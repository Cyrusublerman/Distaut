package com.cyrusublerman.distaut.projects

import com.cyrusublerman.distaut.render.PixelBuffer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Encodes straight RGBA8 rows directly; no second full-size Android Bitmap is required. */
object PngWriter {
    fun write(image: PixelBuffer, output: OutputStream, checkCancelled: () -> Unit = {}) {
        val data = DataOutputStream(output)
        data.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type: String, bytes: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            data.writeInt(bytes.size); data.write(name); data.write(bytes)
            val crc = CRC32().apply { update(name); update(bytes) }
            data.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { it.writeInt(image.width); it.writeInt(image.height); it.write(byteArrayOf(8,6,0,0,0)) }
        chunk("IHDR", header.toByteArray())
        chunk("sRGB", byteArrayOf(0))
        val idat = object : OutputStream() {
            val buffer = ByteArrayOutputStream(65536)
            override fun write(value: Int) { buffer.write(value); if (buffer.size() >= 65536) flush() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                var position = offset
                var remaining = length
                while (remaining > 0) {
                    val n = minOf(remaining, 65536 - buffer.size())
                    buffer.write(bytes, position, n); position += n; remaining -= n
                    if (buffer.size() == 65536) flush()
                }
            }
            override fun flush() { if (buffer.size() > 0) { chunk("IDAT", buffer.toByteArray()); buffer.reset() } }
        }
        val deflater = Deflater()
        try {
            val compressed = DeflaterOutputStream(idat, deflater, 65536)
            for (y in 0 until image.height) {
                checkCancelled()
                compressed.write(0) // PNG filter None, deterministic and bounded memory.
                compressed.write(image.rgba, y * image.width * 4, image.width * 4)
            }
            compressed.finish(); idat.flush()
        } finally { deflater.end() }
        chunk("IEND", byteArrayOf()); data.flush()
    }
}
