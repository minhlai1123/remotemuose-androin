package com.remotemouse.protocol

/**
 * CRC-16/CCITT-FALSE (also known as CRC-16/IBM-3740)
 * Polynomial: 0x1021 (x^16 + x^12 + x^5 + 1)
 * Initial Value: 0xFFFF, XOR Out: 0x0000
 * Standard Check Vector "123456789" -> 0x29B1
 */
object Crc16CcittFalse {
    private val TABLE = IntArray(256) { i ->
        var curr = i shl 8
        repeat(8) {
            curr = if ((curr and 0x8000) != 0) {
                ((curr shl 1) xor 0x1021) and 0xFFFF
            } else {
                (curr shl 1) and 0xFFFF
            }
        }
        curr
    }

    fun calculate(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in offset until (offset + length)) {
            val byte = data[i].toInt() and 0xFF
            crc = (((crc shl 8) and 0xFF00) xor TABLE[((crc ushr 8) xor byte) and 0xFF]) and 0xFFFF
        }
        return crc
    }

    fun verify(data: ByteArray, offset: Int = 0, length: Int = data.size, expected: Int): Boolean {
        return calculate(data, offset, length) == (expected and 0xFFFF)
    }
}
