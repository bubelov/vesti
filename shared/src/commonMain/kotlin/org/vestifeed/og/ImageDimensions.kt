package org.vestifeed.og

/**
 * Reads the pixel dimensions of an encoded image from its header.
 *
 * A small, dependency-free parser for the formats feeds actually serve
 * (PNG, JPEG, GIF, WebP, BMP). It exists so the shared OpenGraph fetcher does
 * not need a platform image decoder: the same code runs on Android, the JVM and
 * wasm.
 *
 * Returns width to height, or null when the format is unknown or truncated.
 */
fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
    return png(bytes)
        ?: gif(bytes)
        ?: bmp(bytes)
        ?: jpeg(bytes)
        ?: webp(bytes)
}

private fun ByteArray.u8(index: Int): Int =
    if (index < 0 || index >= size) -1 else this[index].toInt() and 0xFF

private fun ByteArray.be16(index: Int): Int {
    val a = u8(index)
    val b = u8(index + 1)
    if (a < 0 || b < 0) return -1
    return (a shl 8) or b
}

private fun ByteArray.be32(index: Int): Long {
    val a = u8(index).toLong()
    val b = u8(index + 1).toLong()
    val c = u8(index + 2).toLong()
    val d = u8(index + 3).toLong()
    if (a < 0 || b < 0 || c < 0 || d < 0) return -1
    return (a shl 24) or (b shl 16) or (c shl 8) or d
}

private fun startsWith(bytes: ByteArray, offset: Int, ascii: String): Boolean {
    if (offset + ascii.length > bytes.size) return false
    for (i in ascii.indices) {
        if (bytes[offset + i].toInt() and 0xFF != ascii[i].code) return false
    }
    return true
}

private fun png(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 24) return null
    if (!startsWith(bytes, 0, "\u0089PNG")) return null
    val width = bytes.be32(16)
    val height = bytes.be32(20)
    if (width <= 0 || height <= 0) return null
    return width.toInt() to height.toInt()
}

private fun gif(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 10) return null
    if (!startsWith(bytes, 0, "GIF8")) return null
    val width = bytes.u8(6) or (bytes.u8(7) shl 8)
    val height = bytes.u8(8) or (bytes.u8(9) shl 8)
    if (width <= 0 || height <= 0) return null
    return width to height
}

private fun bmp(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 26) return null
    if (!startsWith(bytes, 0, "BM")) return null
    val width = bytes.u8(18) or (bytes.u8(19) shl 8) or (bytes.u8(20) shl 16) or (bytes.u8(21) shl 24)
    val height = bytes.u8(22) or (bytes.u8(23) shl 8) or (bytes.u8(24) shl 16) or (bytes.u8(25) shl 24)
    if (width == 0 || height == 0) return null
    return kotlin.math.abs(width) to kotlin.math.abs(height)
}

private fun jpeg(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 4 || bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) return null
    var i = 2
    while (i + 9 < bytes.size) {
        if (bytes.u8(i) != 0xFF) {
            i++
            continue
        }
        val marker = bytes.u8(i + 1)
        if (marker == 0xFF) {
            i++
            continue
        }
        // Standalone markers without a length.
        if (marker == 0x01 || marker in 0xD0..0xD7) {
            i += 2
            continue
        }
        val length = bytes.be16(i + 2)
        if (length < 2) return null
        // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC).
        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            val height = bytes.be16(i + 5)
            val width = bytes.be16(i + 7)
            if (width <= 0 || height <= 0) return null
            return width to height
        }
        i += 2 + length
    }
    return null
}

private fun webp(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 30) return null
    if (!startsWith(bytes, 0, "RIFF") || !startsWith(bytes, 8, "WEBP")) return null
    return when {
        startsWith(bytes, 12, "VP8X") -> {
            val width = (bytes.u8(24) or (bytes.u8(25) shl 8) or (bytes.u8(26) shl 16)) + 1
            val height = (bytes.u8(27) or (bytes.u8(28) shl 8) or (bytes.u8(29) shl 16)) + 1
            width to height
        }

        startsWith(bytes, 12, "VP8 ") -> {
            // Lossy: the 16-bit dimensions live just after the 3-byte frame tag
            // and start code, at offsets 26 and 28, masked to 14 bits.
            val width = (bytes.u8(26) or (bytes.u8(27) shl 8)) and 0x3FFF
            val height = (bytes.u8(28) or (bytes.u8(29) shl 8)) and 0x3FFF
            if (width == 0 || height == 0) null else width to height
        }

        startsWith(bytes, 12, "VP8L") -> {
            // Lossless: 14-bit (minus one) dimensions packed from offset 21.
            val b0 = bytes.u8(21)
            val b1 = bytes.u8(22)
            val b2 = bytes.u8(23)
            val b3 = bytes.u8(24)
            val width = 1 + (b0 or ((b1 and 0x3F) shl 8))
            val height = 1 + (((b1 and 0xC0) shr 6) or (b2 shl 2) or ((b3 and 0x0F) shl 10))
            width to height
        }

        else -> null
    }
}
