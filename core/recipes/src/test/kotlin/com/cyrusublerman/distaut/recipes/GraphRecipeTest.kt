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
        assertEquals(4, recipe.schemaVersion)
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
    @Test fun malformedGraphCannotSilentlyFallBackToAList() {
        val bad = """{"schemaVersion":4,"engineVersion":"test","effects":[],"graph":{"version":1,"outputNodeId":"gone","connections":[]}}"""
        assertFailsWith<IllegalArgumentException> { RecipeCodec.decode(bad, emptySet()) }
    }
    @Test fun olderSchemaCannotCarryAnIgnoredGraph() {
        val bad = """{"schemaVersion":3,"engineVersion":"test","effects":[],"graph":{"version":1,"outputNodeId":"@source","connections":[]}}"""
        assertFailsWith<IllegalArgumentException> { RecipeCodec.decode(bad, emptySet()) }
    }
}
