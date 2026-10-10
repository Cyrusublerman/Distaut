package com.cyrusublerman.distaut.render

import com.cyrusublerman.distaut.model.EffectInstance

/** Buffers passed to a backend are read-only. Kernels return independently owned storage. */
class PixelBuffer(val width: Int, val height: Int, val rgba: ByteArray) {
    init {
        require(width > 0 && height > 0)
        require(width.toLong() * height * 4 == rgba.size.toLong())
    }
    fun copy(): PixelBuffer = PixelBuffer(width, height, rgba.copyOf())
}

enum class RenderQuality { PREVIEW, FINAL }
fun interface CancellationProbe { fun isCancelled(): Boolean }
/** All spatial parameters and matrix phases use oriented document pixels. */
data class RenderRequest(
    val generation: Long,
    val sourceRevision: Long,
    val source: PixelBuffer,
    val effects: List<EffectInstance>,
    val quality: RenderQuality,
    val globalSeed: Long,
    val cancellationProbe: CancellationProbe = CancellationProbe { false },
    val documentWidth: Int = source.width,
    val documentHeight: Int = source.height,
    val originX: Int = 0,
    val originY: Int = 0,
) {
    init { require(documentWidth > 0 && documentHeight > 0) }
    val scaleX: Double get() = source.width.toDouble() / documentWidth
    val scaleY: Double get() = source.height.toDouble() / documentHeight
}
data class RenderResult(val generation: Long, val sourceRevision: Long, val quality: RenderQuality, val output: PixelBuffer, val appliedEffectIds: List<String>, val durationNanos: Long)
fun interface RenderBackend { fun render(request: RenderRequest): RenderResult }
class RenderCancelledException : RuntimeException("Render cancelled")
class UnsupportedEffectException(val effectId: String, val effectType: String) : RuntimeException("Unsupported effect '$effectType' at node '$effectId'")
class UnsupportedBlendModeException(val effectId: String, val blendMode: String) : RuntimeException("Unsupported blend mode '$blendMode' at node '$effectId'")
class ResultGenerationGate {
    @Volatile private var latestGeneration: Long = Long.MIN_VALUE
    @Synchronized fun request(generation: Long) { if (generation > latestGeneration) latestGeneration = generation }
    fun accepts(result: RenderResult): Boolean = result.generation == latestGeneration
}
