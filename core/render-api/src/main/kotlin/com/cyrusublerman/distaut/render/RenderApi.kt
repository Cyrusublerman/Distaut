package com.cyrusublerman.distaut.render

import com.cyrusublerman.distaut.model.EffectInstance

class PixelBuffer(val width: Int, val height: Int, val rgba: ByteArray) {
    init {
        require(width > 0 && height > 0)
        require(rgba.size == width * height * 4)
    }
    fun copy(): PixelBuffer = PixelBuffer(width, height, rgba.copyOf())
}

enum class RenderQuality { PREVIEW, FINAL }

fun interface CancellationProbe {
    fun isCancelled(): Boolean
}

data class RenderRequest(
    val generation: Long,
    val sourceRevision: Long,
    val source: PixelBuffer,
    val effects: List<EffectInstance>,
    val quality: RenderQuality,
    val globalSeed: Long,
    val cancellationProbe: CancellationProbe = CancellationProbe { false },
)

data class RenderResult(
    val generation: Long,
    val sourceRevision: Long,
    val quality: RenderQuality,
    val output: PixelBuffer,
    val appliedEffectIds: List<String>,
    val durationNanos: Long,
)

fun interface RenderBackend {
    fun render(request: RenderRequest): RenderResult
}

class RenderCancelledException : RuntimeException("Render cancelled")

class UnsupportedEffectException(
    val effectId: String,
    val effectType: String,
) : RuntimeException("Unsupported effect '$effectType' at node '$effectId'")

class UnsupportedBlendModeException(
    val effectId: String,
    val blendMode: String,
) : RuntimeException("Unsupported blend mode '$blendMode' at node '$effectId'")

fun interface CancellationProbe { fun isCancelled(): Boolean }
data class RenderRequest(val generation: Long, val sourceRevision: Long, val source: PixelBuffer, val effects: List<EffectInstance>, val quality: RenderQuality, val globalSeed: Long, val cancellationProbe: CancellationProbe = CancellationProbe { false })
data class RenderResult(val generation: Long, val sourceRevision: Long, val quality: RenderQuality, val output: PixelBuffer, val appliedEffectIds: List<String>, val durationNanos: Long)
fun interface RenderBackend { fun render(request: RenderRequest): RenderResult }
class RenderCancelledException : RuntimeException("Render cancelled")
class UnsupportedEffectException(val effectId: String, val effectType: String) : RuntimeException("Unsupported effect '$effectType' at node '$effectId'")
class UnsupportedBlendModeException(val effectId: String, val blendMode: String) : RuntimeException("Unsupported blend mode '$blendMode' at node '$effectId'")
class ResultGenerationGate {
    @Volatile private var latestGeneration: Long = Long.MIN_VALUE
    fun request(generation: Long) { if (generation > latestGeneration) latestGeneration = generation }
    fun accepts(result: RenderResult): Boolean = result.generation == latestGeneration
}
