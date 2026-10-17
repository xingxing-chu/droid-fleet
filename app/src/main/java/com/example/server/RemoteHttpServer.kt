package com.example.server

import android.content.Context
import android.os.PowerManager
import com.example.data.model.DeviceInfo
import com.example.data.model.LogEntry
import com.example.data.model.PairedClient
import com.example.data.model.PopupNotificationEvent
import com.example.data.storage.RemoteStorageManager
import com.example.hardware.DeviceHardwareManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RemoteHttpServer(
    private val hardwareManager: DeviceHardwareManager,
    val port: Int = 8888
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var listenJob: Job? = null

    private val storageManager = RemoteStorageManager(hardwareManager.context)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // 6-digit PIN code required for binding (persisted across restarts)
    private val _pinCode = MutableStateFlow(storageManager.getPinCode())
    val pinCode: StateFlow<String> = _pinCode.asStateFlow()

    // Paired clients: token -> PairedClient (persisted across restarts & clearing background)
    private val pairedClients = ConcurrentHashMap<String, PairedClient>()
    private val _pairedClientsList = MutableStateFlow<List<PairedClient>>(emptyList())
    val pairedClientsList: StateFlow<List<PairedClient>> = _pairedClientsList.asStateFlow()

    // Command activity log
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // In-app Popup event flow
    private val _popupEvent = MutableSharedFlow<PopupNotificationEvent>()
    val popupEvent: SharedFlow<PopupNotificationEvent> = _popupEvent.asSharedFlow()

    init {
        try {
            val savedClients = storageManager.loadPairedClients()
            pairedClients.putAll(savedClients)
            syncPairedClients()
            if (savedClients.isNotEmpty()) {
                addLog("系统", "信任库加载", "已成功载入 ${savedClients.size} 台历史绑定受控端，无需重新配对")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updatePin(newPin: String) {
        if (newPin.isNotBlank()) {
            val clean = newPin.trim()
            _pinCode.value = clean
            storageManager.savePinCode(clean)
            addLog("系统", "PIN变更", "数字密码已更新为 $clean，已绑定设备的永久密钥不受影响")
        }
    }

    fun regeneratePin(): String {
        val newPin = (100000..999999).random().toString()
        _pinCode.value = newPin
        storageManager.savePinCode(newPin)
        addLog("系统", "PIN重置", "生成新数字密码: $newPin，已绑定设备的永久密钥不受影响")
        return newPin
    }

    fun unbindClient(token: String) {
        pairedClients.remove(token)?.let {
            storageManager.removePairedClient(token)
            syncPairedClients()
            addLog("受控端", "解绑客户端", "已手动解除客户端 [${it.name}] (${it.ip}) 的授权")
        }
    }

    fun updateClientRemark(token: String, remark: String) {
        pairedClients[token]?.let { client ->
            val updated = client.copy(remark = remark)
            pairedClients[token] = updated
            storageManager.updateClientRemark(token, remark)
            syncPairedClients()
        }
    }

    fun startServer() {
        if (_isRunning.value) return

        listenJob = scope.launch {
            try {
                serverSocket = ServerSocket(port, 100, InetAddress.getByName("0.0.0.0")).apply {
                    reuseAddress = true
                }
                _isRunning.value = true
                addLog("服务器", "服务启动", "受控服务端已在端口 $port 启动 (监听 0.0.0.0)")

                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        clientSocket.tcpNoDelay = true
                        clientSocket.soTimeout = 15000
                        scope.launch {
                            handleClientSocket(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                addLog("服务器", "启动异常", "端口绑定失败: ${e.message}", false)
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun stopServer() {
        _isRunning.value = false
        listenJob?.cancel()
        listenJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        addLog("服务器", "服务停止", "受控服务端已停止")
    }

    private suspend fun handleClientSocket(socket: Socket) = withContext(Dispatchers.IO) {
        val clientIp = socket.inetAddress?.hostAddress ?: "未知IP"
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val output = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0].uppercase()
            val uri = parts[1]

            // Read headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var headerLine = reader.readLine()
            while (!headerLine.isNullOrEmpty()) {
                val colonIdx = headerLine.indexOf(':')
                if (colonIdx > 0) {
                    val key = headerLine.substring(0, colonIdx).trim().lowercase()
                    val value = headerLine.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
                headerLine = reader.readLine()
            }

            // Read body if exists
            val body = if (contentLength > 0) {
                val charBuffer = CharArray(contentLength)
                var bytesRead = 0
                while (bytesRead < contentLength) {
                    val read = reader.read(charBuffer, bytesRead, contentLength - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }
                String(charBuffer, 0, bytesRead)
            } else {
                ""
            }

            // Route request with headers
            dispatchRoute(method, uri, body, headers, clientIp, output)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun dispatchRoute(
        method: String,
        uri: String,
        body: String,
        headers: Map<String, String>,
        clientIp: String,
        output: OutputStream
    ) {
        val path = uri.substringBefore('?')
        val query = if (uri.contains('?')) uri.substringAfter('?') else ""

        // Handle OPTIONS for CORS preflight
        if (method == "OPTIONS") {
            sendResponse(output, 204, "No Content", "text/plain", "")
            return
        }

        when {
            path == "/" || path == "/index.html" -> {
                sendResponse(output, 200, "OK", "text/html; charset=utf-8", getWebControllerHtml())
            }

            path == "/api/ping" -> {
                val deviceInfo = hardwareManager.getDeviceInfo(port)
                val json = JSONObject().apply {
                    put("code", 0)
                    put("status", "online")
                    put("server", "AndroidRemoteReceiver")
                    put("pairedCount", pairedClients.size)
                    put("model", deviceInfo.model)
                    put("batteryLevel", deviceInfo.batteryLevel)
                    put("isCharging", deviceInfo.isCharging)
                    put("mac", deviceInfo.mac)
                    put("serial", deviceInfo.serial)
                    put("androidId", deviceInfo.androidId)
                    put("serialSource", deviceInfo.serialSource)
                    put("macSource", deviceInfo.macSource)
                    put("assetTag", deviceInfo.assetTag)
                }
                sendJsonResponse(output, 200, json.toString())
            }

            path == "/api/bind" && method == "POST" -> {
                handleBind(body, clientIp, output)
            }

            path == "/api/unbind" && method == "POST" -> {
                handleUnbind(body, clientIp, output)
            }

            path == "/api/verify" -> {
                handleVerify(query, body, headers, clientIp, output)
            }

            path == "/api/status" -> {
                handleStatus(query, headers, output)
            }

            path == "/api/device/asset" && method == "POST" -> {
                handleAssetCalibration(body, clientIp, headers, output)
            }

            path == "/api/command" && method == "POST" -> {
                handleCommand(body, clientIp, headers, output)
            }

            path == "/api/download/win11_controller.py" -> {
                sendResponse(output, 200, "OK", "text/x-python; charset=utf-8", getPythonControllerSource())
            }

            else -> {
                val err = JSONObject().apply {
                    put("code", 404)
                    put("message", "接口不存在: $path")
                }
                sendJsonResponse(output, 404, err.toString())
            }
        }
    }

    private fun handleBind(body: String, clientIp: String, output: OutputStream) {
        try {
            val json = JSONObject(body)
            val submittedPin = json.optString("pin", "").trim()
            val clientName = json.optString("clientName", "Win11 控制台")

            if (submittedPin == _pinCode.value) {
                // PIN matches! Generate permanent token and client secret
                val token = UUID.randomUUID().toString().replace("-", "")
                val clientSecret = UUID.randomUUID().toString().replace("-", "")
                val pairedClient = PairedClient(
                    token = token,
                    name = clientName,
                    ip = clientIp,
                    pairedAt = System.currentTimeMillis(),
                    lastActiveAt = System.currentTimeMillis(),
                    clientSecret = clientSecret
                )
                pairedClients[token] = pairedClient
                storageManager.savePairedClient(pairedClient)
                syncPairedClients()

                val deviceInfo = hardwareManager.getDeviceInfo(port)
                val responseJson = JSONObject().apply {
                    put("code", 0)
                    put("message", "绑定成功！已建立永久信任密钥，即使手机重启或清理后台均无需重新绑定")
                    put("token", token)
                    put("clientSecret", clientSecret)
                    put("deviceSecret", storageManager.getDeviceSecret())
                    put("deviceInfo", JSONObject().apply {
                        put("ipv4", deviceInfo.ipv4)
                        put("mac", deviceInfo.mac)
                        put("serial", deviceInfo.serial)
                        put("model", deviceInfo.model)
                        put("androidVersion", deviceInfo.androidVersion)
                        put("port", deviceInfo.port)
                        put("batteryLevel", deviceInfo.batteryLevel)
                        put("isCharging", deviceInfo.isCharging)
                        put("androidId", deviceInfo.androidId)
                        put("serialSource", deviceInfo.serialSource)
                        put("macSource", deviceInfo.macSource)
                        put("assetTag", deviceInfo.assetTag)
                    })
                }

                addLog(
                    clientName,
                    "绑定成功",
                    "密码验证通过 ($clientIp)，双方已建立永久信任绑定（免除日后重复配对）。设备: ${deviceInfo.model} (${deviceInfo.serial})"
                )
                sendJsonResponse(output, 200, responseJson.toString())
            } else {
                // PIN mismatch
                val errJson = JSONObject().apply {
                    put("code", 403)
                    put("message", "数字密码错误！请查看安卓屏幕上显示的6位PIN码")
                }
                addLog(clientName, "绑定失败", "提交密码 [$submittedPin] 与本机PIN不符 ($clientIp)", false)
                sendJsonResponse(output, 403, errJson.toString())
            }
        } catch (e: Exception) {
            val errJson = JSONObject().apply {
                put("code", 400)
                put("message", "请求格式错误: ${e.message}")
            }
            sendJsonResponse(output, 400, errJson.toString())
        }
    }

    private fun handleUnbind(body: String, clientIp: String, output: OutputStream) {
        try {
            val json = JSONObject(body)
            val token = json.optString("token", "").trim()
            val client = pairedClients.remove(token)
            if (client != null) {
                storageManager.removePairedClient(token)
                syncPairedClients()
                addLog(client.name, "客户端解绑", "客户端 ($clientIp) 已主动解除绑定关系")
                val resp = JSONObject().apply {
                    put("code", 0)
                    put("message", "已成功解除与该设备的绑定授权")
                }
                sendJsonResponse(output, 200, resp.toString())
            } else {
                val resp = JSONObject().apply {
                    put("code", 0)
                    put("message", "设备不在已绑定列表中或已解绑")
                }
                sendJsonResponse(output, 200, resp.toString())
            }
        } catch (e: Exception) {
            val err = JSONObject().apply {
                put("code", 400)
                put("message", "请求格式错误: ${e.message}")
            }
            sendJsonResponse(output, 400, err.toString())
        }
    }

    private fun handleVerify(
        query: String,
        body: String,
        headers: Map<String, String>,
        clientIp: String,
        output: OutputStream
    ) {
        val queryParams = parseQueryParams(query)
        var token = queryParams["token"]
        if (token.isNullOrBlank() && body.isNotBlank()) {
            try {
                val json = JSONObject(body)
                token = json.optString("token", "").ifBlank { json.optString("secret", "") }
            } catch (_: Exception) {}
        }
        if (token.isNullOrBlank()) {
            val headerToken = headers["x-auth-token"] ?: headers["authorization"]
            if (!headerToken.isNullOrBlank()) {
                token = headerToken.removePrefix("Bearer ").trim()
            }
        }

        val client = if (!token.isNullOrBlank()) pairedClients[token] else null
        if (client != null && !token.isNullOrBlank()) {
            if (client.ip != clientIp) {
                val updated = client.copy(ip = clientIp, lastActiveAt = System.currentTimeMillis())
                pairedClients[token!!] = updated
                storageManager.savePairedClient(updated)
                syncPairedClients()
            }
            val resp = JSONObject().apply {
                put("code", 0)
                put("valid", true)
                put("clientName", client.name)
                put("remark", client.remark)
                put("message", "永久认证密钥有效")
            }
            sendJsonResponse(output, 200, resp.toString())
        } else {
            val resp = JSONObject().apply {
                put("code", 401)
                put("valid", false)
                put("message", "未授权或已被解除绑定，请在电脑端重新输入PIN码绑定")
            }
            sendJsonResponse(output, 401, resp.toString())
        }
    }

    private fun handleStatus(query: String, headers: Map<String, String>, output: OutputStream) {
        val queryParams = parseQueryParams(query)
        var token = queryParams["token"]
        if (token.isNullOrBlank()) {
            val headerToken = headers["x-auth-token"] ?: headers["authorization"]
            if (!headerToken.isNullOrBlank()) {
                token = headerToken.removePrefix("Bearer ").trim()
            }
        }

        // If valid token provided, update last active
        if (!token.isNullOrBlank() && pairedClients.containsKey(token)) {
            val client = pairedClients[token]!!
            pairedClients[token] = client.copy(lastActiveAt = System.currentTimeMillis())
            syncPairedClients()
        }

        val deviceInfo = hardwareManager.getDeviceInfo(port)
        val resp = JSONObject().apply {
            put("code", 0)
            put("status", "online")
            put("isRinging", hardwareManager.isRinging.value)
            put("isFlashlightOn", hardwareManager.isFlashlightOn.value)
            put("isStrobeActive", hardwareManager.isStrobeActive.value)
            put("batteryLevel", deviceInfo.batteryLevel)
            put("isCharging", deviceInfo.isCharging)
            put("model", deviceInfo.model)
            put("mac", deviceInfo.mac)
            put("serial", deviceInfo.serial)
            put("androidId", deviceInfo.androidId)
            put("serialSource", deviceInfo.serialSource)
            put("macSource", deviceInfo.macSource)
            put("assetTag", deviceInfo.assetTag)
            put("timestamp", System.currentTimeMillis())
        }
        sendJsonResponse(output, 200, resp.toString())
    }

    private fun handleAssetCalibration(
        body: String,
        clientIp: String,
        headers: Map<String, String>,
        output: OutputStream
    ) {
        try {
            val json = JSONObject(body)
            var token = json.optString("token", "")
            if (token.isBlank()) {
                token = headers["x-auth-token"] ?: headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: ""
            }
            if (token.isBlank() || !pairedClients.containsKey(token)) {
                val err = JSONObject().apply {
                    put("code", 401)
                    put("message", "未授权的访问，请先通过PIN码配对绑定")
                }
                sendJsonResponse(output, 401, err.toString())
                return
            }

            val serial = if (json.has("serial")) json.optString("serial") else null
            val mac = if (json.has("mac")) json.optString("mac") else null
            val assetTag = if (json.has("assetTag")) json.optString("assetTag") else null

            hardwareManager.calibrateAsset(serial, mac, assetTag)
            val updatedInfo = hardwareManager.getDeviceInfo(port)

            addLog(
                "硬件管理中心",
                "资产信息已校准",
                "来源: $clientIp, 序列号: ${updatedInfo.serial}, 资产编号: ${updatedInfo.assetTag}"
            )

            val resp = JSONObject().apply {
                put("code", 0)
                put("message", "设备资产硬件信息已成功保存！")
                put("deviceInfo", JSONObject().apply {
                    put("mac", updatedInfo.mac)
                    put("serial", updatedInfo.serial)
                    put("assetTag", updatedInfo.assetTag)
                    put("serialSource", updatedInfo.serialSource)
                    put("macSource", updatedInfo.macSource)
                    put("androidId", updatedInfo.androidId)
                })
            }
            sendJsonResponse(output, 200, resp.toString())
        } catch (e: Exception) {
            val err = JSONObject().apply {
                put("code", 500)
                put("message", "保存失败: ${e.message}")
            }
            sendJsonResponse(output, 500, err.toString())
        }
    }

    private suspend fun handleCommand(
        body: String,
        clientIp: String,
        headers: Map<String, String>,
        output: OutputStream
    ) {
        val pm = hardwareManager.context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val cmdWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteHttpServer::CmdWakeLock")
        try {
            cmdWakeLock?.acquire(8000)
        } catch (_: Exception) {}

        try {
            val json = JSONObject(body)
            var token = json.optString("token", "")
            if (token.isBlank()) {
                token = json.optString("secret", "")
            }
            if (token.isBlank()) {
                val headerToken = headers["x-auth-token"] ?: headers["authorization"]
                if (!headerToken.isNullOrBlank()) {
                    token = headerToken.removePrefix("Bearer ").trim()
                }
            }

            val action = json.optString("action", "")

            // Validate token against persistent paired clients
            val client = pairedClients[token]
            if (client == null) {
                val err = JSONObject().apply {
                    put("code", 401)
                    put("message", "未授权或Token无效。若您曾手动移除了设备，请在控制台重新输入PIN码绑定。")
                }
                addLog("未知客户端", "拒绝命令", "Token验证失败 ($action from $clientIp)", false)
                sendJsonResponse(output, 401, err.toString())
                return
            }

            // Update client activity
            pairedClients[token] = client.copy(lastActiveAt = System.currentTimeMillis())
            syncPairedClients()

            var message = "命令执行完成"
            when (action) {
                "ring" -> {
                    val duration = json.optInt("duration", 15)
                    hardwareManager.startRinging(duration)
                    message = "已启动响铃 (${duration}秒)"
                    addLog(client.name, "响铃命令", message)
                }

                "ring_stop" -> {
                    hardwareManager.stopRinging()
                    message = "已停止响铃并恢复原音量"
                    addLog(client.name, "停止响铃", message)
                }

                "volume", "set_volume" -> {
                    val percent = json.optInt("percent", json.optInt("volume", 10))
                    val ok = hardwareManager.setVolume(percent)
                    message = if (ok) "已调整设备系统音量至 ${percent}%" else "音量调节受限"
                    addLog(client.name, "音量调节", message)
                }

                "flash" -> {
                    val mode = json.optString("mode", "on")
                    val duration = json.optInt("duration", 10)
                    when (mode) {
                        "on" -> {
                            val ok = hardwareManager.setFlashlight(true)
                            message = if (ok) "闪光灯已开启" else "闪光灯打开失败（无可用闪光灯）"
                        }
                        "off" -> {
                            hardwareManager.setFlashlight(false)
                            message = "闪光灯已关闭"
                        }
                        "strobe" -> {
                            hardwareManager.startStrobeFlashlight(duration)
                            message = "闪光灯爆闪已开启 (${duration}秒)"
                        }
                    }
                    addLog(client.name, "闪光灯命令", message)
                }

                "flash_off" -> {
                    hardwareManager.setFlashlight(false)
                    message = "闪光灯已关闭"
                    addLog(client.name, "关闭闪光灯", message)
                }

                "notify" -> {
                    val title = json.optString("title", "来自控制端的通知")
                    val text = json.optString("message", "设备已被呼叫")
                    hardwareManager.showPopupNotification(title, text, client.name)

                    message = "弹窗通知已弹出: $title"
                    addLog(client.name, "弹窗通知", "$title - $text")
                }

                else -> {
                    message = "未知动作: $action"
                    addLog(client.name, "未知命令", action, false)
                }
            }

            val resp = JSONObject().apply {
                put("code", 0)
                put("message", message)
                put("action", action)
                put("isRinging", hardwareManager.isRinging.value)
                put("isFlashlightOn", hardwareManager.isFlashlightOn.value)
            }
            sendJsonResponse(output, 200, resp.toString())
        } catch (e: Exception) {
            val err = JSONObject().apply {
                put("code", 500)
                put("message", "处理命令出错: ${e.message}")
            }
            sendJsonResponse(output, 500, err.toString())
        } finally {
            try {
                if (cmdWakeLock?.isHeld == true) {
                    cmdWakeLock.release()
                }
            } catch (_: Exception) {}
        }
    }

    private fun syncPairedClients() {
        _pairedClientsList.value = pairedClients.values.toList().sortedByDescending { it.lastActiveAt }
    }

    private fun addLog(source: String, action: String, details: String, isSuccess: Boolean = true) {
        val entry = LogEntry(
            source = source,
            action = action,
            details = details,
            isSuccess = isSuccess
        )
        val current = _logs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 100) {
            current.removeAt(current.size - 1)
        }
        _logs.value = current
    }

    private fun sendJsonResponse(output: OutputStream, statusCode: Int, json: String) {
        sendResponse(output, statusCode, if (statusCode == 200) "OK" else "Error", "application/json; charset=utf-8", json)
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        try {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val header = buildString {
                append("HTTP/1.1 $statusCode $statusText\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type, Authorization, X-Auth-Token\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(header.toByteArray(StandardCharsets.UTF_8))
            output.write(bytes)
            output.flush()
        } catch (_: Exception) {
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                result[key] = value
            }
        }
        return result
    }

    private fun generateRandomPin(): String {
        val num = (100000..999999).random()
        return num.toString()
    }

    private fun getWebControllerHtml(): String {
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Win11 控制终端 - 安卓远程受控端</title>
    <style>
        :root {
            --bg: #0b1329;
            --surface: #141e38;
            --surface-card: #1b284c;
            --primary: #00e5ff;
            --primary-hover: #33ebff;
            --accent: #7c4dff;
            --success: #00e676;
            --danger: #ff5252;
            --text-main: #f0f4fc;
            --text-sub: #8fa0c0;
            --border: #263866;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Microsoft YaHei", sans-serif; }
        body { background: var(--bg); color: var(--text-main); padding: 24px; min-height: 100vh; }
        .container { max-width: 900px; margin: 0 auto; }
        header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid var(--border); }
        .logo-title { display: flex; align-items: center; gap: 12px; }
        .badge { padding: 4px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
        .badge-online { background: rgba(0, 230, 118, 0.15); color: var(--success); border: 1px solid var(--success); }
        .badge-offline { background: rgba(255, 82, 82, 0.15); color: var(--danger); border: 1px solid var(--danger); }
        .pulse-dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
        .card { background: var(--surface); border: 1px solid var(--border); border-radius: 16px; padding: 20px; margin-bottom: 20px; }
        .card-title { font-size: 16px; font-weight: 600; margin-bottom: 16px; color: var(--primary); display: flex; align-items: center; gap: 8px; }
        .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
        @media(max-width: 650px) { .grid-2 { grid-template-columns: 1fr; } }
        .input-group { margin-bottom: 14px; }
        label { display: block; font-size: 13px; color: var(--text-sub); margin-bottom: 6px; }
        input, textarea { width: 100%; padding: 10px 14px; background: var(--surface-card); border: 1px solid var(--border); border-radius: 8px; color: #fff; font-size: 14px; }
        input:focus, textarea:focus { outline: none; border-color: var(--primary); }
        .btn { cursor: pointer; padding: 10px 18px; border-radius: 8px; border: none; font-size: 14px; font-weight: 600; transition: 0.2s; display: inline-flex; align-items: center; justify-content: center; gap: 8px; }
        .btn-primary { background: var(--primary); color: #000; }
        .btn-primary:hover { background: var(--primary-hover); }
        .btn-danger { background: var(--danger); color: #fff; }
        .btn-warning { background: #ffb703; color: #000; }
        .btn-outline { background: transparent; border: 1px solid var(--border); color: var(--text-main); }
        .btn-outline:hover { background: var(--surface-card); }
        .btn-group { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 10px; }
        .info-row { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid rgba(255,255,255,0.05); }
        .info-label { color: var(--text-sub); font-size: 13px; }
        .info-value { font-weight: 600; font-family: monospace; color: var(--primary); }
        .log-box { background: #070c1a; border-radius: 8px; padding: 12px; height: 160px; overflow-y: auto; font-family: monospace; font-size: 12px; color: #a5b4d4; }
        .log-item { margin-bottom: 6px; }
        .log-time { color: #5c6e94; margin-right: 8px; }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <div class="logo-title">
                <h2>⚡ Win11 远程控制终端</h2>
            </div>
            <div id="statusBadge" class="badge badge-offline">
                <span class="pulse-dot"></span> <span id="statusText">检查连接...</span>
            </div>
        </header>

        <div class="card" id="bindSection">
            <div class="card-title">🔐 1. 输入受控安卓端数字密码绑定</div>
            <p style="font-size:13px;color:var(--text-sub);margin-bottom:14px;">在被控手机屏幕上查看当前显示的 6 位 PIN 密码，输入后点击绑定即可获得控制权及设备硬件信息。</p>
            <div class="grid-2">
                <div class="input-group">
                    <label>受控端数字密码 (PIN)</label>
                    <input type="text" id="pinInput" placeholder="输入手机显示的6位数字PIN" maxlength="10">
                </div>
                <div class="input-group">
                    <label>当前控制端标识</label>
                    <input type="text" id="clientNameInput" value="Windows 11 PC">
                </div>
            </div>
            <button class="btn btn-primary" onclick="doBind()">🔗 立即验证并绑定</button>
        </div>

        <div class="card" id="deviceSection">
            <div class="card-title">📱 2. 受控设备信息 (绑定后同步)</div>
            <div class="info-row"><span class="info-label">IPv4 地址:</span><span class="info-value" id="devIp">--</span></div>
            <div class="info-row"><span class="info-label">物理 MAC 地址:</span><span class="info-value" id="devMac">--</span></div>
            <div class="info-row"><span class="info-label">设备唯一序列号:</span><span class="info-value" id="devSerial">--</span></div>
            <div class="info-row"><span class="info-label">机型与系统:</span><span class="info-value" id="devModel">--</span></div>
            <div class="info-row"><span class="info-label">电量状态:</span><span class="info-value" id="devBattery">--</span></div>
            
            <div style="margin-top: 14px;">
                <label>设备备注 (本地自动保存)</label>
                <div style="display:flex;gap:8px;">
                    <input type="text" id="devRemark" placeholder="例如：客厅备用机 / 小米测试机">
                    <button class="btn btn-outline" onclick="saveRemark()">保存备注</button>
                </div>
            </div>
        </div>

        <div class="card" id="controlSection">
            <div class="card-title">🎮 3. 远程命令控制</div>
            <div class="grid-2">
                <div>
                    <h4 style="font-size:14px;margin-bottom:8px;">🔔 声音警报</h4>
                    <div class="btn-group">
                        <button class="btn btn-primary" onclick="sendCommand('ring', {duration: 10})">响铃 10秒</button>
                        <button class="btn btn-primary" onclick="sendCommand('ring', {duration: 30})">响铃 30秒</button>
                        <button class="btn btn-danger" onclick="sendCommand('ring_stop')">停止响铃</button>
                    </div>
                </div>
                <div>
                    <h4 style="font-size:14px;margin-bottom:8px;">🔦 闪光灯控制</h4>
                    <div class="btn-group">
                        <button class="btn btn-primary" onclick="sendCommand('flash', {mode: 'on'})">常亮开启</button>
                        <button class="btn btn-warning" onclick="sendCommand('flash', {mode: 'strobe', duration: 10})">闪烁爆闪 (10s)</button>
                        <button class="btn btn-danger" onclick="sendCommand('flash', {mode: 'off'})">关闭闪光灯</button>
                    </div>
                </div>
            </div>

            <div style="margin-top:20px;padding-top:16px;border-top:1px solid var(--border);">
                <h4 style="font-size:14px;margin-bottom:8px;">💬 远程弹窗通知</h4>
                <div class="grid-2" style="margin-bottom:10px;">
                    <input type="text" id="notifyTitle" placeholder="通知标题 (如：请注意)" value="来自Win11的呼叫">
                    <input type="text" id="notifyMsg" placeholder="通知内容 (如：请接听或查看屏幕)" value="请查看Windows控制端发送的提醒！">
                </div>
                <button class="btn btn-primary" onclick="sendNotify()">🚀 发送弹窗通知到安卓端</button>
            </div>
        </div>

        <div class="card" style="border: 1px solid var(--primary); background: rgba(0, 229, 255, 0.05);">
            <div class="card-title">🖥️ Windows 11 多设备批量控制终端</div>
            <p style="font-size:13px;color:var(--text-sub);margin-bottom:12px;">
                需要同时管理多台安卓设备、局域网一键并发扫描、批量响铃与一键群发通知？<br>
                运行 Windows 11 客户端 <b>win11_controller.py</b>，零依赖、原生现代化界面，支持多台设备集中批量控制！
            </p>
            <div style="display:flex;gap:10px;flex-wrap:wrap;">
                <a href="/api/download/win11_controller.py" download="win11_controller.py" class="btn btn-primary" style="text-decoration:none;">
                    📥 下载最新多设备批量控制客户端 (win11_controller.py)
                </a>
            </div>
        </div>

        <div class="card">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">
                <div class="card-title" style="margin-bottom:0;">📋 运行日志</div>
                <button class="btn btn-outline" style="padding:4px 10px;font-size:12px;" onclick="clearLogs()">清空</button>
            </div>
            <div class="log-box" id="logBox"></div>
        </div>
    </div>

    <script>
        let authToken = localStorage.getItem('remote_auth_token') || '';
        let heartBeatTimer = null;

        function appendLog(msg) {
            const box = document.getElementById('logBox');
            const now = new Date().toLocaleTimeString();
            const div = document.createElement('div');
            div.className = 'log-item';
            div.innerHTML = '<span class="log-time">[' + now + ']</span> ' + msg;
            box.appendChild(div);
            box.scrollTop = box.scrollHeight;
        }

        function clearLogs() {
            document.getElementById('logBox').innerHTML = '';
        }

        async function doBind() {
            const pin = document.getElementById('pinInput').value.trim();
            const clientName = document.getElementById('clientNameInput').value.trim() || 'Windows 11 PC';
            if (!pin) {
                alert('请输入安卓端屏幕上显示的数字密码 PIN');
                return;
            }

            appendLog('正在向受控端提交 PIN 码验证: ' + pin);
            try {
                const res = await fetch('/api/bind', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ pin, clientName })
                });
                const data = await res.json();
                if (data.code === 0) {
                    authToken = data.token;
                    localStorage.setItem('remote_auth_token', authToken);
                    appendLog('🎉 绑定成功！已获得设备硬件信息');
                    displayDeviceInfo(data.deviceInfo);
                    startHeartbeat();
                } else {
                    appendLog('❌ 绑定失败: ' + data.message);
                    alert(data.message);
                }
            } catch (err) {
                appendLog('❌ 请求失败: ' + err.message);
                alert('连接受控端失败，请检查手机是否开启服务');
            }
        }

        function displayDeviceInfo(info) {
            if (!info) return;
            document.getElementById('devIp').innerText = info.ipv4;
            document.getElementById('devMac').innerText = info.mac;
            document.getElementById('devSerial').innerText = info.serial;
            document.getElementById('devModel').innerText = (info.model || '') + ' (' + (info.androidVersion || '') + ')';
            document.getElementById('devBattery').innerText = (info.batteryLevel || 100) + '%' + (info.isCharging ? ' (充电中)' : '');
            
            // load saved remark
            const savedRemark = localStorage.getItem('remark_' + info.serial) || '';
            document.getElementById('devRemark').value = savedRemark;
        }

        function saveRemark() {
            const serial = document.getElementById('devSerial').innerText;
            const remark = document.getElementById('devRemark').value;
            if (serial && serial !== '--') {
                localStorage.setItem('remark_' + serial, remark);
                appendLog('已保存设备备注: ' + remark);
                alert('备注保存成功！');
            }
        }

        async function sendCommand(action, params = {}) {
            if (!authToken) {
                alert('请先输入数字密码绑定设备！');
                return;
            }
            appendLog('发送命令: ' + action + ' ' + JSON.stringify(params));
            try {
                const res = await fetch('/api/command', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ token: authToken, action, ...params })
                });
                const data = await res.json();
                if (data.code === 0) {
                    appendLog('✅ ' + data.message);
                } else {
                    appendLog('⚠️ 执行失败: ' + data.message);
                    if (data.code === 401) {
                        alert('授权失效，请重新绑定');
                    }
                }
            } catch (err) {
                appendLog('❌ 发送出错: ' + err.message);
            }
        }

        function sendNotify() {
            const title = document.getElementById('notifyTitle').value;
            const message = document.getElementById('notifyMsg').value;
            sendCommand('notify', { title, message });
        }

        async function checkStatus() {
            const badge = document.getElementById('statusBadge');
            const text = document.getElementById('statusText');
            try {
                const url = authToken ? '/api/status?token=' + authToken : '/api/ping';
                const res = await fetch(url);
                if (res.ok) {
                    badge.className = 'badge badge-online';
                    text.innerText = '设备在线';
                } else {
                    badge.className = 'badge badge-offline';
                    text.innerText = '设备离线';
                }
            } catch (e) {
                badge.className = 'badge badge-offline';
                text.innerText = '设备离线';
            }
        }

        function startHeartbeat() {
            if (heartBeatTimer) clearInterval(heartBeatTimer);
            checkStatus();
            heartBeatTimer = setInterval(checkStatus, 3000);
        }

        window.onload = function() {
            appendLog('Win11 控制终端已就绪。');
            startHeartbeat();
        };
    </script>
</body>
</html>
        """.trimIndent()
    }

    fun getPythonControllerSource(): String {
        val assetContent = hardwareManager.readAsset("win11_controller.py")
        return if (assetContent.isNotBlank()) assetContent else "# Windows 11 Multi Device Controller"
    }
}
