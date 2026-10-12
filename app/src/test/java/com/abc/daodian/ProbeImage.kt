package com.abc.daodian

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** [LiveGatewayTest] 带图那一条用的图：现画，不放二进制文件进仓库 */
internal object ProbeImage {

    /**
     * 白底黑字的一张 PNG：5×7 的点阵数字放大。手写编码，不靠 java.awt —— 安卓单测的编译类路径里没有它
     */
    fun digits(digits: String, scale: Int = 24): ByteArray {
        val glyphs = mapOf(
            '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
            '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
            '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
            '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
            '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
            '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
            '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
            '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
            '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
            '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100")
        )
        val margin = 2
        val w = (digits.length * 6 - 1 + margin * 2) * scale
        val h = (7 + margin * 2) * scale
        // 8 位灰度，每行开头一个过滤字节 0
        val raw = ByteArray((w + 1) * h)
        for (y in 0 until h) {
            val row = y / scale - margin
            for (x in 0 until w) {
                val col = x / scale - margin
                val ink = row in 0..6 && col >= 0 && col / 6 < digits.length && col % 6 < 5 &&
                    glyphs.getValue(digits[col / 6])[row][col % 6] == '1'
                raw[y * (w + 1) + 1 + x] = if (ink) 0 else 0xFF.toByte()
            }
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(name); update(data) }.value.toInt()
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            out.write(name)
            out.write(data)
            out.write(ByteBuffer.allocate(4).putInt(crc).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(0).put(0).put(0).put(0).array())
        chunk("IDAT", ByteArrayOutputStream().also { z -> DeflaterOutputStream(z).use { it.write(raw) } }.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
