package com.example.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AssetCalibrationDialog
import com.example.ui.components.BackgroundKeepAliveCard
import com.example.ui.components.CommandLogCard
import com.example.ui.components.CustomPinDialog
import com.example.ui.components.DeviceInfoCard
import com.example.ui.components.EditRemarkDialog
import com.example.ui.components.HardwareControlCard
import com.example.ui.components.HeroStatusCard
import com.example.ui.components.NotificationSettingCard
import com.example.ui.components.PairedClientsCard
import com.example.ui.components.Win11GuideDialog
import com.example.ui.theme.CyberBg
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current

    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val pinCode by viewModel.pinCode.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val pairedClients by viewModel.pairedClients.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val isRinging by viewModel.isRinging.collectAsStateWithLifecycle()
    val isFlashlightOn by viewModel.isFlashlightOn.collectAsStateWithLifecycle()
    val isStrobeActive by viewModel.isStrobeActive.collectAsStateWithLifecycle()
    val isIgnoringBattery by viewModel.isIgnoringBattery.collectAsStateWithLifecycle()
    val isNotificationSilent by viewModel.isNotificationSilent.collectAsStateWithLifecycle()
    val isNotificationBlocked by viewModel.isNotificationBlocked.collectAsStateWithLifecycle()

    val showGuide by viewModel.showWin11GuideDialog.collectAsStateWithLifecycle()
    val showCustomPin by viewModel.showCustomPinDialog.collectAsStateWithLifecycle()
    val showAssetCalibration by viewModel.showAssetCalibrationDialog.collectAsStateWithLifecycle()
    val editingRemarkClient by viewModel.editingRemarkClient.collectAsStateWithLifecycle()

    // Permission launcher for Camera (Torch) & Notifications
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.checkNotificationStatus(context)
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(permissions.toTypedArray())
        viewModel.checkNotificationStatus(context)

        // Auto start service on first launch so it's ready out-of-the-box
        if (!isRunning) {
            viewModel.toggleService(context)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg),
        containerColor = CyberBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyanPrimary.copy(alpha = 0.15f))
                                .border(1.dp, CyanPrimary, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SettingsRemote,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "安卓受控端",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "Win11 远程控制连接服务",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.openWin11Guide() },
                        modifier = Modifier.testTag("topbar_guide_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = "Win11控制指南",
                            tint = CyanPrimary
                        )
                    }

                    IconButton(
                        onClick = { viewModel.refreshNetworkInfo() },
                        modifier = Modifier.testTag("topbar_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新网络",
                            tint = TextMuted
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CyberBg,
                    titleContentColor = TextWhite
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Hero Status & PIN Card
            HeroStatusCard(
                isRunning = isRunning,
                pinCode = pinCode,
                ipv4 = deviceInfo?.ipv4 ?: "127.0.0.1",
                port = deviceInfo?.port ?: 8888,
                onToggleService = { viewModel.toggleService(context) },
                onRegeneratePin = { viewModel.regeneratePin(context) },
                onOpenCustomPinDialog = { viewModel.openCustomPinDialog() },
                onOpenWin11Guide = { viewModel.openWin11Guide() },
                onCopyAddress = {
                    viewModel.copyToClipboard(
                        context,
                        "受控端地址",
                        "http://${deviceInfo?.ipv4 ?: "127.0.0.1"}:${deviceInfo?.port ?: 8888}"
                    )
                },
                onCopyPin = { pin ->
                    viewModel.copyToClipboard(context, "PIN 密码", pin)
                }
            )

            // Background Keep-Alive & Anti-Sleep Guarantee Card
            BackgroundKeepAliveCard(
                isIgnoringBattery = isIgnoringBattery,
                onRequestIgnoreBattery = { viewModel.requestIgnoreBatteryOptimization(context) }
            )

            // Notification Bar Persistent Display Management Card
            NotificationSettingCard(
                isNotificationSilent = isNotificationSilent,
                isNotificationBlocked = isNotificationBlocked,
                onToggleSilent = { viewModel.setNotificationSilent(it, context) },
                onOpenNotificationSettings = { viewModel.openNotificationSettings(context) }
            )

            // 2. Hardware Device Info Card (IPv4, MAC, Serial)
            DeviceInfoCard(
                deviceInfo = deviceInfo,
                onRefresh = { viewModel.refreshNetworkInfo() },
                onCalibrate = { viewModel.openAssetCalibrationDialog() },
                onCopy = { label, value ->
                    viewModel.copyToClipboard(context, label, value)
                }
            )

            // 3. Hardware Control & Local Quick Test Card
            HardwareControlCard(
                isRinging = isRinging,
                isFlashlightOn = isFlashlightOn,
                isStrobeActive = isStrobeActive,
                onTestRing = { viewModel.testRing() },
                onStopRing = { viewModel.stopRing() },
                onToggleFlashlight = { viewModel.toggleFlashlight() },
                onTestStrobe = { viewModel.testStrobe() },
                onTestPopup = { viewModel.testPopup() }
            )

            // 4. Paired Win11 Clients Card (Online / Offline status & Remarks)
            PairedClientsCard(
                pairedClients = pairedClients,
                onUnbindClient = { token -> viewModel.unbindClient(token, context) },
                onEditRemark = { client -> viewModel.openEditRemarkDialog(client) }
            )

            // 5. Activity Logs Card
            CommandLogCard(logs = logs)

            Spacer(modifier = Modifier.height(20.dp))
        }

        // Dialogs
        if (showGuide) {
            Win11GuideDialog(
                ipv4 = deviceInfo?.ipv4 ?: "127.0.0.1",
                port = deviceInfo?.port ?: 8888,
                pinCode = pinCode,
                onDismiss = { viewModel.closeWin11Guide() },
                onCopy = { label, text -> viewModel.copyToClipboard(context, label, text) }
            )
        }

        if (showCustomPin) {
            CustomPinDialog(
                currentPin = pinCode,
                onDismiss = { viewModel.closeCustomPinDialog() },
                onConfirm = { newPin -> viewModel.setCustomPin(newPin, context) }
            )
        }

        if (showAssetCalibration) {
            AssetCalibrationDialog(
                deviceInfo = deviceInfo,
                onDismiss = { viewModel.closeAssetCalibrationDialog() },
                onConfirm = { serial, mac, assetTag ->
                    viewModel.calibrateAsset(serial, mac, assetTag, context)
                }
            )
        }

        editingRemarkClient?.let { client ->
            EditRemarkDialog(
                clientName = client.name,
                currentRemark = client.remark,
                onDismiss = { viewModel.closeEditRemarkDialog() },
                onConfirm = { remark -> viewModel.saveClientRemark(client.token, remark) }
            )
        }
    }
}
