package com.cyrusublerman.distaut.render.kotlin

import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.model.*
import com.cyrusublerman.distaut.render.*
import kotlin.test.*

class GraphRenderingTest {
    private val source = PixelBuffer(2, 1, byteArrayOf(0,0,0,-1, -1,-1,-1,-1))
    private val invert = BuiltInEffects.Invert.instantiate("invert")
    private val mask = BuiltInEffects.LuminanceMask.instantiate("mask")
    private val mix = BuiltInEffects.Mix.instantiate("mix").copy(parameters = mapOf("amount" to ParameterValue.Decimal(1.0)))
    private val effects = listOf(mix, mask, invert) // Deliberately not topological list order.
    private val graph = ProcessingGraph(connections = listOf(
        GraphConnection(SOURCE_NODE_ID, invert.id, "image"), GraphConnection(SOURCE_NODE_ID, mask.id, "image"),
        GraphConnection(invert.id, mix.id, "a"), GraphConnection(SOURCE_NODE_ID, mix.id, "b"), GraphConnection(mask.id, mix.id, "mask"),
    ), outputNodeId = mix.id)
    private fun request() = RenderRequest(1, 2, source, effects, RenderQuality.PREVIEW, 42, graph = graph)
    @Test fun branchingMaskControlsTwoDifferentInputsRegardlessOfListOrder() {
        val result = KotlinGraphRenderer().render(request())
        assertContentEquals(byteArrayOf(-1,-1,-1,-1, -1,-1,-1,-1), result.output.rgba)
        assertEquals(setOf("invert", "mask", "mix"), result.appliedEffectIds.toSet())
    }
    @Test fun independentMaskViewerNeverChangesFinalExport() {
        val preview = KotlinGraphRenderer().render(request().copy(viewerNodeId = mask.id))
        assertContentEquals(source.rgba, preview.output.rgba)
        assertEquals(listOf(mask.id), preview.appliedEffectIds)
        val final = KotlinGraphRenderer().render(request().copy(viewerNodeId = mask.id, quality = RenderQuality.FINAL))
        assertContentEquals(byteArrayOf(-1,-1,-1,-1, -1,-1,-1,-1), final.output.rgba)
    }
    @Test fun sharedUpstreamExecutesOnceAndDisconnectedNodesAreNotExecuted() {
        val both = graph.copy(connections = graph.connections.filterNot { it.to == mix.id && it.input == "b" } + GraphConnection(invert.id, mix.id, "b"))
        val unused = EffectInstance("unused", "unsupported")
        val result = KotlinGraphRenderer().render(request().copy(graph = both, effects = effects + unused))
        assertEquals(1, result.appliedEffectIds.count { it == invert.id })
        assertFalse("unused" in result.appliedEffectIds)
        assertContentEquals(byteArrayOf(-1,-1,-1,-1, 0,0,0,-1), result.output.rgba)
    }
    @Test fun oldChainIncludingLegacyOpacityRendersIdentically() {
        val nodes = listOf(EffectInstance("d", "ordered_dither"), EffectInstance("b", "box_blur", opacity = 0.37))
        val linear = request().copy(effects = nodes, graph = null)
        assertContentEquals(KotlinPipelineRenderer().render(linear).output.rgba,
            KotlinGraphRenderer().render(linear.copy(graph = GraphRules.chain(nodes))).output.rgba)
    }
    @Test fun maskMixUsesAssociatedAlphaWithoutLeakingInvisibleColour() {
        val pixels = PixelBuffer(1,1,byteArrayOf(-1,0,0,0))
        val half = mix.copy(parameters = mapOf("amount" to ParameterValue.Decimal(0.5)))
        val g = ProcessingGraph(connections = listOf(GraphConnection(SOURCE_NODE_ID, "mix", "a"),GraphConnection(SOURCE_NODE_ID, "mix", "b")), outputNodeId = "mix")
        val result = KotlinGraphRenderer().render(request().copy(source = pixels, effects = listOf(half), graph = g))
        assertContentEquals(byteArrayOf(0,0,0,0), result.output.rgba)
    }
    @Test fun unknownNodeWithGraphTypeNameRemainsDisabledPassthrough() {
        val unknown = EffectInstance("old", "mix", enabled = false, opaquePayload = "{legacy:true}")
        val result = KotlinGraphRenderer().render(request().copy(effects = listOf(unknown), graph = GraphRules.chain(listOf(unknown))))
        assertContentEquals(source.rgba, result.output.rgba)
    }
    @Test fun cancellationAndBranchMemoryLimitsAreEnforced() {
        assertFailsWith<RenderCancelledException> { KotlinGraphRenderer().render(request().copy(cancellationProbe = CancellationProbe { true })) }
        assertFailsWith<IllegalArgumentException> { KotlinGraphRenderer().render(request().copy(workingMemoryBudgetBytes = 1)) }
    }
}
