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
                "ordered_dither" -> OrderedDitherKernel.apply(current, effect.parameters["levels"], request)
                "pixelate" -> PixelateKernel.apply(current, effect.parameters["blockSize"], request)
                "box_blur" -> BoxBlurKernel.apply(current, effect.parameters["radius"], request)
                else -> throw UnsupportedEffectException(effect.id, effect.type)
            }
            current = if (effect.opacity >= 1.0) processed else {
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
    fun apply(source: PixelBuffer, request: RenderRequest? = null): PixelBuffer {
        val output = source.rgba.copyOf()
        var i = 0
        while (i < output.size) {
            if (i and 0x3fff == 0) request?.let(::checkCancelled)
            val r = output[i].toInt() and 0xff
            val g = output[i + 1].toInt() and 0xff
            val b = output[i + 2].toInt() and 0xff
            val y = ((54 * r + 183 * g + 19 * b + 128) shr 8).toByte()
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
    fun apply(source: PixelBuffer, parameter: ParameterValue?, request: RenderRequest? = null): PixelBuffer {
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
                val level = (value * denominator + 127) / 255
                output[i + channel] = ((level * 255 + denominator / 2) / denominator).toByte()
            }
            i += 4
        }
        return PixelBuffer(source.width, source.height, output)
    }
}

object NormalOpacityBlend {
    fun apply(base: PixelBuffer, processed: PixelBuffer, opacity: Double, request: RenderRequest? = null): PixelBuffer {
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
            output[i + 3] = base.rgba[i + 3]
            i += 4
        }
        return PixelBuffer(base.width, base.height, output)
    }
}

object OrderedDitherKernel {
    private val bayer4 = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5,
    )

    fun apply(source: PixelBuffer, parameter: ParameterValue?, request: RenderRequest? = null): PixelBuffer {
        val levels = (parameter as? ParameterValue.Integer)?.value?.coerceIn(2, 8) ?: 2
        val output = source.rgba.copyOf()
        val steps = levels - 1
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val pixel = (y * source.width + x) * 4
                if (pixel and 0x3fff == 0) request?.let(::checkCancelled)
                val threshold = (bayer4[(y and 3) * 4 + (x and 3)] + 0.5) / 16.0 - 0.5
                for (channel in 0..2) {
                    val value = (output[pixel + channel].toInt() and 0xff) / 255.0
                    val adjusted = (value + threshold / levels).coerceIn(0.0, 1.0)
                    val quantised = (adjusted * steps).roundToInt() / steps.toDouble()
                    output[pixel + channel] = (quantised * 255.0).roundToInt().toByte()
                }
            }
        }
        return PixelBuffer(source.width, source.height, output)
    }
}

object PixelateKernel {
    fun apply(source: PixelBuffer, parameter: ParameterValue?, request: RenderRequest? = null): PixelBuffer {
        val block = (parameter as? ParameterValue.Integer)?.value?.coerceIn(2, 256) ?: 8
        val output = source.rgba.copyOf()
        for (top in 0 until source.height step block) {
            for (left in 0 until source.width step block) {
                request?.let(::checkCancelled)
                val right = minOf(left + block, source.width)
                val bottom = minOf(top + block, source.height)
                val sums = LongArray(4)
                var count = 0
                for (y in top until bottom) for (x in left until right) {
                    val i = (y * source.width + x) * 4
                    for (channel in 0..3) {
                        sums[channel] = sums[channel] + (source.rgba[i + channel].toInt() and 0xff)
                    }
                    count++
                }
                val average = IntArray(4) { ((sums[it] + count / 2) / count).toInt() }
                for (y in top until bottom) for (x in left until right) {
                    val i = (y * source.width + x) * 4
                    for (channel in 0..3) output[i + channel] = average[channel].toByte()
                }
            }
        }
        return PixelBuffer(source.width, source.height, output)
    }
}

object BoxBlurKernel {
    fun apply(source: PixelBuffer, parameter: ParameterValue?, request: RenderRequest? = null): PixelBuffer {
        val radius = (parameter as? ParameterValue.Integer)?.value?.coerceIn(1, 64) ?: 2
        val horizontal = ByteArray(source.rgba.size)
        val output = ByteArray(source.rgba.size)
        blurPass(source.rgba, horizontal, source.width, source.height, radius, true, request)
        blurPass(horizontal, output, source.width, source.height, radius, false, request)
        return PixelBuffer(source.width, source.height, output)
    }

    private fun blurPass(
        input: ByteArray,
        output: ByteArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontalPass: Boolean,
        request: RenderRequest?,
    ) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val target = (y * width + x) * 4
                if (target and 0x3fff == 0) request?.let(::checkCancelled)
                for (channel in 0..3) {
                    var sum = 0
                    var count = 0
                    for (offset in -radius..radius) {
                        val sx = if (horizontalPass) (x + offset).coerceIn(0, width - 1) else x
                        val sy = if (horizontalPass) y else (y + offset).coerceIn(0, height - 1)
                        sum += input[(sy * width + sx) * 4 + channel].toInt() and 0xff
                        count++
                    }
                    output[target + channel] = ((sum + count / 2) / count).toByte()
                }
            }
        }
    }
}

private fun checkCancelled(request: RenderRequest) {
    if (request.cancellationProbe.isCancelled() || Thread.currentThread().isInterrupted) {
        throw RenderCancelledException()
    }
}
