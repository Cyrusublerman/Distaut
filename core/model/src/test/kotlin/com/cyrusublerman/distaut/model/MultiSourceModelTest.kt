package com.cyrusublerman.distaut.model

import kotlin.test.*

class MultiSourceModelTest {
    private val asset = SourceAsset("file:/image", width = 8, height = 4)
    @Test fun sourceRemovalClearsEdgesAndOutputsAndUndoRetainsAsset() {
        val history = ProjectHistory(ProjectState(source = asset, graph = ProcessingGraph()))
        history.dispatch(EditorCommand.AddSource("second", "asset2", asset.copy(uri = "file:/second"), "Second"))
        history.dispatch(EditorCommand.SaveOutput("alternate", "Alternate", PortRef("second")))
        val before = history.current
        history.dispatch(EditorCommand.RemoveSource("second"))
        assertEquals(DEFAULT_OUTPUT_ID, history.current.processingGraph().selectedOutputId)
        assertTrue(history.current.assets.isEmpty())
        assertEquals(2, history.retainedSources().size)
        history.undo()
        assertEquals(before, history.current)
        assertEquals(PortRef("second"), history.current.processingGraph().exportEndpoint())
    }
    @Test fun explicitPortsAndUniqueOutputNamesAreValidated() {
        val state = ProjectState(graph = ProcessingGraph())
        assertFails { ProjectReducer.reduce(state, EditorCommand.SaveOutput("bad", "Bad", PortRef(SOURCE_NODE_ID, "missing"))) }
        assertFails { ProjectReducer.reduce(state, EditorCommand.SaveOutput("bad", "final", PortRef(SOURCE_NODE_ID))) }
        val saved = ProjectReducer.reduce(state, EditorCommand.SaveOutput("raw", "Raw", PortRef(SOURCE_NODE_ID)))
        assertEquals("Raw", saved.processingGraph().exportTargets().getValue("raw").name)
        assertFails { ProjectReducer.reduce(saved, EditorCommand.AddSource("second", PRIMARY_ASSET_ID, asset, "Second")) }
    }
    @Test fun namedMaskCanBeSelectedWithoutChangingFinalImage() {
        val mask = EffectInstance("mask", "luminance_mask")
        val state = ProjectState(effects = listOf(mask), graph = ProcessingGraph(connections = listOf(GraphConnection(SOURCE_NODE_ID, "mask", "image"))))
        val saved = ProjectReducer.reduce(state, EditorCommand.SaveOutput("matte", "Matte", PortRef("mask")))
        assertEquals(SOURCE_NODE_ID, saved.processingGraph().outputNodeId)
        assertEquals("mask", saved.processingGraph().exportEndpoint().nodeId)
        assertEquals(setOf(SOURCE_NODE_ID), GraphRules.requiredSources(saved.effects, saved.processingGraph(), "mask"))
    }
}
