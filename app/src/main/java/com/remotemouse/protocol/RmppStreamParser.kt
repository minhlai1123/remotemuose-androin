package com.remotemouse.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RmppStreamParser(private val maxBufferSize: Int = 262144) {
    private val buffer = ByteArrayOutputStream()

    fun feed(chunk: ByteArray): List<RmppFrame> {
        if (buffer.size() + chunk.size > maxBufferSize) {
            buffer.reset()
            return emptyList()
        }

        buffer.write(chunk)
        val bytes = buffer.toByteArray()
        val frames = mutableListOf<RmppFrame>()
        var offset = 0

        while (bytes.size - offset >= 4) {
            // Scan for Magic 0x524D ('R', 'M')
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            if (b0 != 0x52 || b1 != 0x4D) {
                offset++
                continue
            }

            val ver = bytes[offset + 2]
            if (ver != RmppConstants.PROTOCOL_VERSION) {
                offset += 2
                continue
            }

            val type = bytes[offset + 3]

            if (RmppConstants.COMPACT_MSG_TYPES.contains(type)) {
                if (bytes.size - offset < RmppConstants.COMPACT_FRAME_SIZE) {
                    break
                }
                val rawFrame = bytes.copyOfRange(offset, offset + RmppConstants.COMPACT_FRAME_SIZE)
                try {
                    frames.add(CompactFrame.deserialize(rawFrame))
                    offset += RmppConstants.COMPACT_FRAME_SIZE
                } catch (e: Exception) {
                    offset += 2
                }
            } else if (RmppConstants.EXTENDED_MSG_TYPES.contains(type)) {
                if (bytes.size - offset < RmppConstants.EXTENDED_HEADER_SIZE) {
                    break
                }
                val headerBuf = ByteBuffer.wrap(bytes, offset, RmppConstants.EXTENDED_HEADER_SIZE).apply {
                    order(ByteOrder.BIG_ENDIAN)
                }
                headerBuf.position(8)
                val payloadLen = headerBuf.short.toInt() and 0xFFFF
                if (payloadLen > RmppConstants.MAX_PAYLOAD_SIZE) {
                    offset += 2
                    continue
                }
                val totalFrameLen = RmppConstants.EXTENDED_HEADER_SIZE + payloadLen + 2

                if (bytes.size - offset < totalFrameLen) {
                    break
                }
                val rawFrame = bytes.copyOfRange(offset, offset + totalFrameLen)
                try {
                    frames.add(ExtendedFrame.deserialize(rawFrame))
                    offset += totalFrameLen
                } catch (e: Exception) {
                    offset += 2
                }
            } else {
                // Unknown message type: advance 2 bytes past magic to resynchronize
                offset += 2
            }
        }

        buffer.reset()
        if (offset < bytes.size) {
            buffer.write(bytes, offset, bytes.size - offset)
        }

        return frames
    }

    fun reset() {
        buffer.reset()
    }
}
