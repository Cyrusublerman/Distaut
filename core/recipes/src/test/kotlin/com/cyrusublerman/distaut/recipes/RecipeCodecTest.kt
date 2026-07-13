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
        val recipe = RecipeV2(
            engineVersion = "test",
            sourceChecksum = "abc",
            sourceWidth = 640,
            sourceHeight = 480,
            globalSeed = 99,
            effects = listOf(
                EffectInstance(
                    id = "poster",
                    type = "posterise",
                    parameters = mapOf("levels" to ParameterValue.Integer(8)),
                    opacity = 0.75,
                ),
            ),
        )
        val encoded = RecipeCodec.encode(recipe)
        val decoded = RecipeCodec.decode(encoded, setOf("posterise"))
        assertEquals(recipe, decoded)

        val withUnknown = """
            {
              "schemaVersion": 2,
              "engineVersion": "siteboy",
              "globalSeed": 42,
              "effects": [
                {"id":"future","type":"future_shader","enabled":true,"custom":{"x":1}}
              ]
            }
        """.trimIndent()
        val unknown = RecipeCodec.decode(withUnknown, setOf("greyscale"))
        val node = unknown.effects.single()
        assertFalse(node.enabled)
        assertNotNull(node.opaquePayload)
        assertTrue(node.opaquePayload!!.contains("future_shader"))
        val reencoded = RecipeCodec.encode(unknown)
        assertTrue(reencoded.contains("\"custom\""))
        val recipe = RecipeV2(engineVersion="test",sourceChecksum="abc",sourceWidth=640,sourceHeight=480,globalSeed=99,effects=listOf(EffectInstance("poster","posterise",parameters=mapOf("levels" to ParameterValue.Integer(8)),opacity=0.75)))
        assertEquals(recipe, RecipeCodec.decode(RecipeCodec.encode(recipe), setOf("posterise")))
        val unknown = RecipeCodec.decode("""{"schemaVersion":2,"engineVersion":"future","globalSeed":42,"effects":[{"id":"future","type":"future_shader","enabled":true,"custom":{"x":1}}]}""", setOf("greyscale"))
        val node = unknown.effects.single()
        assertFalse(node.enabled)
        assertNotNull(node.opaquePayload)
        assertTrue(RecipeCodec.encode(unknown).contains("\"custom\""))
    }
}
