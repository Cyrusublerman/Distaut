package com.cyrusublerman.distaut.model

import kotlin.test.*

class GraphModelTest {
    private val image = EffectInstance("image", "invert")
    private val second = EffectInstance("second", "posterise")
    private val mask = EffectInstance("mask", "edge_mask")
    @Test fun legacyChainPreservesIdsOrderAndDisabledNodes() {
        val effects = listOf(image, second.copy(enabled = false), EffectInstance("unknown", "mix", enabled = false, opaquePayload = "{}"))
        val graph = GraphRules.chain(effects)
        GraphRules.validate(effects, graph)
        assertEquals(listOf("image", "second", "unknown"), GraphRules.executionOrder(effects, graph, graph.outputNodeId))
        assertEquals("unknown", graph.outputNodeId)
        assertEquals("second", graph.upstream("unknown", "image"))
    }
    @Test fun connectingReplacesOneInputAndRejectsCyclesAndWrongTypes() {
        val effects = listOf(image, second, mask)
        var state = ProjectState(effects = effects, graph = ProcessingGraph(connections = listOf(
            GraphConnection(SOURCE_NODE_ID, image.id, "image"), GraphConnection(image.id, second.id, "image"),
            GraphConnection(SOURCE_NODE_ID, mask.id, "image"))))
        assertFailsWith<IllegalArgumentException> { ProjectReducer.reduce(state, EditorCommand.Connect(second.id, image.id, "image")) }
        assertFailsWith<IllegalArgumentException> { ProjectReducer.reduce(state, EditorCommand.Connect(mask.id, image.id, "image")) }
        state = ProjectReducer.reduce(state, EditorCommand.Connect(SOURCE_NODE_ID, second.id, "image"))
        assertEquals(1, state.graph!!.connections.count { it.to == second.id })
        assertEquals(SOURCE_NODE_ID, state.graph!!.upstream(second.id, "image"))
    }
    @Test fun graphEditingAllowsUnconnectedNodesButRenderingRejectsMissingInputs() {
        val state = ProjectState(effects = listOf(image), graph = ProcessingGraph(outputNodeId = image.id))
        GraphRules.validate(state.effects, state.graph!!)
        assertFailsWith<IllegalArgumentException> { GraphRules.executionOrder(state.effects, state.graph!!, image.id) }
        assertEquals(emptyList(), GraphRules.executionOrder(state.effects, state.graph!!, SOURCE_NODE_ID))
    }
    @Test fun removingANodeDisconnectsItsConsumersAndUndoRestoresTopology() {
        val initial = ProjectState(effects = listOf(image, second), graph = GraphRules.chain(listOf(image, second)))
        val history = ProjectHistory(initial)
        history.dispatch(EditorCommand.RemoveEffect(image.id))
        assertTrue(history.current.graph!!.connections.none { it.from == image.id || it.to == image.id })
        assertEquals(initial, history.undo())
        history.dispatch(EditorCommand.RemoveEffect(second.id))
        assertEquals(SOURCE_NODE_ID, history.current.graph!!.outputNodeId)
    }
    @Test fun masksCannotBecomeFinalImageOutputs() {
        val effects = listOf(mask)
        assertFailsWith<IllegalArgumentException> { GraphRules.validate(effects, ProcessingGraph(outputNodeId = mask.id)) }
    }
    @Test fun maximumLegacyChainFitsWithinCanvasCoordinateLimits() {
        val effects = (1..GraphRules.MAX_NODES).map { EffectInstance("node-$it", "invert") }
        val graph = GraphRules.chain(effects)
        GraphRules.validate(effects, graph)
        assertEquals(effects.map { it.id }, GraphRules.executionOrder(effects, graph, graph.outputNodeId))
        assertEquals(GraphRules.MAX_NODES + 1, graph.positions.values.distinct().size)
    }
    @Test fun positionsAndConnectionsAreUndoableWithoutChangingEffectOrder() {
        val initial = ProjectState(effects = listOf(image, second), graph = GraphRules.chain(listOf(image, second)))
        val history = ProjectHistory(initial)
        history.dispatch(EditorCommand.SetNodePosition(image.id, NodePosition(10f, 100f)))
        assertEquals(initial.effects, history.current.effects)
        assertEquals(initial, history.undo())
    }
}
