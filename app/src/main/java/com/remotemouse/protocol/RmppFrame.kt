package com.remotemouse.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class RmppFrame(val msgType: Byte, val seq: Long)

data class CompactFrame(
    val type: Byte,
    val sequenceNumber: Long,
    val data1: Short = 0,
    val data2: Short = 0,
    val flags: Int = 0,
    val checksum: Int? = null
) : RmppFrame(type, sequenceNumber) {

    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(RmppConstants.COMPACT_FRAME_SIZE).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(RmppConstants.MAGIC)
            put(RmppConstants.PROTOCOL_VERSION)
            put(type)
            putInt(sequenceNumber.toInt())
            putShort(data1)
            putShort(data2)
            putShort(flags.toShort())
        }
        val headerBytes = buffer.array()
        val csum = Crc16CcittFalse.calculate(headerBytes, 0, 14)
        buffer.putShort(csum.toShort())
        return buffer.array()
    }

    companion object {
        fun deserialize(raw: ByteArray): CompactFrame {
            require(raw.size == RmppConstants.COMPACT_FRAME_SIZE) {
                "CompactFrame size must be 16 bytes, was ${raw.size}"
            }
            val buffer = ByteBuffer.wrap(raw).apply { order(ByteOrder.BIG_ENDIAN) }
            val magic = buffer.short
            require(magic == RmppConstants.MAGIC) { "Invalid magic: 0x%04X".format(magic) }
            val ver = buffer.get()
            require(ver == RmppConstants.PROTOCOL_VERSION) { "Unsupported version: $ver" }
            val type = buffer.get()
            val seq = buffer.int.toLong() and 0xFFFFFFFFL
            val data1 = buffer.short
            val data2 = buffer.short
            val flags = buffer.short.toInt() and 0xFFFF
            val csum = buffer.short.toInt() and 0xFFFF

            val computed = Crc16CcittFalse.calculate(raw, 0, 14)
            require(computed == csum) { "CRC mismatch: computed 0x%04X, got 0x%04X".format(computed, csum) }

            return CompactFrame(type, seq, data1, data2, flags, csum)
        }
    }
}

data class ExtendedFrame(
    val type: Byte,
    val sequenceNumber: Long,
    val payload: ByteArray,
    val flags: Int = 0,
    val checksum: Int? = null
) : RmppFrame(type, sequenceNumber) {

    fun serialize(): ByteArray {
        val totalSize = RmppConstants.EXTENDED_HEADER_SIZE + payload.size + 2
        val buffer = ByteBuffer.allocate(totalSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(RmppConstants.MAGIC)
            put(RmppConstants.PROTOCOL_VERSION)
            put(type)
            putInt(sequenceNumber.toInt())
            putShort(payload.size.toShort())
            putShort(flags.toShort())
            put(payload)
        }
        val bytes = buffer.array()
        val csum = Crc16CcittFalse.calculate(bytes, 0, bytes.size - 2)
        buffer.putShort(csum.toShort())
        return buffer.array()
    }

    companion object {
        fun deserialize(raw: ByteArray): ExtendedFrame {
            require(raw.size >= RmppConstants.EXTENDED_HEADER_SIZE + 2) { "ExtendedFrame too short: ${raw.size}" }
            val buffer = ByteBuffer.wrap(raw).apply { order(ByteOrder.BIG_ENDIAN) }
            val magic = buffer.short
            require(magic == RmppConstants.MAGIC) { "Invalid magic" }
            val ver = buffer.get()
            require(ver == RmppConstants.PROTOCOL_VERSION) { "Unsupported version" }
            val type = buffer.get()
            val seq = buffer.int.toLong() and 0xFFFFFFFFL
            val payloadLen = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF

            require(raw.size == RmppConstants.EXTENDED_HEADER_SIZE + payloadLen + 2) {
                "Length mismatch: expected ${RmppConstants.EXTENDED_HEADER_SIZE + payloadLen + 2}, got ${raw.size}"
            }
            val payload = ByteArray(payloadLen)
            buffer.get(payload)
            val csum = buffer.short.toInt() and 0xFFFF

            val computed = Crc16CcittFalse.calculate(raw, 0, raw.size - 2)
            require(computed == csum) { "CRC mismatch" }

            return ExtendedFrame(type, seq, payload, flags, csum)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ExtendedFrame
        return type == other.type && sequenceNumber == other.sequenceNumber && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.toInt()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
