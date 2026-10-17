package com.example.hardware

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.DeviceInfo
import com.example.data.storage.RemoteStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class DeviceHardwareManager(val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val storageManager by lazy { RemoteStorageManager(context) }

    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    private var activeRingtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private var ringWakeLock: PowerManager.WakeLock? = null
    private var ringJob: Job? = null
    private var strobeJob: Job? = null

    private val _isRinging = MutableStateFlow(false)
    val isRinging: StateFlow<Boolean> = _isRinging.asStateFlow()

    private val _isFlashlightOn = MutableStateFlow(false)
    val isFlashlightOn: StateFlow<Boolean> = _isFlashlightOn.asStateFlow()

    private val _isStrobeActive = MutableStateFlow(false)
    val isStrobeActive: StateFlow<Boolean> = _isStrobeActive.asStateFlow()

    @Volatile
    private var cachedMacPair: Pair<String, String>? = null

    @Volatile
    private var cachedSerialPair: Pair<String, String>? = null

    fun invalidateHardwareCache() {
        cachedMacPair = null
        cachedSerialPair = null
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_REMOTE_ALERTS,
                "远程控制通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "来自Windows 11控制端的弹窗与报警通知"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun getDeviceInfo(port: Int = 8888): DeviceInfo {
        val (mac, macSource) = detectMacAddress()
        val (serial, serialSource) = detectDeviceSerial()
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val assetTag = storageManager.getAssetTag() ?: ""

        return DeviceInfo(
            ipv4 = getIpv4Address(),
            mac = mac,
            serial = serial,
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            port = port,
            batteryLevel = getBatteryPercentage(),
            isCharging = isDeviceCharging(),
            androidId = androidId,
            serialSource = serialSource,
            macSource = macSource,
            assetTag = assetTag
        )
    }

    fun calibrateAsset(serial: String?, mac: String?, assetTag: String?) {
        if (serial != null) storageManager.saveCustomSerial(serial)
        if (mac != null) storageManager.saveCustomMac(mac)
        if (assetTag != null) storageManager.saveAssetTag(assetTag)
        invalidateHardwareCache()
    }

    fun getIpv4Address(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (host.isNotBlank() && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun getMacAddress(): String {
        return detectMacAddress().first
    }

    fun getDeviceSerial(): String {
        return detectDeviceSerial().first
    }

    fun detectMacAddress(): Pair<String, String> {
        cachedMacPair?.let { return it }

        val result = run {
            // 1. Check if Administrator calibrated / manually overrode
            val customMac = storageManager.getCustomMac()
            if (!customMac.isNullOrBlank() && isValidMac(customMac)) {
                return@run Pair(customMac, "管理员校准")
            }

            // 2. NetworkInterface hardwareAddress (standard API, works when privileged or on POS/custom hardware)
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (networkInterface in interfaces) {
                    val name = networkInterface.name.lowercase()
                    if (name.startsWith("wlan") || name.startsWith("eth") || name == "p2p0") {
                        val macBytes = networkInterface.hardwareAddress
                        if (macBytes != null && macBytes.isNotEmpty()) {
                            val macStr = macBytes.joinToString(":") { "%02X".format(it) }
                            if (isValidMac(macStr)) {
                                return@run Pair(macStr, "网络接口")
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}

            // 3. Android 10+ Privacy Sandbox restriction (02:00:00:00:00:00)
            Pair("02:00:00:00:00:00", "受系统隐私沙盒保护(Win11端将通过ARP自动获取)")
        }

        cachedMacPair = result
        return result
    }

    fun detectDeviceSerial(): Pair<String, String> {
        cachedSerialPair?.let { return it }

        val result = run {
            // 1. Admin calibrated / manual asset override
            val customSerial = storageManager.getCustomSerial()
            if (!customSerial.isNullOrBlank()) {
                return@run Pair(customSerial, "管理员校准")
            }

            // 2. Vendor System Properties via reflection only (safe, no exec, avoids SELinux denials)
            val propKeys = listOf(
                "ro.serialno",
                "ro.boot.serialno",
                "ril.serialnumber",
                "sys.serialnumber",
                "ro.hardware.chip_id",
                "gsm.sn1"
            )
            for (key in propKeys) {
                val propVal = getSystemProperty(key)
                if (!propVal.isNullOrBlank() &&
                    propVal != "unknown" &&
                    propVal != "0" &&
                    propVal.length >= 4
                ) {
                    return@run Pair(propVal, "底层硬件属性($key)")
                }
            }

            // 3. Official Build.SERIAL (safe access)
            try {
                @Suppress("DEPRECATION")
                if (!Build.SERIAL.isNullOrBlank() && Build.SERIAL != Build.UNKNOWN) {
                    return@run Pair(Build.SERIAL, "Build.SERIAL")
                }
            } catch (_: Throwable) {}

            // 4. Safe check for Build.getSerial() ONLY on Android 8 & 9 when permission is held
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P) {
                try {
                    val hasPerm = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasPerm) {
                        val serial = Build.getSerial()
                        if (!serial.isNullOrBlank() && serial != Build.UNKNOWN) {
                            return@run Pair(serial, "系统API (Build.getSerial)")
                        }
                    }
                } catch (_: Throwable) {}
            }

            // 5. Fallback: Android ID (standard, safe, no SELinux violation)
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrBlank()) {
                return@run Pair("ID-${androidId.uppercase()}", "系统设备标识码(Android ID)")
            }

            Pair("UNKNOWN", "未知")
        }

        cachedSerialPair = result
        return result
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            val value = get.invoke(null, key) as? String
            if (!value.isNullOrBlank() && value != "unknown") {
                value.trim()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun isValidMac(mac: String): Boolean {
        if (mac.isBlank()) return false
        val clean = mac.trim().uppercase()
        if (clean == "02:00:00:00:00:00" || clean == "00:00:00:00:00:00" || clean.startsWith("FE:")) {
            return false
        }
        val regex = Regex("^([0-9A-F]{2}[:-]){5}([0-9A-F]{2})$")
        return regex.matches(clean)
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                (level * 100 / scale)
            } else {
                100
            }
        } catch (_: Exception) {
            100
        }
    }

    private fun isDeviceCharging(): Boolean {
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            false
        }
    }

    // --- Flashlight Functions ---

    private fun getTorchCameraId(): String? {
        val manager = cameraManager ?: return null
        return try {
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            // Fallback: any camera with flash
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        }
    }

    fun setFlashlight(enable: Boolean): Boolean {
        stopStrobe()
        val manager = cameraManager ?: return false
        val cameraId = getTorchCameraId() ?: return false
        return try {
            manager.setTorchMode(cameraId, enable)
            _isFlashlightOn.value = enable
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun startStrobeFlashlight(durationSeconds: Int = 10) {
        stopStrobe()
        val manager = cameraManager ?: return
        val cameraId = getTorchCameraId() ?: return

        _isStrobeActive.value = true
        strobeJob = scope.launch {
            val endTime = System.currentTimeMillis() + (durationSeconds * 1000L)
            var state = false
            try {
                while (isActive && System.currentTimeMillis() < endTime) {
                    state = !state
                    try {
                        manager.setTorchMode(cameraId, state)
                        _isFlashlightOn.value = state
                    } catch (_: Exception) {
                    }
                    delay(120)
                }
            } finally {
                try {
                    manager.setTorchMode(cameraId, false)
                } catch (_: Exception) {
                }
                _isFlashlightOn.value = false
                _isStrobeActive.value = false
            }
        }
    }

    fun stopStrobe() {
        strobeJob?.cancel()
        strobeJob = null
        _isStrobeActive.value = false
    }

    // --- Ringtone / Sound Functions ---

    private var savedAlarmVolume: Int? = null
    private var savedRingVolume: Int? = null
    private var savedMusicVolume: Int? = null
    private var savedRingerMode: Int? = null

    /**
     * Set device system volume to a percentage (0..100) across ALARM, RING, and MUSIC streams.
     */
    fun setVolume(percent: Int): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val clamped = percent.coerceIn(0, 100)

            if (clamped > 0 && audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                try { audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
            }

            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val targetAlarm = Math.round(maxAlarm * (clamped / 100.0)).toInt().coerceIn(0, maxAlarm)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)

            val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            val targetRing = Math.round(maxRing * (clamped / 100.0)).toInt().coerceIn(0, maxRing)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, targetRing, 0)

            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetMusic = Math.round(maxMusic * (clamped / 100.0)).toInt().coerceIn(0, maxMusic)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)

            // Update saved volume state so subsequent stopRinging won't overwrite user's explicitly set volume
            savedAlarmVolume = targetAlarm
            savedRingVolume = targetRing
            savedMusicVolume = targetMusic
            true
        } catch (e: Exception) {
            Log.e("DeviceHardwareManager", "Failed to set volume: ${e.message}")
            false
        }
    }

    fun startRinging(durationSeconds: Int = 15) {
        stopRinging()
        try {
            // 1. Acquire WakeLock to keep CPU active during ringing even when device screen is off
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ringWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteReceiver::RingWakeLock")
            ringWakeLock?.acquire((durationSeconds + 5) * 1000L)

            // 2. Save previous volume levels before forcing 100% volume for the alarm
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let { am ->
                if (savedAlarmVolume == null) {
                    savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                    savedRingVolume = am.getStreamVolume(AudioManager.STREAM_RING)
                    savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    savedRingerMode = am.ringerMode
                }

                try {
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                } catch (_: Exception) {}

                try {
                    val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                } catch (_: Exception) {}

                try {
                    val maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING)
                    am.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)
                } catch (_: Exception) {}

                try {
                    val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                } catch (_: Exception) {}
            }

            // 3. Resolve best alarm / ringtone audio source
            var alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            // 4. Play with MediaPlayer (isLooping = true) to guarantee continuous playback without stopping early
            var playedWithMedia = false
            try {
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context, alarmUri)
                    isLooping = true
                    setVolume(1.0f, 1.0f)
                    prepare()
                    start()
                }
                mediaPlayer = player
                playedWithMedia = true
            } catch (e: Exception) {
                Log.w("DeviceHardwareManager", "MediaPlayer playback failed, fallback to Ringtone", e)
            }

            if (!playedWithMedia) {
                try {
                    val ringtone = RingtoneManager.getRingtone(context, alarmUri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ringtone.audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ringtone.isLooping = true
                    }
                    ringtone.play()
                    activeRingtone = ringtone
                } catch (e: Exception) {
                    Log.e("DeviceHardwareManager", "Ringtone fallback also failed", e)
                }
            }
            _isRinging.value = true

            // Trigger strong continuous vibration alongside ring
            vibrateDevice(pattern = longArrayOf(0, 800, 300, 800, 300, 800), repeat = 0)

            // Precise duration delay
            ringJob = scope.launch {
                delay(durationSeconds * 1000L)
                stopRinging()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _isRinging.value = false
        }
    }

    fun stopRinging() {
        ringJob?.cancel()
        ringJob = null
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null

        try {
            activeRingtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
        } catch (_: Exception) {}
        activeRingtone = null

        _isRinging.value = false
        stopVibration()

        try {
            if (ringWakeLock?.isHeld == true) {
                ringWakeLock?.release()
            }
        } catch (_: Exception) {}
        ringWakeLock = null

        // Restore original system volumes prior to ringing
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let { am ->
                savedRingerMode?.let { mode ->
                    try { am.ringerMode = mode } catch (_: Exception) {}
                }
                savedAlarmVolume?.let { vol ->
                    try { am.setStreamVolume(AudioManager.STREAM_ALARM, vol, 0) } catch (_: Exception) {}
                }
                savedRingVolume?.let { vol ->
                    try { am.setStreamVolume(AudioManager.STREAM_RING, vol, 0) } catch (_: Exception) {}
                }
                savedMusicVolume?.let { vol ->
                    try { am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        savedAlarmVolume = null
        savedRingVolume = null
        savedMusicVolume = null
        savedRingerMode = null
    }

    fun vibrateDevice(pattern: LongArray = longArrayOf(0, 400, 200, 400), repeat: Int = -1) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, repeat))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, repeat))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, repeat)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun stopVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.cancel()
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.cancel()
            }
        } catch (_: Exception) {
        }
    }

    // --- Notification Functions ---

    fun showPopupNotification(title: String, message: String, fromClient: String) {
        vibrateDevice(longArrayOf(0, 400, 200, 400, 200, 400))

        // 1. Wake screen up immediately even if phone is sleeping/screen is off
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "RemoteControl::PopupWakeLock"
            )
            wakeLock?.acquire(10000)
        } catch (_: Exception) {
        }

        // 2. Launch transparent overlay AlertActivity to show directly over any other app or lock screen
        try {
            val alertIntent = Intent(context, com.example.ui.RemoteAlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(com.example.ui.RemoteAlertActivity.EXTRA_TITLE, title)
                putExtra(com.example.ui.RemoteAlertActivity.EXTRA_MESSAGE, message)
                putExtra(com.example.ui.RemoteAlertActivity.EXTRA_FROM, fromClient)
            }
            context.startActivity(alertIntent)
        } catch (e: Exception) {
            Log.e("DeviceHardwareManager", "Failed to launch RemoteAlertActivity", e)
        }

        // 3. System high-priority heads-up notification with FullScreenIntent for system notification shade and lockscreen
        val fullScreenIntent = Intent(context, com.example.ui.RemoteAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(com.example.ui.RemoteAlertActivity.EXTRA_TITLE, title)
            putExtra(com.example.ui.RemoteAlertActivity.EXTRA_MESSAGE, message)
            putExtra(com.example.ui.RemoteAlertActivity.EXTRA_FROM, fromClient)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMOTE_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("【Win11控制端】$title")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message\n\n来源：$fromClient"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(fullScreenPendingIntent)
            .build()

        notificationManager?.notify(NOTIFICATION_ID_ALERT, notification)
    }

    fun readAsset(fileName: String): String {
        return try {
            context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        const val CHANNEL_REMOTE_ALERTS = "remote_alerts_channel"
        const val NOTIFICATION_ID_ALERT = 2001
    }
}
