package com.cyrusublerman.distaut.render.kotlin

import com.cyrusublerman.distaut.model.ParameterValue
import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.effects.normalise
import com.cyrusublerman.distaut.model.EffectInstance
import kotlin.math.floor
import kotlin.math.ceil
import com.cyrusublerman.distaut.render.PixelBuffer
import com.cyrusublerman.distaut.render.RenderBackend
import com.cyrusublerman.distaut.render.RenderCancelledException
import com.cyrusublerman.distaut.render.RenderRequest
import com.cyrusublerman.distaut.render.RenderResult
import com.cyrusublerman.distaut.render.UnsupportedBlendModeException
import com.cyrusublerman.distaut.render.UnsupportedEffectException
import kotlin.math.roundToInt

class KotlinPipelineRenderer(private val cacheBudgetBytes: Long = 0L) : RenderBackend {
    private data class Key(val source: PixelBuffer, val effects: List<EffectInstance>, val seed: Long,
        val width: Int, val height: Int, val x: Int, val y: Int)
    private val cache = LinkedHashMap<Key, PixelBuffer>(16, 0.75f, true)
    private var cachedBytes = 0L
    @Synchronized fun clearCache() { cache.clear(); cachedBytes = 0L }

    // A single execution lane also bounds scratch memory during preview/export transitions.
    @Synchronized override fun render(request: RenderRequest): RenderResult {
        val started = System.nanoTime()
        checkCancelled(request)
        if (cache.keys.firstOrNull()?.source !== request.source) clearCache()
        var current = request.source
        val applied = mutableListOf<String>()
        val prefix = mutableListOf<EffectInstance>()
        for (original in request.effects) {
            checkCancelled(request)
            if (!original.enabled) continue
            if (!original.isResolved) throw UnsupportedEffectException(original.id, original.type)
            if (original.blendMode != "normal") throw UnsupportedBlendModeException(original.id, original.blendMode)
            val definition = BuiltInEffects.registry.definition(original.type)
                ?: throw UnsupportedEffectException(original.id, original.type)
            val legacyVersion = when (original.type) {
                "ordered_dither" -> "bayer4-rgb-v1"
                "pixelate" -> "block-average-v1"
                "box_blur" -> "separable-clamp-v1"
                else -> definition.algorithmVersion
            }
            require(original.algorithmVersion == null || original.algorithmVersion in setOf(legacyVersion, definition.algorithmVersion)) {
                "Unsupported algorithm ${original.algorithmVersion} at ${original.id}"
            }
            require(original.compositionVersion in 1..2) { "Unsupported composition at ${original.id}" }
            val effect = definition.normalise(original)
            prefix += effect
            val key = Key(request.source, prefix.toList(), request.globalSeed,
                request.documentWidth, request.documentHeight, request.originX, request.originY)
            val cached = if (request.quality == com.cyrusublerman.distaut.render.RenderQuality.PREVIEW) cache[key] else null
            if (cached != null) current = cached else {
                val modern = effect.algorithmVersion == definition.algorithmVersion
                val processed = when (effect.type) {
                    "greyscale" -> GreyscaleKernel.apply(current, request)
                    "invert" -> InvertKernel.apply(current, request)
                    "posterise" -> PosteriseKernel.apply(current, effect.parameters["levels"], request)
                    "ordered_dither" -> OrderedDitherKernel.apply(current, effect.parameters["levels"], request, modern)
                    "pixelate" -> PixelateKernel.apply(current, effect.parameters["blockSize"], request, modern)
                    "box_blur" -> BoxBlurKernel.apply(current, effect.parameters["radius"], request, modern)
                    else -> throw UnsupportedEffectException(effect.id, effect.type)
                }
                current = if (effect.opacity >= 1.0) processed else NormalOpacityBlend.apply(
                    current, processed, effect.opacity, request, effect.compositionVersion >= 2)
                checkCancelled(request)
                if (request.quality == com.cyrusublerman.distaut.render.RenderQuality.PREVIEW && current.rgba.size <= cacheBudgetBytes) {
                    cache.put(key, current)?.let { cachedBytes -= it.rgba.size }
                    cachedBytes += current.rgba.size
                    while (cachedBytes > cacheBudgetBytes && cache.isNotEmpty()) {
                        val iterator = cache.entries.iterator()
                        cachedBytes -= iterator.next().value.rgba.size
                        iterator.remove()
                    }
                }
            }
            applied += effect.id
        }
        checkCancelled(request)
        return RenderResult(request.generation, request.sourceRevision, request.quality,
            current, applied, System.nanoTime() - started)
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
    fun apply(base: PixelBuffer, processed: PixelBuffer, opacity: Double,
        request: RenderRequest? = null, associated: Boolean = true): PixelBuffer {
        require(base.width == processed.width && base.height == processed.height)
        val amount = opacity.coerceIn(0.0, 1.0)
        if (amount <= 0.0) return base.copy()
        if (amount >= 1.0) return processed.copy()
        val out = ByteArray(base.rgba.size)
        for (i in out.indices step 4) {
            if (i and 0x3fff == 0) request?.let(::checkCancelled)
            val a = base.rgba[i + 3].u()
            val b = processed.rgba[i + 3].u()
            val alpha = a + (b - a) * amount
            for (c in 0..2) {
                val x = base.rgba[i + c].u()
                val y = processed.rgba[i + c].u()
                out[i + c] = if (associated) {
                    (if (alpha <= 0) 0 else ((x * a * (1 - amount) + y * b * amount) / alpha).roundToInt()).toByte()
                } else (x + (y - x) * amount).roundToInt().toByte()
            }
            out[i + 3] = if (associated) alpha.roundToInt().toByte() else base.rgba[i + 3]
        }
        return PixelBuffer(base.width, base.height, out)
    }
}

object OrderedDitherKernel {
    private val bayer4 = intArrayOf(0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5)
    fun apply(source: PixelBuffer, parameter: ParameterValue?, request: RenderRequest? = null,
        corrected: Boolean = true): PixelBuffer {
        val levels = integer(parameter, 2).coerceIn(2, 8)
        val out = source.rgba.copyOf()
        val steps = levels - 1
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val i = (y * source.width + x) * 4
            if (i and 0x3fff == 0) request?.let(::checkCancelled)
            val dx = floor(x / (request?.scaleX ?: 1.0)).toInt() + (request?.originX ?: 0)
            val dy = floor(y / (request?.scaleY ?: 1.0)).toInt() + (request?.originY ?: 0)
            val t = (bayer4[(dy and 3) * 4 + (dx and 3)] + 0.5) / 16.0
            for (c in 0..2) {
                val value = source.rgba[i + c].u() / 255.0
                val q = if (corrected) floor(value * steps + t).coerceIn(0.0, steps.toDouble()) / steps
                    else ((value + (t - 0.5) / levels).coerceIn(0.0, 1.0) * steps).roundToInt() / steps.toDouble()
                out[i + c] = (q * 255).roundToInt().toByte()
            }
        }
        return PixelBuffer(source.width, source.height, out)
    }
}

