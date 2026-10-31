package com.remotemouse.protocol

object RmppConstants {
    const val MAGIC: Short = 0x524D.toShort() // 'R', 'M'
    const val PROTOCOL_VERSION: Byte = 0x01

    // Compact Frame Message Types (16 bytes fixed)
    const val MSG_MOUSE_MOVE: Byte = 0x01
    const val MSG_MOUSE_DOWN: Byte = 0x02
    const val MSG_MOUSE_UP: Byte = 0x03
    const val MSG_MOUSE_CLICK: Byte = 0x04
    const val MSG_MOUSE_SCROLL: Byte = 0x05
    const val MSG_KEY_DOWN: Byte = 0x10
    const val MSG_KEY_UP: Byte = 0x11
    const val MSG_MEDIA_CONTROL: Byte = 0x20
    const val MSG_SYSTEM_POWER: Byte = 0x30
    const val MSG_HEARTBEAT_PING: Byte = 0x90.toByte()
    const val MSG_HEARTBEAT_PONG: Byte = 0x91.toByte()
    const val MSG_SAFETY_RELEASE_ALL: Byte = 0xFF.toByte()

    // Extended Frame Message Types (Variable length)
    const val MSG_TEXT_INPUT: Byte = 0x12
    const val MSG_AUTH_REQUEST: Byte = 0x80.toByte()
    const val MSG_AUTH_RESPONSE: Byte = 0x81.toByte()
    const val MSG_CLIPBOARD_DATA: Byte = 0x82.toByte()
    const val MSG_AUTH_RESUME: Byte = 0x83.toByte()
    const val MSG_SESSION_ERROR: Byte = 0xFE.toByte()

    const val COMPACT_FRAME_SIZE = 16
    const val EXTENDED_HEADER_SIZE = 12
    const val DEFAULT_PORT = 54321
    const val MAX_PAYLOAD_SIZE = 65535
    const val MAX_ACCUMULATOR_BUFFER_SIZE = 262144

    val COMPACT_MSG_TYPES: Set<Byte> = setOf(
        MSG_MOUSE_MOVE,
        MSG_MOUSE_DOWN,
        MSG_MOUSE_UP,
        MSG_MOUSE_CLICK,
        MSG_MOUSE_SCROLL,
        MSG_KEY_DOWN,
        MSG_KEY_UP,
        MSG_MEDIA_CONTROL,
        MSG_SYSTEM_POWER,
        MSG_HEARTBEAT_PING,
        MSG_HEARTBEAT_PONG,
        MSG_SAFETY_RELEASE_ALL
    )

    val EXTENDED_MSG_TYPES: Set<Byte> = setOf(
        MSG_TEXT_INPUT,
        MSG_AUTH_REQUEST,
        MSG_AUTH_RESPONSE,
        MSG_CLIPBOARD_DATA,
        MSG_AUTH_RESUME,
        MSG_SESSION_ERROR
    )
}
