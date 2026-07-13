package com.cyrusublerman.distaut.render.kotlin

import com.cyrusublerman.distaut.model.ParameterValue
import com.cyrusublerman.distaut.render.PixelBuffer
import com.cyrusublerman.distaut.render.RenderBackend
import com.cyrusublerman.distaut.render.RenderCancelledException
import com.cyrusublerman.distaut.render.RenderRequest
import com.cyrusublerman.distaut.render.RenderResult
import com.cyrusublerman.distaut.render.UnsupportedBlendModeException
import com.cyrusublerman.distaut.render.UnsupportedEffectException
import kotlin.math.roundToInt

class KotlinPipelineRenderer : RenderBackend {
    override fun render(request: RenderRequest): RenderResult {
        val started = System.nanoTime()
        var current = request.source.copy()
        val applied = mutableListOf<String>()

        for (effect in request.effects) {
            checkCancelled(request)
            if (!effect.enabled) continue
            if (effect.opaquePayload != null) {
                throw UnsupportedEffectException(effect.id, effect.type)
            }
            if (effect.blendMode != "normal") {
                throw UnsupportedBlendModeException(effect.id, effect.blendMode)
            }

            val processed = when (effect.type) {
                "greyscale" -> GreyscaleKernel.apply(current, request)
                "invert" -> InvertKernel.apply(current, request)
                "posterise" -> PosteriseKernel.apply(current, effect.parameters["levels"], request)
                else -> throw UnsupportedEffectException(effect.id, effect.type)
            }
            current = if (effect.opacity >= 1.0) {
                processed
            } else {
                NormalOpacityBlend.apply(current, processed, effect.opacity, request)
            }
            applied += effect.id
        }

        return RenderResult(
            generation = request.generation,
            sourceRevision = request.sourceRevision,
            quality = request.quality,
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
    fun apply(source: PixelBuffer, request: RenderRequest? = null): PixelBuffer {
        val output = source.rgba.copyOf()
        var i = 0
        while (i < output.size) {
            if (i and 0x3fff == 0) request?.let(::checkCancelled)
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

object InvertKernel {
    fun apply(source: PixelBuffer, request: RenderRequest? = null): PixelBuffer {
        val output = source.rgba.copyOf()
        var i = 0
        while (i < output.size) {
            if (i and 0x3fff == 0) request?.let(::checkCancelled)
            output[i] = (255 - (output[i].toInt() and 0xff)).toByte()
            output[i + 1] = (255 - (output[i + 1].toInt() and 0xff)).toByte()
            output[i + 2] = (255 - (output[i + 2].toInt() and 0xff)).toByte()
            i += 4
        }
        return PixelBuffer(source.width, source.height, output)
    }
}

object PosteriseKernel {
    fun apply(
        source: PixelBuffer,
        parameter: ParameterValue?,
        request: RenderRequest? = null,
    ): PixelBuffer {
        val levels = when (parameter) {
            is ParameterValue.Integer -> parameter.value
            is ParameterValue.Decimal -> parameter.value.roundToInt()
            else -> 6
        }.coerceIn(2, 256)
        val denominator = levels - 1
        val output = source.rgba.copyOf()
        var i = 0
        while (i < output.size) {
            if (i and 0x3fff == 0) request?.let(::checkCancelled)
            for (channel in 0..2) {
                val value = output[i + channel].toInt() and 0xff
                val level = ((value * denominator + 127) / 255)
                output[i + channel] = ((level * 255 + denominator / 2) / denominator).toByte()
            }
            i += 4
        }
        return PixelBuffer(source.width, source.height, output)
    }
}

object NormalOpacityBlend {
    fun apply(
        base: PixelBuffer,
        processed: PixelBuffer,
        opacity: Double,
        request: RenderRequest? = null,
    ): PixelBuffer {
        require(base.width == processed.width && base.height == processed.height)
        val amount = opacity.coerceIn(0.0, 1.0)
        if (amount <= 0.0) return base.copy()
        if (amount >= 1.0) return processed.copy()
        val output = base.rgba.copyOf()
        var i = 0
        while (i < output.size) {
            if (i and 0x3fff == 0) request?.let(::checkCancelled)
            for (channel in 0..2) {
                val a = base.rgba[i + channel].toInt() and 0xff
                val b = processed.rgba[i + channel].toInt() and 0xff
                output[i + channel] = (a + (b - a) * amount).roundToInt().coerceIn(0, 255).toByte()
            }
            // Effects currently preserve alpha; the pipeline contract keeps the incoming alpha.
            output[i + 3] = base.rgba[i + 3]
            i += 4
        }
        return PixelBuffer(base.width, base.height, output)
    }
}

private fun checkCancelled(request: RenderRequest) {
    if (request.cancellationProbe.isCancelled() || Thread.currentThread().isInterrupted) {
        throw RenderCancelledException()
    }
}
