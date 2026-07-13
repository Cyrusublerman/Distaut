package com.cyrusublerman.distaut.render

import com.cyrusublerman.distaut.model.EffectInstance

class PixelBuffer(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
) {
    init {
        require(width > 0 && height > 0)
        require(rgba.size == width * height * 4) {
            "Expected ${width * height * 4} RGBA bytes, received ${rgba.size}"
        }
    }

    fun copy(): PixelBuffer = PixelBuffer(width, height, rgba.copyOf())
}

enum class RenderQuality { PREVIEW, FINAL }

data class RenderRequest(
    val generation: Long,
    val sourceRevision: Long,
    val source: PixelBuffer,
    val effects: List<EffectInstance>,
    val quality: RenderQuality,
    val globalSeed: Long,
)

data class RenderResult(
    val generation: Long,
    val sourceRevision: Long,
    val output: PixelBuffer,
    val appliedEffectIds: List<String>,
    val durationNanos: Long,
)

fun interface RenderBackend {
    fun render(request: RenderRequest): RenderResult
}

class ResultGenerationGate {
    @Volatile
    private var latestGeneration: Long = Long.MIN_VALUE

    fun request(generation: Long) {
        if (generation > latestGeneration) latestGeneration = generation
    }

    fun accepts(result: RenderResult): Boolean = result.generation == latestGeneration
}
