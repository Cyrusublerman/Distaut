package com.cyrusublerman.distaut.render.kotlin

import com.cyrusublerman.distaut.render.PixelBuffer
import com.cyrusublerman.distaut.render.RenderBackend
import com.cyrusublerman.distaut.render.RenderRequest
import com.cyrusublerman.distaut.render.RenderResult

class KotlinPipelineRenderer : RenderBackend {
    override fun render(request: RenderRequest): RenderResult {
        val started = System.nanoTime()
        var current = request.source.copy()
        val applied = mutableListOf<String>()

        for (effect in request.effects) {
            if (!effect.enabled) continue
            current = when (effect.type) {
                "greyscale" -> GreyscaleKernel.apply(current)
                else -> current
            }
            applied += effect.id
        }

        return RenderResult(
            generation = request.generation,
            sourceRevision = request.sourceRevision,
            output = current,
            appliedEffectIds = applied,
            durationNanos = System.nanoTime() - started,
        )
    }
}

object GreyscaleKernel {
    /**
     * Deterministic encoded-sRGB Rec.709 approximation.
     * Alpha is preserved. Integer coefficients sum to 256.
     */
    fun apply(source: PixelBuffer): PixelBuffer {
        val output = source.rgba.copyOf()
        var i = 0
        while (i < output.size) {
            val r = output[i].toInt() and 0xff
            val g = output[i + 1].toInt() and 0xff
            val b = output[i + 2].toInt() and 0xff
            val luminance = (54 * r + 183 * g + 19 * b + 128) shr 8
            val y = luminance.toByte()
            output[i] = y
            output[i + 1] = y
            output[i + 2] = y
            i += 4
        }
        return PixelBuffer(source.width, source.height, output)
    }
}
