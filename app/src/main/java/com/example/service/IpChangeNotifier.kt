package com.example.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.example.hardware.DeviceHardwareManager
import com.example.server.RemoteHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class IpChangeNotifier(
    private val context: Context,
    private val hardwareManager: DeviceHardwareManager,
    private val server: RemoteHttpServer
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var periodicJob: Job? = null
    private var listenerJob: Job? = null
    private var lastKnownIp: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var udpSocket: DatagramSocket? = null

    companion object {
        const val BROADCAST_PORT = 8889
        private const val TAG = "IpChangeNotifier"
    }

    fun start() {
        stop()

        // 1. Initial IP check & announcement
        lastKnownIp = hardwareManager.getIpv4Address()
        broadcastAnnouncement(oldIp = "", newIp = lastKnownIp ?: "")

        // 2. Register NetworkCallback for real-time network change detection
        registerNetworkCallback()

        // 3. Periodic fallback poll (every 15 seconds) to catch silent DHCP migrations
        periodicJob = scope.launch {
            while (isActive) {
                delay(15_000L)
                checkAndNotifyIfChanged(force = false)
            }
        }

        // 4. UDP Listener for "who_has" queries from Win11 Controller
        startUdpQueryListener()
    }

    fun stop() {
        unregisterNetworkCallback()
        periodicJob?.cancel()
        periodicJob = null
        listenerJob?.cancel()
        listenerJob = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null
    }

    fun checkAndNotifyIfChanged(force: Boolean = false) {
        val currentIp = hardwareManager.getIpv4Address()
        if (currentIp.isBlank() || currentIp == "0.0.0.0" || currentIp == "127.0.0.1") {
            return
        }

        val previous = lastKnownIp
        if (force || previous != currentIp) {
            Log.i(TAG, "IP address changed or forced update: $previous -> $currentIp")
            lastKnownIp = currentIp
            broadcastAnnouncement(oldIp = previous ?: "", newIp = currentIp)
        }
    }

    fun broadcastAnnouncement(oldIp: String, newIp: String) {
        if (newIp.isBlank() || newIp == "0.0.0.0" || newIp == "127.0.0.1") return

        scope.launch {
            val devInfo = hardwareManager.getDeviceInfo(server.port)
            val pairedClients = server.pairedClientsList.value
            val tokenList = pairedClients.map { it.token }

            val json = JSONObject().apply {
                put("type", "ip_announce")
                put("event", "ip_changed")
                put("oldIp", oldIp)
                put("newIp", newIp)
                put("port", server.port)
                put("model", devInfo.model)
                put("serial", devInfo.serial)
                put("mac", devInfo.mac)
                put("androidId", devInfo.androidId)
                put("assetTag", devInfo.assetTag)
                put("tokens", JSONArray(tokenList))
                put("pairedCount", pairedClients.size)
                put("timestamp", System.currentTimeMillis())
            }

            val payload = json.toString().toByteArray(Charsets.UTF_8)
            val destinations = mutableSetOf<String>()

            // 1. Global subnet broadcast
            destinations.add("255.255.255.255")

            // 2. Class C subnet broadcast
            val parts = newIp.split(".")
            if (parts.size == 4) {
                destinations.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
            }

            // 3. Direct unicast to all paired clients' recorded IP addresses
            for (client in pairedClients) {
                if (client.ip.isNotBlank() && client.ip != "0.0.0.0") {
                    destinations.add(client.ip)
                }
            }

            // Send 3 times with 300ms intervals to overcome Wi-Fi UDP packet drop
            for (burst in 1..3) {
                try {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        for (dest in destinations) {
                            try {
                                val address = InetAddress.getByName(dest)
                                val packet = DatagramPacket(payload, payload.size, address, BROADCAST_PORT)
                                socket.send(packet)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error sending IP announce to $dest: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in broadcast socket burst $burst", e)
                }
                delay(300L)
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    checkAndNotifyIfChanged(force = true)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                    checkAndNotifyIfChanged(force = false)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    checkAndNotifyIfChanged(force = false)
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        networkCallback = null
    }

    private fun startUdpQueryListener() {
        listenerJob = scope.launch {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(BROADCAST_PORT))
                }
                udpSocket = socket
                val buffer = ByteArray(2048)

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        handleIncomingUdpPacket(text, packet.address, packet.port)
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP query listener could not bind on port $BROADCAST_PORT: ${e.message}")
            }
        }
    }

    private fun handleIncomingUdpPacket(text: String, senderAddress: InetAddress, senderPort: Int) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            if (type == "who_has" || type == "discover") {
                val reqToken = json.optString("token")
                val reqSerial = json.optString("serial")
                val reqAndroidId = json.optString("androidId")

                val pairedTokens = server.pairedClientsList.value.map { it.token }
                val devInfo = hardwareManager.getDeviceInfo(server.port)

                val matches = (reqToken.isNotBlank() && pairedTokens.contains(reqToken)) ||
                        (reqSerial.isNotBlank() && reqSerial != "--" && devInfo.serial == reqSerial) ||
                        (reqAndroidId.isNotBlank() && devInfo.androidId == reqAndroidId) ||
                        (reqToken.isBlank() && reqSerial.isBlank() && reqAndroidId.isBlank())

                if (matches) {
                    val currentIp = hardwareManager.getIpv4Address()
                    val replyJson = JSONObject().apply {
                        put("type", "ip_announce")
                        put("event", "ip_response")
                        put("newIp", currentIp)
                        put("port", server.port)
                        put("model", devInfo.model)
                        put("serial", devInfo.serial)
                        put("mac", devInfo.mac)
                        put("androidId", devInfo.androidId)
                        put("assetTag", devInfo.assetTag)
                        put("tokens", JSONArray(pairedTokens))
                        put("timestamp", System.currentTimeMillis())
                    }
                    val replyData = replyJson.toString().toByteArray(Charsets.UTF_8)
                    DatagramSocket().use { s ->
                        val replyPacket = DatagramPacket(replyData, replyData.size, senderAddress, senderPort)
                        s.send(replyPacket)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
