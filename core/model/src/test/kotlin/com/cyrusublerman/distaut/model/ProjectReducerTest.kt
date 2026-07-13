package com.cyrusublerman.distaut.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectReducerTest {
    @Test
    fun commandsMaintainOrderedStateAndHistory() {
        val first = EffectInstance("first", "greyscale")
        val second = EffectInstance("second", "greyscale")
        val history = ProjectHistory(ProjectState())
        history.dispatch(EditorCommand.AddEffect(first))
        history.dispatch(EditorCommand.AddEffect(second))
        history.dispatch(EditorCommand.MoveEffect("second", 0))
        assertEquals(listOf("second", "first"), history.current.effects.map { it.id })
        history.dispatch(EditorCommand.SetEffectEnabled("second", false))
        assertFalse(history.current.effects.first().enabled)
        history.dispatch(EditorCommand.SetOpacity("first", 0.25))
        assertEquals(0.25, history.current.effects.last().opacity)
        assertTrue(history.canUndo)
        history.undo()
        assertEquals(1.0, history.current.effects.last().opacity)
        history.redo()
        assertEquals(0.25, history.current.effects.last().opacity)
    }

    @Test
    fun replacingAProjectClearsHistoryAndRetainsSafeUnknownNodes() {
        val history = ProjectHistory(ProjectState())
        history.dispatch(EditorCommand.AddEffect(EffectInstance("a", "greyscale")))
        val replacement = ProjectState(effects = listOf(EffectInstance("unknown", "future", enabled = false, opaquePayload = "{\"type\":\"future\"}")))
        history.replace(replacement)
        assertFalse(history.canUndo)
        assertEquals(replacement, history.current)
    }
}
