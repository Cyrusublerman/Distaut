package com.cyrusublerman.distaut.render.kotlin

import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.model.*
import com.cyrusublerman.distaut.render.*
import kotlin.test.*

class MultiSourceRenderingTest {
    private fun solid(w: Int, h: Int, r: Int, g: Int, b: Int, a: Int = 255) = PixelBuffer(w, h,
        ByteArray(w*h*4) { listOf(r,g,b,a)[it%4].toByte() })
    private val graph = ProcessingGraph(sources = ProcessingGraph().sources + ("second" to GraphSource("asset2", "Second")),
        connections = listOf(GraphConnection(SOURCE_NODE_ID, "mix", "a"), GraphConnection("second", "mix", "b")), outputNodeId = "mix")
    private fun request(mode: String = "stretch") = RenderRequest(1, 1, solid(4,4,255,0,0),
        listOf(BuiltInEffects.Mix.instantiate("mix").copy(parameters = mapOf("amount" to ParameterValue.Decimal(1.0), "fit" to ParameterValue.Choice(mode)))),
        RenderQuality.FINAL, 42, graph = graph, sources = mapOf("second" to solid(4,2,0,0,255)))
    @Test fun differentSizedImagesAlignToAAndPreserveColourAndAlpha() {
        val stretch = KotlinGraphRenderer().render(request()).output
        assertEquals(4, stretch.width); assertEquals(4, stretch.height)
        assertContentEquals(solid(4,4,0,0,255).rgba, stretch.rgba)
        val fit = KotlinGraphRenderer().render(request("fit")).output
        assertEquals(0, fit.rgba[3].toInt())
        assertEquals(255, fit.rgba[(1*4+1)*4+2].toInt() and 255)
        val fill = KotlinGraphRenderer().render(request("fill")).output
        assertContentEquals(stretch.rgba, fill.rgba)
    }
    @Test fun associatedSamplingDoesNotLeakInvisibleRed() {
        val source = PixelBuffer(2,1,byteArrayOf(-1,0,0,0, 0,0,-1,-1))
        val result = alignImage(source,4,1,"stretch") {}
        assertEquals(0, result.rgba[4].toInt())
        assertEquals(255, result.rgba[6].toInt() and 255)
        assertTrue((result.rgba[7].toInt() and 255) in 1..254)
    }
    @Test fun nativeAlignmentUsesDocumentPixelsAtDifferentPreviewScales() {
        val request = request("native").copy(sources = mapOf("second" to solid(4,4,0,0,255)),
            sourceDimensions = mapOf(SOURCE_NODE_ID to ImageDimensions(8,8), "second" to ImageDimensions(4,4)))
        val output = KotlinGraphRenderer().render(request).output
        assertEquals(0, output.rgba[3].toInt())
        assertEquals(255, output.rgba[(1*4+1)*4+3].toInt() and 255)
    }
    @Test fun unusedMissingSourcesDoNotBlockAndNamedMaskUsesItsOwnExtent() {
        val mask = BuiltInEffects.LuminanceMask.instantiate("mask")
        val named = graph.copy(connections = listOf(GraphConnection("second", "mask", "image")), outputNodeId = SOURCE_NODE_ID,
            outputs = mapOf("matte" to GraphOutput("Matte", PortRef("mask"))), selectedOutputId = "matte")
        val output = KotlinGraphRenderer().render(request().copy(effects = listOf(mask), graph = named,
            sources = mapOf("second" to solid(3,2,255,255,255)), viewerNodeId = SOURCE_NODE_ID)).output
        assertEquals(3, output.width); assertEquals(2, output.height)
        assertContentEquals(solid(3,2,255,255,255).rgba, output.rgba)
        val primaryOnly = request().copy(effects = emptyList(), graph = graph.copy(connections = emptyList(), outputNodeId = SOURCE_NODE_ID), sources = emptyMap())
        assertContentEquals(primaryOnly.source.rgba, KotlinGraphRenderer().render(primaryOnly).output.rgba)
        assertFails { KotlinGraphRenderer().render(request().copy(sources = emptyMap())) }
    }
    @Test fun legacyMixRetainsPixelsAndRequiresExplicitUpgradeForDifferentSizes() {
        val old = request().copy(effects = request().effects.map { it.copy(algorithmVersion = "associated-mix-v1") })
        assertFailsWith<IllegalArgumentException> { KotlinGraphRenderer().render(old) }
        val equal = old.copy(sources = mapOf("second" to solid(4,4,0,0,255)))
        assertContentEquals(solid(4,4,0,0,255).rgba, KotlinGraphRenderer().render(equal).output.rgba)
    }
}
