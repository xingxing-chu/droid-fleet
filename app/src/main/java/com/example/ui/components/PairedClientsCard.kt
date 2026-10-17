package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PairedClient
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PairedClientsCard(
    pairedClients: List<PairedClient>,
    onUnbindClient: (token: String) -> Unit,
    onEditRemark: (client: PairedClient) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CyberCard)
            .border(1.dp, CyberBorder, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "💻 已绑定的控制端设备 (${pairedClients.size})",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                }
            }

            Text(
                text = "持久化信任库：双方已建立永久验证密钥。手机重启、清理后台或PIN码变更均不会断开绑定（免除日后重复配对），仅在手动解绑或控制端移除时解除。",
                fontSize = 11.sp,
                color = TextMuted,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            if (pairedClients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0C1427))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "暂无已绑定的 Win11 控制端",
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                        Text(
                            text = "请在电脑端运行控制程序并输入本机 PIN 码绑定",
                            fontSize = 11.sp,
                            color = TextMuted.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val now = System.currentTimeMillis()
                    for (client in pairedClients) {
                        // If last active within 15 seconds, consider online
                        val isOnline = (now - client.lastActiveAt) < 15000L

                        PairedClientItem(
                            client = client,
                            isOnline = isOnline,
                            onUnbind = { onUnbindClient(client.token) },
                            onEditRemark = { onEditRemark(client) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PairedClientItem(
    client: PairedClient,
    isOnline: Boolean,
    onUnbind: () -> Unit,
    onEditRemark: () -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F172A))
            .border(1.dp, if (isOnline) CyanPrimary.copy(alpha = 0.3f) else CyberBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) StatusGreen else StatusRed)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = client.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isOnline) StatusGreen.copy(alpha = 0.15f) else StatusRed.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isOnline) "在线" else "离线",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isOnline) StatusGreen else StatusRed
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onEditRemark,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("edit_remark_${client.token}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "修改备注",
                            tint = CyanPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onUnbind,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("unbind_${client.token}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.LinkOff,
                            contentDescription = "解除绑定",
                            tint = StatusRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "来源 IP: ${client.ip} | 最近活动: ${timeFormat.format(Date(client.lastActiveAt))}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted
            )

            if (client.remark.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "备注: ",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    Text(
                        text = client.remark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = CyanPrimary
                    )
                }
            }
        }
    }
}
