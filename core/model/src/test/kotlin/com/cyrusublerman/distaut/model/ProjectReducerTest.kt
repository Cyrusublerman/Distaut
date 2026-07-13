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
        assertTrue(history.canUndo)
        history.undo()
        assertTrue(history.current.effects.first().enabled)
        history.redo()
        assertFalse(history.current.effects.first().enabled)
    }
}
