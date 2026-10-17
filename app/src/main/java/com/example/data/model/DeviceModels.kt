package com.example.data.model

data class DeviceInfo(
    val ipv4: String,
    val mac: String,
    val serial: String,
    val model: String,
    val androidVersion: String,
    val port: Int = 8888,
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val androidId: String = "",
    val serialSource: String = "自动探测",
    val macSource: String = "自动探测",
    val assetTag: String = ""
)

data class PairedClient(
    val token: String,
    val name: String,
    val ip: String,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
    val remark: String = "",
    val clientSecret: String = ""
)

data class LogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val action: String,
    val details: String,
    val isSuccess: Boolean = true
)

data class PopupNotificationEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val message: String,
    val fromClient: String,
    val timestamp: Long = System.currentTimeMillis()
)
