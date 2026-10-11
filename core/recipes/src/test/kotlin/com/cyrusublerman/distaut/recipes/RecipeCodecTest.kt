package com.cyrusublerman.distaut.recipes

import com.cyrusublerman.distaut.model.EffectInstance
import com.cyrusublerman.distaut.model.ParameterValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipeCodecTest {
    @Test
    fun roundTripsResolvedParametersAndPreservesUnknownNodes() {
        val recipe = RecipeV2(engineVersion="test",sourceChecksum="abc",sourceWidth=640,sourceHeight=480,globalSeed=99,effects=listOf(EffectInstance("poster","posterise",parameters=mapOf("levels" to ParameterValue.Integer(8)),opacity=0.75)))
        assertEquals(recipe, RecipeCodec.decode(RecipeCodec.encode(recipe), setOf("posterise")))
        val unknown = RecipeCodec.decode("""{"schemaVersion":2,"engineVersion":"future","globalSeed":42,"effects":[{"id":"future","type":"future_shader","enabled":true,"custom":{"x":1}}]}""", setOf("greyscale"))
        val node = unknown.effects.single()
        assertFalse(node.enabled)
        assertNotNull(node.opaquePayload)
        assertEquals(node, RecipeCodec.decode(RecipeCodec.encode(unknown), setOf("greyscale")).effects.single())
    }
}

class UnresolvedRegressionTest {
    @kotlin.test.Test fun v1GreyscaleSurvivesRepeatedSaveAndReopenWithoutResolution() {
        val source="""{"version":1,"nodes":[{"type":"greyscale","enabled":true,"params":{"r":1,"g":0,"b":0}}]}"""
        var recipe=SiteBoyRecipeV1Importer.import(source).recipe
        val original=recipe.effects.single()
        repeat(3) {
            recipe=RecipeCodec.decode(RecipeCodec.encode(recipe),setOf("greyscale"))
            kotlin.test.assertEquals(original,recipe.effects.single())
            kotlin.test.assertFalse(recipe.effects.single().enabled)
        }
    }
    @kotlin.test.Test fun previousRawV1PayloadCannotResolveByTypeCollision() {
        val source="""{"schemaVersion":2,"engineVersion":"old","effects":[{"type":"greyscale","enabled":true,"params":{"r":1}}]}"""
        val node=RecipeCodec.decode(source,setOf("greyscale")).effects.single()
        kotlin.test.assertFalse(node.enabled)
        kotlin.test.assertNotNull(node.opaquePayload)
    }
    @kotlin.test.Test fun algorithmAndCompositionVersionsRoundTrip() {
        val source="""{"schemaVersion":3,"engineVersion":"test","effects":[{"id":"d","type":"ordered_dither","algorithmVersion":"bayer4-rgb-v2","compositionVersion":2}]}"""
        val recipe=RecipeCodec.decode(source,setOf("ordered_dither"))
        kotlin.test.assertEquals(recipe,RecipeCodec.decode(RecipeCodec.encode(recipe),setOf("ordered_dither")))
    }
}
