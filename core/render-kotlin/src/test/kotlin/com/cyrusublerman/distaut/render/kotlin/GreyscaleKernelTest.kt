package com.cyrusublerman.distaut.render.kotlin

import com.cyrusublerman.distaut.model.EffectInstance
import com.cyrusublerman.distaut.model.ParameterValue
import com.cyrusublerman.distaut.render.PixelBuffer
import com.cyrusublerman.distaut.render.RenderQuality
import com.cyrusublerman.distaut.render.RenderRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals

class GreyscaleKernelTest {
    @Test
    fun preservesAlphaAndUsesVersionedIntegerRec709Weights() {
        val source = PixelBuffer(
            2,
            1,
            byteArrayOf(
                255.toByte(), 0, 0, 255.toByte(),
                0, 255.toByte(), 0, 128.toByte(),
            ),
        )
        assertContentEquals(
            byteArrayOf(
                54, 54, 54, 255.toByte(),
                182.toByte(), 182.toByte(), 182.toByte(), 128.toByte(),
            ),
            GreyscaleKernel.apply(source).rgba,
        )
    }

    @Test
    fun pipelineSupportsInvertPosteriseAndOpacity() {
        val source = PixelBuffer(1, 1, byteArrayOf(0, 50, 250.toByte(), 200.toByte()))
        val result = KotlinPipelineRenderer().render(
            RenderRequest(
                generation = 1,
                sourceRevision = 1,
                source = source,
                effects = listOf(
                    EffectInstance("invert", "invert", opacity = 0.25),
                    EffectInstance(
                        "poster",
                        "posterise",
                        parameters = mapOf("levels" to ParameterValue.Integer(2)),
                    ),
                ),
                quality = RenderQuality.PREVIEW,
                globalSeed = 42,
            )
        )
        assertContentEquals(
            byteArrayOf(0, 0, 255.toByte(), 200.toByte()),
            result.output.rgba,
        )
    }
}
