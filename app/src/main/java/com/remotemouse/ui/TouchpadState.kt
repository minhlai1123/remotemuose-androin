package com.remotemouse.ui

data class TouchpadState(
    val isConnected: Boolean = false,
    val statusText: String = "Disconnected",
    val hostIp: String = "192.168.1.100",
    val pin: String = "123456"
)
