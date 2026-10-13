package com.cyrusublerman.distaut.render.kotlin

import com.cyrusublerman.distaut.render.*
import kotlin.math.*

/** Pixel-centred bilinear sampling in associated alpha. Native placement is centred. */
internal fun alignImage(source: PixelBuffer, width: Int, height: Int, mode: String, nativeScaleX: Double = 1.0, nativeScaleY: Double = 1.0, cancelled: () -> Unit): PixelBuffer {
    require(mode in setOf("fit", "fill", "stretch", "native")) { "Unknown alignment mode: $mode" }
    if (source.width == width && source.height == height && (mode != "native" || (nativeScaleX == 1.0 && nativeScaleY == 1.0))) return source
    val sx = width.toDouble() / source.width; val sy = height.toDouble() / source.height
    val scale = when (mode) { "fit" -> min(sx, sy); "fill" -> max(sx, sy); "native" -> 1.0; else -> 1.0 }
    val scaleX = when (mode) { "stretch" -> sx; "native" -> nativeScaleX; else -> scale }
    val scaleY = when (mode) { "stretch" -> sy; "native" -> nativeScaleY; else -> scale }
    val offsetX = (width - source.width * scaleX) / 2; val offsetY = (height - source.height * scaleY) / 2
    val out = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), 4))
    for (y in 0 until height) {
        cancelled()
        for (x in 0 until width) {
            val u = (x + 0.5 - offsetX) / scaleX; val v = (y + 0.5 - offsetY) / scaleY
            if (u < 0 || v < 0 || u >= source.width || v >= source.height) continue
            val fx = u - 0.5; val fy = v - 0.5
            val ix = floor(fx).toInt(); val iy = floor(fy).toInt(); val tx = fx - ix; val ty = fy - iy
            var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
            for (j in 0..1) for (i in 0..1) {
                val p = ((iy + j).coerceIn(0, source.height - 1) * source.width + (ix + i).coerceIn(0, source.width - 1)) * 4
                val w = (if (i == 0) 1 - tx else tx) * (if (j == 0) 1 - ty else ty)
                val alpha = (source.rgba[p + 3].toInt() and 255) / 255.0
                a += w * alpha; r += w * alpha * (source.rgba[p].toInt() and 255)
                g += w * alpha * (source.rgba[p + 1].toInt() and 255); b += w * alpha * (source.rgba[p + 2].toInt() and 255)
            }
            val p = (y * width + x) * 4
            if (a > 0) { out[p] = (r / a).roundToInt().coerceIn(0,255).toByte(); out[p+1] = (g / a).roundToInt().coerceIn(0,255).toByte(); out[p+2] = (b / a).roundToInt().coerceIn(0,255).toByte() }
            out[p+3] = (a * 255).roundToInt().coerceIn(0,255).toByte()
        }
    }
    return PixelBuffer(width, height, out)
}
