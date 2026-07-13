import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.model.EditorCommand
import com.cyrusublerman.distaut.model.EffectInstance
import com.cyrusublerman.distaut.model.ParameterValue
import com.cyrusublerman.distaut.model.ProjectHistory
import com.cyrusublerman.distaut.model.ProjectReducer
import com.cyrusublerman.distaut.model.ProjectState
import com.cyrusublerman.distaut.model.SourceAsset
import com.cyrusublerman.distaut.projects.ProjectCodec
import com.cyrusublerman.distaut.projects.ProjectDocument
import com.cyrusublerman.distaut.recipes.RecipeCodec
import com.cyrusublerman.distaut.recipes.RecipeMapper
import com.cyrusublerman.distaut.render.PixelBuffer
import com.cyrusublerman.distaut.render.RenderQuality
import com.cyrusublerman.distaut.render.RenderRequest
import com.cyrusublerman.distaut.render.ResultGenerationGate
import com.cyrusublerman.distaut.render.kotlin.KotlinPipelineRenderer

fun main() {
    testReducerAndHistory()
    testRenderKernelsAndOpacity()
    testGenerationGate()
    testRecipeRoundTripAndUnknownPreservation()
    testSiteBoyV1Import()
    testProjectRoundTrip()
    println("Distaut smoke tests passed")
}

private fun testReducerAndHistory() {
    val a = BuiltInEffects.Greyscale.instantiate("a")
    val b = BuiltInEffects.Invert.instantiate("b")
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

    project = ProjectReducer.reduce(project, EditorCommand.SetOpacity("a", 0.25))
    check(project.effects.first { it.id == "a" }.opacity == 0.25)

    project = ProjectReducer.reduce(project, EditorCommand.SetSeed(999))
    check(project.globalSeed == 999L)

    val history = ProjectHistory(ProjectState())
    history.dispatch(EditorCommand.AddEffect(a))
    check(history.canUndo)
    check(history.undo().effects.isEmpty())
    check(history.canRedo)
    check(history.redo().effects.single().id == "a")
    history.replace(ProjectState(effects = listOf(b)))
    check(!history.canUndo && history.current.effects.single().id == "b")
}

private fun testRenderKernelsAndOpacity() {
    val source = PixelBuffer(
        width = 2,
        height = 1,
        rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 128.toByte(),
        ),
    )
    val greyscale = KotlinPipelineRenderer().render(
        RenderRequest(
            generation = 3,
            sourceRevision = 1,
            source = source,
            effects = listOf(BuiltInEffects.Greyscale.instantiate("effect-1")),
            quality = RenderQuality.PREVIEW,
            globalSeed = 42,
        )
    )
    val out = greyscale.output.rgba.map { it.toInt() and 0xff }
    check(out == listOf(54, 54, 54, 255, 182, 182, 182, 128)) { out }

    val colour = PixelBuffer(1, 1, byteArrayOf(0, 50, 250.toByte(), 200.toByte()))
    val composed = KotlinPipelineRenderer().render(
        RenderRequest(
            generation = 4,
            sourceRevision = 2,
            source = colour,
            effects = listOf(
                EffectInstance("invert", "invert", opacity = 0.25),
                EffectInstance(
                    "poster",
                    "posterise",
                    parameters = mapOf("levels" to ParameterValue.Integer(2)),
                ),
            ),
            quality = RenderQuality.FINAL,
            globalSeed = 42,
        )
    )
    check(composed.output.rgba.map { it.toInt() and 0xff } == listOf(0, 0, 255, 200))
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

private fun testRecipeRoundTripAndUnknownPreservation() {
    val project = ProjectState(
        effects = listOf(
            BuiltInEffects.Posterise.instantiate("poster").copy(
                parameters = mapOf("levels" to ParameterValue.Integer(8)),
            )
        )
    )
    val recipe = RecipeMapper.fromProject(project, "test")
    val decoded = RecipeCodec.decode(
        RecipeCodec.encode(recipe),
        BuiltInEffects.registry.supportedTypes(),
    )
    check(decoded == recipe)

    val unknownJson = """
        {"schemaVersion":2,"engineVersion":"future","globalSeed":1,"effects":[
          {"id":"x","type":"future_shader","enabled":true,"parameters":{"mode":"x"},"custom":{"a":1}}
        ]}
    """.trimIndent()
    val unknown = RecipeCodec.decode(unknownJson, BuiltInEffects.registry.supportedTypes())
    check(!unknown.effects.single().enabled)
    check(unknown.effects.single().opaquePayload?.contains("custom") == true)
    check(RecipeCodec.encode(unknown).contains("future_shader"))
}

private fun testSiteBoyV1Import() {
    val source = """
        {"version":1,"globalSeed":77,"nodes":[
          {"type":"invert","enabled":true,"opacity":0.5,"params":{}},
          {"type":"quantise","enabled":true,"opacity":1,"params":{"mode":"posterise","posteriseSpace":"rgb","rLevels":4,"gLevels":4,"bLevels":4}},
          {"type":"greyscale","enabled":true,"opacity":1,"params":{"wr":0.299,"wg":0.587,"wb":0.114}}
        ]}
    """.trimIndent()
    val imported = RecipeCodec.decodeAny(source, BuiltInEffects.registry.supportedTypes())
    check(imported.recipe.globalSeed == 77L)
    check(imported.recipe.effects[0].type == "invert")
    check(imported.recipe.effects[1].type == "posterise")
    check(imported.recipe.effects[1].parameters["levels"] == ParameterValue.Integer(4))
    check(!imported.recipe.effects[2].isResolved)
    check(imported.warnings.size == 2)
}

private fun testProjectRoundTrip() {
    val project = ProjectState(
        source = SourceAsset(
            uri = "content://source/1",
            displayName = "source.jpg",
            mimeType = "image/jpeg",
            width = 4000,
            height = 3000,
            checksum = "abc",
            persistedPermission = true,
            managedCopy = false,
        ),
        effects = listOf(BuiltInEffects.Invert.instantiate("invert")),
        globalSeed = 123,
        revision = 8,
    )
    val document = ProjectDocument(
        engineVersion = "test",
        savedAtEpochMillis = 99,
        project = project,
    )
    val decoded = ProjectCodec.decode(
        ProjectCodec.encode(document),
        BuiltInEffects.registry.supportedTypes(),
    )
    check(decoded == document)
}
