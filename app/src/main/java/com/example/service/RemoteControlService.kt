package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.DeviceInfo
import com.example.data.model.LogEntry
import com.example.data.model.PairedClient
import com.example.data.model.PopupNotificationEvent
import com.example.hardware.DeviceHardwareManager
import com.example.server.RemoteHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object RemoteControlManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isInitialized = false

    lateinit var hardwareManager: DeviceHardwareManager
        private set
    lateinit var server: RemoteHttpServer
        private set
    lateinit var ipChangeNotifier: IpChangeNotifier
        private set

    val isRunning: StateFlow<Boolean>
        get() = server.isRunning

    val pinCode: StateFlow<String>
        get() = server.pinCode

    val pairedClients: StateFlow<List<PairedClient>>
        get() = server.pairedClientsList

    val logs: StateFlow<List<LogEntry>>
        get() = server.logs

    val popupEvent: SharedFlow<PopupNotificationEvent>
        get() = server.popupEvent

    val isRinging: StateFlow<Boolean>
        get() = hardwareManager.isRinging

    val isFlashlightOn: StateFlow<Boolean>
        get() = hardwareManager.isFlashlightOn

    val isStrobeActive: StateFlow<Boolean>
        get() = hardwareManager.isStrobeActive

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _isNotificationSilent = MutableStateFlow(false)
    val isNotificationSilent: StateFlow<Boolean> = _isNotificationSilent.asStateFlow()

    private val _isNotificationBlocked = MutableStateFlow(false)
    val isNotificationBlocked: StateFlow<Boolean> = _isNotificationBlocked.asStateFlow()

    fun initialize(context: Context) {
        if (isInitialized) return
        val appContext = context.applicationContext
        hardwareManager = DeviceHardwareManager(appContext)
        server = RemoteHttpServer(hardwareManager, port = 8888)
        ipChangeNotifier = IpChangeNotifier(appContext, hardwareManager, server)

        val sp = appContext.getSharedPreferences(RemoteControlService.PREFS_NAME, Context.MODE_PRIVATE)
        _isNotificationSilent.value = sp.getBoolean(RemoteControlService.KEY_NOTIFICATION_SILENT, false)
        checkNotificationStatus(appContext)

        refreshDeviceInfo()
        isInitialized = true
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }
    }

    fun refreshDeviceInfo() {
        if (::hardwareManager.isInitialized) {
            _deviceInfo.value = hardwareManager.getDeviceInfo(8888)
        }
    }

    fun checkNotificationStatus(context: Context) {
        try {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) {
                _isNotificationBlocked.value = true
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val chId = if (_isNotificationSilent.value) {
                    RemoteControlService.CHANNEL_SERVICE_SILENT
                } else {
                    RemoteControlService.CHANNEL_SERVICE_STANDARD
                }
                val ch = manager?.getNotificationChannel(chId)
                if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) {
                    _isNotificationBlocked.value = true
                    return
                }
            }
            _isNotificationBlocked.value = false
        } catch (_: Exception) {
            _isNotificationBlocked.value = false
        }
    }

    fun setNotificationSilentMode(context: Context, silent: Boolean) {
        _isNotificationSilent.value = silent
        val sp = context.getSharedPreferences(RemoteControlService.PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(RemoteControlService.KEY_NOTIFICATION_SILENT, silent).apply()

        checkNotificationStatus(context)

        val intent = Intent(context, RemoteControlService::class.java).apply {
            action = RemoteControlService.ACTION_UPDATE_NOTIFICATION
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {}
    }

    fun openNotificationSettings(context: Context) {
        try {
            val chId = if (_isNotificationSilent.value) {
                RemoteControlService.CHANNEL_SERVICE_SILENT
            } else {
                RemoteControlService.CHANNEL_SERVICE_STANDARD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, chId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    fun startService(context: Context) {
        initialize(context)
        val intent = Intent(context, RemoteControlService::class.java).apply {
            action = RemoteControlService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e("RemoteControlManager", "Error calling startForegroundService", e)
        }
    }

    fun stopService(context: Context) {
        val intent = Intent(context, RemoteControlService::class.java).apply {
            action = RemoteControlService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun regeneratePin(): String {
        return server.regeneratePin()
    }

    fun updatePin(pin: String) {
        server.updatePin(pin)
    }

    fun unbindClient(token: String) {
        server.unbindClient(token)
    }

    fun updateClientRemark(token: String, remark: String) {
        server.updateClientRemark(token, remark)
    }

    fun triggerLocalRingTest(durationSeconds: Int = 5) {
        hardwareManager.startRinging(durationSeconds)
    }

    fun stopRing() {
        hardwareManager.stopRinging()
    }

    fun toggleFlashlight(): Boolean {
        val nextState = !hardwareManager.isFlashlightOn.value
        return hardwareManager.setFlashlight(nextState)
    }

    fun strobeFlashlight(durationSeconds: Int = 5) {
        hardwareManager.startStrobeFlashlight(durationSeconds)
    }

    fun testPopupNotification() {
        scope.launch {
            hardwareManager.showPopupNotification("本地测试提醒", "这是一条测试弹窗，验证锁屏与应用覆盖显示", "受控端测试")
        }
    }
}

class RemoteControlService : Service() {

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val screenAndNetworkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("RemoteControlService", "Screen locked/off, reaffirming CPU & WiFi lock")
                    acquireLocks()
                }
                Intent.ACTION_SCREEN_ON -> {
                    acquireLocks()
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                @Suppress("DEPRECATION")
                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    acquireLocks()
                    RemoteControlManager.refreshDeviceInfo()
                    RemoteControlManager.ipChangeNotifier.checkAndNotifyIfChanged(force = false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RemoteControlManager.initialize(this)
        createChannel()
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                @Suppress("DEPRECATION")
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }
            registerReceiver(screenAndNetworkReceiver, filter)
        } catch (e: Exception) {
            Log.e("RemoteControlService", "Error registering receiver", e)
        }
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseLocks()
                RemoteControlManager.ipChangeNotifier.stop()
                RemoteControlManager.server.stopServer()
                RemoteControlManager.hardwareManager.stopRinging()
                RemoteControlManager.hardwareManager.setFlashlight(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_NOTIFICATION -> {
                try {
                    createChannel()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceCompat.startForeground(
                            this,
                            NOTIFICATION_ID_SERVICE,
                            buildServiceNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID_SERVICE, buildServiceNotification())
                    }
                } catch (e: Exception) {
                    Log.e("RemoteControlService", "Failed to update notification: ${e.message}", e)
                }
                return START_STICKY
            }
            ACTION_START, null -> {
                acquireLocks()
                RemoteControlManager.server.startServer()
                RemoteControlManager.refreshDeviceInfo()
                RemoteControlManager.ipChangeNotifier.start()
                try {
                    createChannel()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceCompat.startForeground(
                            this,
                            NOTIFICATION_ID_SERVICE,
                            buildServiceNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID_SERVICE, buildServiceNotification())
                    }
                } catch (e: Exception) {
                    Log.e("RemoteControlService", "Failed to start foreground service: ${e.message}", e)
                }
            }
        }
        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteControlService::CpuWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.e("RemoteControlService", "Failed to acquire CPU wake lock", e)
        }

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "RemoteControlService::WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.e("RemoteControlService", "Failed to acquire WiFi lock", e)
        }

        try {
            if (multicastLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wm.createMulticastLock("RemoteControlService::MulticastLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.e("RemoteControlService", "Failed to acquire Multicast lock", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (_: Exception) {}

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        } catch (_: Exception) {}

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
            multicastLock = null
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Standard channel (Importance Low: shows in shade, status bar icon displayed, no sound)
            val stdChannel = NotificationChannel(
                CHANNEL_SERVICE_STANDARD,
                "受控后台常驻服务 (标准)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持受控服务后台常驻与网络连接，接收Win11控制指令"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(stdChannel)

            // Silent channel (Importance Min: no status bar icon, collapsed in shade, completely silent)
            val minChannel = NotificationChannel(
                CHANNEL_SERVICE_SILENT,
                "受控后台常驻服务 (静默无图标)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "极简静默模式：不占用顶部状态栏图标，仅后台静默保持服务运行"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(minChannel)
        }
    }

    private fun buildServiceNotification(): Notification {
        val isSilent = RemoteControlManager.isNotificationSilent.value
        val channelId = if (isSilent) CHANNEL_SERVICE_SILENT else CHANNEL_SERVICE_STANDARD
        val priority = if (isSilent) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW

        val ip = RemoteControlManager.hardwareManager.getIpv4Address()
        val pin = RemoteControlManager.pinCode.value

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RemoteControlService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (isSilent) "远程受控服务 (静默运行中)" else "远程受控服务运行中 (已防休眠)")
            .setContentText("局域网 IP: $ip:8888 | PIN: $pin")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .setPriority(priority)

        if (!isSilent) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("局域网 IP: $ip:8888\n数字密码 (PIN): $pin\n已保持后台CPU与低延迟Wi-Fi连接，随时响应Win11指令")
            )
            builder.addAction(R.drawable.ic_launcher_foreground, "停止服务", stopIntent)
        }

        return builder.build()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenAndNetworkReceiver)
        } catch (_: Exception) {}
        RemoteControlManager.ipChangeNotifier.stop()
        releaseLocks()
        RemoteControlManager.server.stopServer()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("RemoteControlService", "App swiped from recent tasks. Keeping background service alive.")
        try {
            val restartIntent = Intent(applicationContext, RemoteControlService::class.java).apply {
                action = ACTION_START
            }
            val restartPending = PendingIntent.getService(
                applicationContext,
                1002,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartPending
            )
        } catch (e: Exception) {
            Log.e("RemoteControlService", "Failed to schedule restart on task removed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_UPDATE_NOTIFICATION = "com.example.service.ACTION_UPDATE_NOTIFICATION"

        const val CHANNEL_SERVICE_STANDARD = "remote_control_service_channel"
        const val CHANNEL_SERVICE_SILENT = "remote_control_service_silent"
        const val NOTIFICATION_ID_SERVICE = 1001

        const val PREFS_NAME = "remote_control_prefs"
        const val KEY_NOTIFICATION_SILENT = "notification_silent_mode"
    }
}
