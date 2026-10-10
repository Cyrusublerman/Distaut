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

class HistoryRegressionTest {
    @kotlin.test.Test fun oneGestureIsOneUndoStepAndKeepsSourceIdentity() {
        val a=SourceAsset("file:/a",width=2,height=2)
        val b=SourceAsset("file:/b",width=2,height=2)
        val history=ProjectHistory(ProjectState(source=a,effects=listOf(EffectInstance("e","invert"))))
        history.dispatch(EditorCommand.SetSource(b))
        history.beginTransaction()
        repeat(100) { history.dispatch(EditorCommand.SetOpacity("e",it/100.0)) }
        history.endTransaction()
        kotlin.test.assertEquals(1.0,history.undo().effects.single().opacity)
        kotlin.test.assertEquals(a,history.undo().source)
        kotlin.test.assertEquals(b,history.redo().source)
        kotlin.test.assertEquals(setOf(a,b),history.retainedSources())
    }
    @kotlin.test.Test fun previewTargetDoesNotChangeExportEffects() {
        val a=EffectInstance("a","invert")
        val b=EffectInstance("b","greyscale")
        val state=ProjectState(effects=listOf(a,b),soloEffectId="a")
        kotlin.test.assertEquals(listOf(a),state.activeEffects())
        kotlin.test.assertEquals(listOf(a,b),state.finalEffects())
    }
}
