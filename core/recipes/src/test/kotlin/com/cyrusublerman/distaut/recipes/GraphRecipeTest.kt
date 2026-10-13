package com.cyrusublerman.distaut.recipes

import com.cyrusublerman.distaut.model.*
import kotlin.test.*

class GraphRecipeTest {
    @Test fun connectionsPositionsOutputAndUnknownPayloadSurviveRoundTrip() {
        val a = EffectInstance("a", "invert", algorithmVersion = "encoded-rgb-v1")
        val b = EffectInstance("b", "future", enabled = false, opaquePayload = "{\"type\":\"future\"}")
        val graph = GraphRules.chain(listOf(a, b)).copy(positions = mapOf(a.id to NodePosition(-32f, 900f)))
        val state = ProjectState(effects = listOf(a, b), graph = graph)
        val recipe = RecipeMapper.fromProject(state, "test")
        assertEquals(5, recipe.schemaVersion)
        assertEquals(recipe, RecipeCodec.decode(RecipeCodec.encode(recipe), setOf("invert")))
    }
    @Test fun legacyRecipeMigratesToConnectedChainWithoutVersionChanges() {
        val legacy = RecipeCodec.decode("""{"schemaVersion":2,"engineVersion":"old","effects":[{"id":"a","type":"invert"},{"id":"b","type":"box_blur"}]}""", setOf("invert", "box_blur"))
        val state = RecipeMapper.applyToProject(legacy, ProjectState())
        assertEquals("a", state.graph!!.upstream("b", "image"))
        assertEquals("b", state.graph!!.outputNodeId)
        assertNull(state.effects[0].algorithmVersion)
        assertEquals(1, state.effects[0].compositionVersion)
    }
    @Test fun multiSourceRecipeRetainsSlotsAndNamedOutputWithoutEmbeddingImages() {
        val graph = ProcessingGraph(sources = ProcessingGraph().sources + ("b" to GraphSource("asset-b", "Second")),
            outputs = mapOf("other" to GraphOutput("Other", PortRef("b"))), selectedOutputId = "other")
        val state = ProjectState(graph = graph, assets = mapOf("asset-b" to SourceAsset("file:/private/photo", width = 9, height = 3)))
        val encoded = RecipeCodec.encode(RecipeMapper.fromProject(state, "test"))
        assertFalse(encoded.contains("file:/private/photo"))
        val recipe = RecipeCodec.decode(encoded, emptySet())
        assertEquals(graph, recipe.graph)
        val applied = RecipeMapper.applyToProject(recipe, ProjectState())
        assertNull(applied.sourceAsset("b"))
        assertEquals(graph, applied.graph)
    }
    @Test fun graphVersionOneMigratesToAnExplicitSourceAndOutputPort() {
        val old = """{"schemaVersion":4,"engineVersion":"old","effects":[{"id":"a","type":"invert"}],"graph":{"version":1,"outputNodeId":"a","connections":[{"from":"@source","to":"a","input":"image"}]}}"""
        val graph = RecipeCodec.decode(old, setOf("invert")).graph!!
        assertEquals(2, graph.version)
        assertEquals(PRIMARY_ASSET_ID, graph.sources.getValue(SOURCE_NODE_ID).assetId)
        assertEquals(PortRef(SOURCE_NODE_ID), graph.connection("a", "image")!!.source)
        assertEquals(PortRef("a"), graph.exportEndpoint())
    }
    @Test fun malformedGraphCannotSilentlyFallBackToAList() {
        val bad = """{"schemaVersion":4,"engineVersion":"test","effects":[],"graph":{"version":1,"outputNodeId":"gone","connections":[]}}"""
        assertFailsWith<IllegalArgumentException> { RecipeCodec.decode(bad, emptySet()) }
    }
    @Test fun olderSchemaCannotCarryAnIgnoredGraph() {
        val bad = """{"schemaVersion":3,"engineVersion":"test","effects":[],"graph":{"version":1,"outputNodeId":"@source","connections":[]}}"""
        assertFailsWith<IllegalArgumentException> { RecipeCodec.decode(bad, emptySet()) }
    }
}
