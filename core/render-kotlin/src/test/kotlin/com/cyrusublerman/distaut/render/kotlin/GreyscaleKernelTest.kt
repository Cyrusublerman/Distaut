package com.cyrusublerman.distaut.render.kotlin

import com.cyrusublerman.distaut.render.PixelBuffer
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
}
