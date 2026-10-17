package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DeviceInfo
import com.example.data.model.LogEntry
import com.example.data.model.PairedClient
import com.example.service.RemoteControlManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    init {
        RemoteControlManager.initialize(application)
        // Auto-start persistent background service on launch
        RemoteControlManager.startService(application)
    }

    val isRunning: StateFlow<Boolean> = RemoteControlManager.isRunning
    val pinCode: StateFlow<String> = RemoteControlManager.pinCode
    val deviceInfo: StateFlow<DeviceInfo?> = RemoteControlManager.deviceInfo
    val pairedClients: StateFlow<List<PairedClient>> = RemoteControlManager.pairedClients
    val logs: StateFlow<List<LogEntry>> = RemoteControlManager.logs
    val isRinging: StateFlow<Boolean> = RemoteControlManager.isRinging
    val isFlashlightOn: StateFlow<Boolean> = RemoteControlManager.isFlashlightOn
    val isStrobeActive: StateFlow<Boolean> = RemoteControlManager.isStrobeActive
    val isNotificationSilent: StateFlow<Boolean> = RemoteControlManager.isNotificationSilent
    val isNotificationBlocked: StateFlow<Boolean> = RemoteControlManager.isNotificationBlocked

    private val _isIgnoringBattery = MutableStateFlow(true)
    val isIgnoringBattery: StateFlow<Boolean> = _isIgnoringBattery.asStateFlow()

    private val _showWin11GuideDialog = MutableStateFlow(false)
    val showWin11GuideDialog: StateFlow<Boolean> = _showWin11GuideDialog.asStateFlow()

    private val _showCustomPinDialog = MutableStateFlow(false)
    val showCustomPinDialog: StateFlow<Boolean> = _showCustomPinDialog.asStateFlow()

    private val _showAssetCalibrationDialog = MutableStateFlow(false)
    val showAssetCalibrationDialog: StateFlow<Boolean> = _showAssetCalibrationDialog.asStateFlow()

    private val _showNotificationSettingDialog = MutableStateFlow(false)
    val showNotificationSettingDialog: StateFlow<Boolean> = _showNotificationSettingDialog.asStateFlow()

    private val _editingRemarkClient = MutableStateFlow<PairedClient?>(null)
    val editingRemarkClient: StateFlow<PairedClient?> = _editingRemarkClient.asStateFlow()

    init {
        checkBatteryOptimization(application)
        checkNotificationStatus(application)
    }

    fun checkBatteryOptimization(context: Context) {
        _isIgnoringBattery.value = RemoteControlManager.isIgnoringBatteryOptimizations(context)
    }

    fun checkNotificationStatus(context: Context) {
        RemoteControlManager.checkNotificationStatus(context)
    }

    fun setNotificationSilent(silent: Boolean, context: Context) {
        RemoteControlManager.setNotificationSilentMode(context, silent)
        val tip = if (silent) "已切换为【极简静默无图标模式】（状态栏不再常驻小图标）" else "已恢复【标准通知模式】"
        Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
    }

    fun openNotificationSettings(context: Context) {
        RemoteControlManager.openNotificationSettings(context)
    }

    fun openNotificationSettingDialog() {
        _showNotificationSettingDialog.value = true
    }

    fun closeNotificationSettingDialog() {
        _showNotificationSettingDialog.value = false
    }

    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    fun toggleService(context: Context) {
        if (isRunning.value) {
            RemoteControlManager.stopService(context)
            Toast.makeText(context, "受控服务已停止", Toast.LENGTH_SHORT).show()
        } else {
            RemoteControlManager.startService(context)
            Toast.makeText(context, "受控服务已启动", Toast.LENGTH_SHORT).show()
        }
    }

    fun regeneratePin(context: Context) {
        val newPin = RemoteControlManager.regeneratePin()
        Toast.makeText(context, "已生成新PIN码: $newPin", Toast.LENGTH_SHORT).show()
    }

    fun setCustomPin(pin: String, context: Context) {
        if (pin.length in 4..10) {
            RemoteControlManager.updatePin(pin)
            _showCustomPinDialog.value = false
            Toast.makeText(context, "数字密码已更新", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "密码长度应在4到10位之间", Toast.LENGTH_SHORT).show()
        }
    }

    fun openCustomPinDialog() {
        _showCustomPinDialog.value = true
    }

    fun closeCustomPinDialog() {
        _showCustomPinDialog.value = false
    }

    fun openWin11Guide() {
        _showWin11GuideDialog.value = true
    }

    fun closeWin11Guide() {
        _showWin11GuideDialog.value = false
    }

    fun refreshNetworkInfo() {
        RemoteControlManager.refreshDeviceInfo()
    }

    fun openAssetCalibrationDialog() {
        _showAssetCalibrationDialog.value = true
    }

    fun closeAssetCalibrationDialog() {
        _showAssetCalibrationDialog.value = false
    }

    fun calibrateAsset(serial: String, mac: String, assetTag: String, context: Context) {
        val s = if (serial.isNotBlank()) serial else null
        val m = if (mac.isNotBlank()) mac else null
        val a = if (assetTag.isNotBlank()) assetTag else null
        RemoteControlManager.hardwareManager.calibrateAsset(s, m, a)
        RemoteControlManager.refreshDeviceInfo()
        _showAssetCalibrationDialog.value = false
        Toast.makeText(context, "硬件资产参数已保存并持久化！", Toast.LENGTH_SHORT).show()
    }

    fun unbindClient(token: String, context: Context) {
        RemoteControlManager.unbindClient(token)
        Toast.makeText(context, "已解除该控制端绑定", Toast.LENGTH_SHORT).show()
    }

    fun openEditRemarkDialog(client: PairedClient) {
        _editingRemarkClient.value = client
    }

    fun closeEditRemarkDialog() {
        _editingRemarkClient.value = null
    }

    fun saveClientRemark(token: String, remark: String) {
        RemoteControlManager.updateClientRemark(token, remark)
        _editingRemarkClient.value = null
    }

    // Hardware quick test actions
    fun testRing() {
        RemoteControlManager.triggerLocalRingTest(durationSeconds = 10)
    }

    fun stopRing() {
        RemoteControlManager.stopRing()
    }

    fun toggleFlashlight() {
        RemoteControlManager.toggleFlashlight()
    }

    fun testStrobe() {
        RemoteControlManager.strobeFlashlight(durationSeconds = 5)
    }

    fun testPopup() {
        RemoteControlManager.testPopupNotification()
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "已复制 $label 到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