object PixelateKernel {
    fun apply(source: PixelBuffer, parameter: ParameterValue?, request: RenderRequest? = null,
        associated: Boolean = true): PixelBuffer {
        val block = integer(parameter, 8).coerceIn(2, 256)
        val out = ByteArray(source.rgba.size)
        val sx = request?.scaleX ?: 1.0
        val sy = request?.scaleY ?: 1.0
        fun boundary(position: Int, origin: Int, scale: Double, limit: Int): Int {
            val doc = floor(position / scale).toInt() + origin
            val end = (Math.floorDiv(doc, block) + 1) * block
            return ceil((end - origin) * scale).toInt().coerceIn(position + 1, limit)
        }
        var top = 0
        while (top < source.height) {
            val bottom = boundary(top, request?.originY ?: 0, sy, source.height)
            var left = 0
            while (left < source.width) {
                request?.let(::checkCancelled)
                val right = boundary(left, request?.originX ?: 0, sx, source.width)
                val sums = LongArray(4)
                val count = (bottom - top).toLong() * (right - left)
                for (y in top until bottom) for (x in left until right) {
                    val i = (y * source.width + x) * 4
                    val alpha = source.rgba[i + 3].u()
                    for (c in 0..2) sums[c] += source.rgba[i + c].u().toLong() * if (associated) alpha else 1
                    sums[3] += alpha
                }
                val average = IntArray(4) { c ->
                    val divisor = if (associated && c < 3) sums[3] else count
                    if (divisor == 0L) 0 else ((sums[c] + divisor / 2) / divisor).toInt()
                }
                for (y in top until bottom) for (x in left until right) {
                    val i = (y * source.width + x) * 4
                    for (c in 0..3) out[i + c] = average[c].toByte()
                }
                left = right
            }
            top = bottom
        }
        return PixelBuffer(source.width, source.height, out)
    }
}

object BoxBlurKernel {
    fun apply(source: PixelBuffer, parameter: ParameterValue?, request: RenderRequest? = null,
        associated: Boolean = true): PixelBuffer {
        val radius = integer(parameter, 2).coerceIn(1, 64)
        val rx = (radius * (request?.scaleX ?: 1.0)).roundToInt().coerceAtLeast(0)
        val ry = (radius * (request?.scaleY ?: 1.0)).roundToInt().coerceAtLeast(0)
        val horizontal = ByteArray(source.rgba.size)
        val output = ByteArray(source.rgba.size)
        blurPass(source.rgba, horizontal, source.width, source.height, rx, true, associated, request)
        blurPass(horizontal, output, source.width, source.height, ry, false, associated, request)
        return PixelBuffer(source.width, source.height, output)
    }
    private fun blurPass(input: ByteArray, out: ByteArray, width: Int, height: Int,
        radius: Int, horizontal: Boolean, associated: Boolean, request: RenderRequest?) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val count = 2L * radius + 1
        fun index(line: Int, position: Int): Int = if (horizontal)
            (line * width + position.coerceIn(0, length - 1)) * 4
            else (position.coerceIn(0, length - 1) * width + line) * 4
        for (line in 0 until lines) {
            request?.let(::checkCancelled)
            val sums = LongArray(4)
            fun add(position: Int, sign: Int) {
                val i = index(line, position)
                val alpha = input[i + 3].u()
                for (c in 0..2) sums[c] += sign.toLong() * input[i + c].u() * if (associated) alpha else 1
                sums[3] += sign.toLong() * alpha
            }
            for (offset in -radius..radius) add(offset, 1)
            for (position in 0 until length) {
                if (position and 0x3ff == 0) request?.let(::checkCancelled)
                val target = index(line, position)
                for (c in 0..3) {
                    val divisor = if (associated && c < 3) sums[3] else count
                    out[target + c] = (if (divisor == 0L) 0 else (sums[c] + divisor / 2) / divisor).toByte()
                }
                add(position - radius, -1)
                add(position + radius + 1, 1)
            }
        }
    }
}

private fun integer(value: ParameterValue?, fallback: Int): Int = when (value) {
    is ParameterValue.Integer -> value.value
    is ParameterValue.Decimal -> value.value.roundToInt()
    else -> fallback
}
private fun Byte.u(): Int = toInt() and 0xff
private fun checkCancelled(request: RenderRequest) {
    if (request.cancellationProbe.isCancelled() || Thread.currentThread().isInterrupted) throw RenderCancelledException()
}
