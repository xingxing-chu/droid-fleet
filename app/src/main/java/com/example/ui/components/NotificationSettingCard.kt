package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite

@Composable
fun NotificationSettingCard(
    isNotificationSilent: Boolean,
    isNotificationBlocked: Boolean,
    onToggleSilent: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showHelpDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CyberCard)
            .border(
                1.dp,
                if (isNotificationBlocked) StatusGreen.copy(alpha = 0.5f) else CyberBorder,
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
            .testTag("notification_setting_card")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (isNotificationBlocked) StatusGreen.copy(alpha = 0.15f)
                            else CyanPrimary.copy(alpha = 0.15f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isNotificationBlocked) Icons.Default.NotificationsOff
                        else if (isNotificationSilent) Icons.Default.VisibilityOff
                        else Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = if (isNotificationBlocked) StatusGreen else CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "通知栏常驻显示管理",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = if (isNotificationBlocked) "当前状态：已彻底隐藏（状态栏无常驻图标）"
                        else if (isNotificationSilent) "当前状态：极简静默（状态栏不显示图标）"
                        else "当前状态：标准常驻（显示连接IP与PIN码）",
                        fontSize = 11.sp,
                        color = if (isNotificationBlocked) StatusGreen else TextMuted
                    )
                }

                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "查看说明",
                    tint = TextMuted,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { showHelpDialog = true }
                )
            }

            HorizontalDivider(color = CyberBorder, thickness = 0.8.dp)

            if (isNotificationBlocked) {
                // When completely disabled at system level
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(StatusGreen.copy(alpha = 0.1f))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = StatusGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "常驻通知已在系统设置中关闭。后台服务依然 100% 正常运行并随时响应 Win11 控制指令。",
                            fontSize = 11.sp,
                            color = Color(0xFFA5D6A7),
                            lineHeight = 15.sp
                        )
                    }
                }

                OutlinedButton(
                    onClick = onOpenNotificationSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("btn_manage_system_notification"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(CyanPrimary.copy(alpha = 0.5f))),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("前往系统通知设置 (随时可恢复显示)", fontSize = 12.sp)
                }
            } else {
                // Option 1: In-app Silent / Min Importance switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "极简静默模式 (隐藏状态栏小图标)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                        Text(
                            text = "开启后状态栏不再占用常驻小图标，通知自动收拢在下拉底部静音区",
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )
                    }

                    Switch(
                        checked = isNotificationSilent,
                        onCheckedChange = { onToggleSilent(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyanPrimary,
                            checkedTrackColor = CyanPrimary.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = Color(0xFF1B284C)
                        ),
                        modifier = Modifier.testTag("switch_silent_notification")
                    )
                }

                // Option 2: Completely hide persistent notification via system settings
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1B284C))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "想要彻底隐藏通知栏常驻？",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = "根据 Android 系统安全规范，前台保活服务默认需发送通知。您可以直接在系统设置中关闭该通知开关：关闭后后台受控服务依然会全天候持续稳定运行，且手机顶部和下拉栏将 100% 没有任何常驻显示！",
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 15.sp
                        )

                        Button(
                            onClick = onOpenNotificationSettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .testTag("btn_close_system_notification"),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "前往系统设置彻底关闭常驻通知",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }
            }
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(
                    text = "关于通知栏常驻说明",
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "1. 为什么默认常驻通知？\nAndroid 8.0+ 为防止系统在熄屏、锁屏或切换后台时杀死网络监听服务（导致电脑无法连接），要求后台长连接服务必须使用“前台服务”。前台服务按原生规范必须关联通知。",
                        fontSize = 12.sp,
                        color = Color(0xFFCFD8DC),
                        lineHeight = 17.sp
                    )
                    Text(
                        text = "2. 取消通知栏显示会影响功能吗？\n完全不会！Android 系统设计规范明确允许用户关闭前台服务的通知。关闭后，后台 CPU 唤醒锁、低延迟 Wi-Fi 锁和 HTTP 指令监听依然 100% 正常运行，不受任何影响。",
                        fontSize = 12.sp,
                        color = Color(0xFFCFD8DC),
                        lineHeight = 17.sp
                    )
                    Text(
                        text = "3. 推荐两种隐藏方式：\n• 方式一：开启上面的【极简静默模式】，状态栏小图标立即隐藏。\n• 方式二：点击【前往系统设置彻底关闭常驻通知】，关闭通知后达到 100% 零常驻显示。",
                        fontSize = 12.sp,
                        color = CyanPrimary,
                        lineHeight = 17.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showHelpDialog = false
                        onOpenNotificationSettings()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("前往设置", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("我知道了", color = TextMuted)
                }
            },
            containerColor = Color(0xFF141E38),
            shape = RoundedCornerShape(16.dp)
        )
    }
}
