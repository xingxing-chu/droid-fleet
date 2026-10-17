package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusGreenBg
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StatusRedBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite

@Composable
fun HeroStatusCard(
    isRunning: Boolean,
    pinCode: String,
    ipv4: String,
    port: Int,
    onToggleService: () -> Unit,
    onRegeneratePin: () -> Unit,
    onOpenCustomPinDialog: () -> Unit,
    onOpenWin11Guide: () -> Unit,
    onCopyAddress: () -> Unit,
    onCopyPin: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        CyberCard,
                        Color(0xFF131F3A)
                    )
                )
            )
            .border(1.dp, if (isRunning) CyanPrimary.copy(alpha = 0.4f) else CyberBorder, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Column {
            // Header Row: Status & Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .scale(if (isRunning) pulseScale else 1f)
                            .clip(CircleShape)
                            .background(if (isRunning) StatusGreen else StatusRed)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isRunning) "受控服务运行中 (后台常驻)" else "受控服务已停止",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning) StatusGreen else StatusRed
                        )
                        Text(
                            text = if (isRunning) "支持后台/锁屏秒级响应，无需常开App" else "点击右侧开关启动受控服务",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                }

                Switch(
                    checked = isRunning,
                    onCheckedChange = { onToggleService() },
                    modifier = Modifier.testTag("service_toggle_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyberCard,
                        checkedTrackColor = CyanPrimary,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = CyberBorder
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // IP & Port Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C1427))
                    .border(1.dp, CyberBorder, RoundedCornerShape(12.dp))
                    .clickable { onCopyAddress() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "局域网地址",
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "连接地址: ",
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                    Text(
                        text = "http://$ipv4:$port",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = CyanPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "点击复制",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制地址",
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Fully Screen-Adaptive PIN Display Card (never truncated or clipped)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F1B33))
                    .border(1.dp, ElectricViolet.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clickable { onCopyPin?.invoke(pinCode) }
                    .padding(14.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔐 绑定数字密码 (PIN)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onRegeneratePin,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("regenerate_pin_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "随机生成新PIN",
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = onOpenCustomPinDialog,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("custom_pin_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "自定义PIN",
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Win11 控制端发起绑定前，必须在控制端输入此密码验证通过才能获得控制权",
                        fontSize = 11.sp,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Dynamically scaled digits display fitting every screen width and PIN length perfectly
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val chars = pinCode.toCharArray()
                        val totalChars = chars.size.coerceAtLeast(1)
                        val spacingVal = if (totalChars > 6) 3 else 6
                        val spacingDp = spacingVal.dp
                        val totalSpacing = spacingDp * (totalChars - 1)
                        val availableWidth = (maxWidth - totalSpacing).coerceAtLeast(0.dp)
                        val rawWidth = availableWidth / totalChars
                        val boxWidth = rawWidth.coerceIn(20.dp, 44.dp)
                        val boxHeight = (boxWidth * 1.25f).coerceIn(30.dp, 52.dp)
                        val fontSize = (boxWidth.value * 0.52f).coerceIn(12f, 22f).sp

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacingDp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (c in chars) {
                                Box(
                                    modifier = Modifier
                                        .width(boxWidth)
                                        .height(boxHeight)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1B284C))
                                        .border(1.dp, CyanPrimary.copy(alpha = 0.7f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = c.toString(),
                                        fontSize = fontSize,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = CyanPrimary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Guarantee clear visibility and instant 1-tap copy
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF13203C))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PIN: ",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                        Text(
                            text = pinCode,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = CyanPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(轻触整栏快捷复制)",
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Win11 Controller Guide Button
            Button(
                onClick = onOpenWin11Guide,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_win11_guide_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricViolet
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Computer,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "💻 Windows 11 控制端程序与连接指南",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextWhite
                )
            }
        }
    }
}
