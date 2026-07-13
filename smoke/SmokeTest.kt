import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.model.EditorCommand
import com.cyrusublerman.distaut.model.ParameterValue
import com.cyrusublerman.distaut.model.ProjectHistory
import com.cyrusublerman.distaut.model.ProjectReducer
import com.cyrusublerman.distaut.model.ProjectState
import com.cyrusublerman.distaut.render.PixelBuffer
import com.cyrusublerman.distaut.render.RenderQuality
import com.cyrusublerman.distaut.render.RenderRequest
import com.cyrusublerman.distaut.render.ResultGenerationGate
import com.cyrusublerman.distaut.render.kotlin.KotlinPipelineRenderer

fun main() {
    testReducerAndHistory()
    testGreyscaleKernel()
    testGenerationGate()
    println("Distaut smoke tests passed")
}

private fun testReducerAndHistory() {
    val a = BuiltInEffects.Greyscale.instantiate("a")
    val b = BuiltInEffects.Greyscale.instantiate("b")
    var project = ProjectState()
    project = ProjectReducer.reduce(project, EditorCommand.AddEffect(a))
    project = ProjectReducer.reduce(project, EditorCommand.AddEffect(b))
    check(project.effects.map { it.id } == listOf("a", "b"))

    project = ProjectReducer.reduce(project, EditorCommand.MoveEffect("b", 0))
    check(project.effects.map { it.id } == listOf("b", "a"))

    project = ProjectReducer.reduce(project, EditorCommand.SetEffectEnabled("b", false))
    check(!project.effects.first { it.id == "b" }.enabled)

    project = ProjectReducer.reduce(project, EditorCommand.SetSoloEffect("a"))
    check(project.activeEffects().map { it.id } == listOf("a"))

    project = ProjectReducer.reduce(
        project,
        EditorCommand.SetParameter("a", "amount", ParameterValue.Decimal(0.5)),
    )
    check(project.effects.first { it.id == "a" }.parameters["amount"] == ParameterValue.Decimal(0.5))

    project = ProjectReducer.reduce(project, EditorCommand.SetSeed(999))
    check(project.globalSeed == 999L)

    project = ProjectReducer.reduce(project, EditorCommand.RemoveEffect("b"))
    check(project.effects.map { it.id } == listOf("a"))

    val history = ProjectHistory(ProjectState())
    history.dispatch(EditorCommand.AddEffect(a))
    check(history.canUndo)
    check(history.undo().effects.isEmpty())
    check(history.canRedo)
    check(history.redo().effects.single().id == "a")
}

private fun testGreyscaleKernel() {
    val effect = BuiltInEffects.Greyscale.instantiate("effect-1")
    val project = ProjectReducer.reduce(ProjectState(), EditorCommand.AddEffect(effect))
    val source = PixelBuffer(
        width = 2,
        height = 1,
        rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 128.toByte(),
        ),
    )
    val result = KotlinPipelineRenderer().render(
        RenderRequest(
            generation = 3,
            sourceRevision = project.revision,
            source = source,
            effects = project.activeEffects(),
            quality = RenderQuality.PREVIEW,
            globalSeed = project.globalSeed,
        )
    )

    val out = result.output.rgba.map { it.toInt() and 0xff }
    check(out == listOf(54, 54, 54, 255, 182, 182, 182, 128)) { out }
    check(result.appliedEffectIds == listOf("effect-1"))
}

private fun testGenerationGate() {
    val source = PixelBuffer(1, 1, byteArrayOf(0, 0, 0, 255.toByte()))
    val renderer = KotlinPipelineRenderer()
    val gate = ResultGenerationGate()
    gate.request(10)
    val stale = renderer.render(RenderRequest(9, 0, source, emptyList(), RenderQuality.PREVIEW, 0))
    val current = renderer.render(RenderRequest(10, 0, source, emptyList(), RenderQuality.PREVIEW, 0))
    check(!gate.accepts(stale))
    check(gate.accepts(current))
}
