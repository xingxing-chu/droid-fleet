package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DeviceInfo
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberSurfaceVariant
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite

@Composable
fun AssetCalibrationDialog(
    deviceInfo: DeviceInfo?,
    onDismiss: () -> Unit,
    onConfirm: (serial: String, mac: String, assetTag: String) -> Unit
) {
    var serialText by remember { mutableStateOf(deviceInfo?.serial ?: "") }
    var macText by remember { mutableStateOf(deviceInfo?.mac ?: "") }
    var assetTagText by remember { mutableStateOf(deviceInfo?.assetTag ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberCard,
        title = {
            Text(
                text = "🏷️ 硬件资产参数校准与录入",
                color = TextWhite,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "专为硬件管理者设计：在此校准设备真实出厂SN、网卡MAC及企业资产编号，保存后将永久持久化并同步给Win11控制端。",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Info banner regarding Android privacy
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F172A))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = "Android 10+ 限制普通应用直采硬件MAC。Win11控制端支持在局域网内通过ARP协议物理层自动获取真实网卡MAC！",
                        fontSize = 10.sp,
                        color = TextMuted,
                        lineHeight = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Serial Number Field
                OutlinedTextField(
                    value = serialText,
                    onValueChange = { serialText = it },
                    label = { Text("硬件序列号 (Serial / SN)") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = CyberSurfaceVariant,
                        unfocusedContainerColor = CyberSurfaceVariant,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedIndicatorColor = ElectricAmber,
                        unfocusedIndicatorColor = CyberBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // MAC Address Field
                OutlinedTextField(
                    value = macText,
                    onValueChange = { macText = it.uppercase() },
                    label = { Text("物理网卡地址 (MAC)") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = CyberSurfaceVariant,
                        unfocusedContainerColor = CyberSurfaceVariant,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedIndicatorColor = CyanPrimary,
                        unfocusedIndicatorColor = CyberBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Asset Tag Field
                OutlinedTextField(
                    value = assetTagText,
                    onValueChange = { assetTagText = it },
                    label = { Text("企业资产编号 (Asset Tag，如 IT-PAD-001)") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = CyberSurfaceVariant,
                        unfocusedContainerColor = CyberSurfaceVariant,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedIndicatorColor = CyanPrimary,
                        unfocusedIndicatorColor = CyberBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (!deviceInfo?.androidId.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "系统设备码 (Android ID): ${deviceInfo?.androidId}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(serialText.trim(), macText.trim(), assetTagText.trim())
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(
                    text = "💾 保存校准",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
            ) {
                Text("取消")
            }
        }
    )
}
