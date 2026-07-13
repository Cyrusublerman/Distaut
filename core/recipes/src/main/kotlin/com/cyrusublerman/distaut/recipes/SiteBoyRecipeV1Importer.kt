package com.cyrusublerman.distaut.recipes

import com.cyrusublerman.distaut.model.EffectInstance
import com.cyrusublerman.distaut.model.ParameterValue

data class RecipeImportResult(
    val recipe: RecipeV2,
    val warnings: List<String> = emptyList(),
)

object SiteBoyRecipeV1Importer {
    fun import(text: String): RecipeImportResult {
        val root = JsonCodec.parse(text).asObjectOrNull()
            ?: error("SiteBoy recipe root must be an object")
        val version = root["version"].asNumberOrNull()?.asDouble()?.toInt()
            ?: error("SiteBoy recipe version is required")
        require(version == 1) { "Unsupported SiteBoy recipe version: $version" }
        val warnings = mutableListOf<String>()
        val nodes = root["nodes"].asArrayOrNull()?.values
            ?: error("SiteBoy recipe nodes must be an array")
        val effects = nodes.mapIndexed { index, value ->
            importNode(value, index, warnings)
        }
        return RecipeImportResult(
            recipe = RecipeV2(
data class RecipeImportResult(val recipe: RecipeV2, val warnings: List<String> = emptyList())

object SiteBoyRecipeV1Importer {
    fun import(text: String): RecipeImportResult {
        val root = JsonCodec.parse(text).asObjectOrNull() ?: error("SiteBoy recipe root must be an object")
        val version = root["version"].asNumberOrNull()?.asIntExact() ?: error("SiteBoy recipe version is required")
        require(version == 1)
        val warnings = mutableListOf<String>()
        val nodes = root["nodes"].asArrayOrNull()?.values ?: error("SiteBoy recipe nodes must be an array")
        val effects = nodes.mapIndexed { index, value -> importNode(value, index, warnings) }
        return RecipeImportResult(
            RecipeV2(
                engineVersion = "siteboy-recipe-v1",
                sourceChecksum = null,
                sourceWidth = null,
                sourceHeight = null,
                globalSeed = root["globalSeed"].asNumberOrNull()?.asDouble()?.toLong() ?: 42L,
                effects = effects,
            ),
            warnings = warnings,
        )
    }

    private fun importNode(
        value: JsonValue,
        index: Int,
        warnings: MutableList<String>,
    ): EffectInstance {
        val node = value.asObjectOrNull() ?: return unresolved(
            value = value,
            index = index,
            type = "unknown",
            warning = "Node $index was not an object and was retained unresolved.",
            warnings = warnings,
        )
                globalSeed = root["globalSeed"].asNumberOrNull()?.asLongExact() ?: 42L,
                effects = effects,
            ),
            warnings,
        )
    }

    private fun importNode(value: JsonValue, index: Int, warnings: MutableList<String>): EffectInstance {
        val node = value.asObjectOrNull() ?: return unresolved(value, index, "unknown", "Node $index was not an object and was retained unresolved.", warnings)
        val type = node["type"].asStringOrNull() ?: "unknown"
        val id = "v1-$index-${type.replace(Regex("[^A-Za-z0-9_-]"), "_")}"
        val enabled = node["enabled"].asBooleanOrNull() ?: true
        val opacity = node["opacity"].asNumberOrNull()?.asDouble()?.coerceIn(0.0, 1.0) ?: 1.0
        val params = node["params"].asObjectOrNull()

        return when (type) {
            "invert" -> EffectInstance(
                id = id,
                type = "invert",
                enabled = enabled,
                opacity = opacity,
            )
            "quantise" -> importQuantise(node, params, id, enabled, opacity, index, warnings)
            "greyscale" -> unresolved(
                value = node,
                index = index,
                type = type,
                warning = "Node $index greyscale uses SiteBoy channel weights that Distaut does not yet model; retained unresolved.",
                warnings = warnings,
            )
            else -> unresolved(
                value = node,
                index = index,
                type = type,
                warning = "Node $index '$type' is not yet supported and was retained unresolved.",
                warnings = warnings,
            )
        }
    }

    private fun importQuantise(
        node: JsonValue.Object,
        params: JsonValue.Object?,
        id: String,
        enabled: Boolean,
        opacity: Double,
        index: Int,
        warnings: MutableList<String>,
    ): EffectInstance {
        val mode = params?.get("mode").asStringOrNull()
        val space = params?.get("posteriseSpace").asStringOrNull() ?: "rgb"
        val r = params?.get("rLevels").asNumberOrNull()?.asDouble()?.toInt()
        val g = params?.get("gLevels").asNumberOrNull()?.asDouble()?.toInt()
        val b = params?.get("bLevels").asNumberOrNull()?.asDouble()?.toInt()
        if (mode == "posterise" && space == "rgb" && r != null && r == g && g == b && r in 2..32) {
            warnings += "Node $index quantise/posterise was mapped to Distaut posterise with $r uniform RGB levels."
            return EffectInstance(
                id = id,
                type = "posterise",
                enabled = enabled,
                opacity = opacity,
                parameters = mapOf("levels" to ParameterValue.Integer(r)),
            )
        }
        return unresolved(
            value = node,
            index = index,
            type = "quantise",
            warning = "Node $index quantise mode cannot be represented exactly and was retained unresolved.",
            warnings = warnings,
        )
    }

    private fun unresolved(
        value: JsonValue,
        index: Int,
        type: String,
        warning: String,
        warnings: MutableList<String>,
    ): EffectInstance {
        return when (type) {
            "invert" -> EffectInstance(id, "invert", enabled = enabled, opacity = opacity)
            "quantise" -> importQuantise(node, params, id, enabled, opacity, index, warnings)
            "greyscale" -> unresolved(node, index, type, "Node $index greyscale uses SiteBoy channel weights that Distaut does not yet model; retained unresolved.", warnings)
            else -> unresolved(node, index, type, "Node $index '$type' is not yet supported and was retained unresolved.", warnings)
        }
    }

    private fun importQuantise(node: JsonValue.Object, params: JsonValue.Object?, id: String, enabled: Boolean, opacity: Double, index: Int, warnings: MutableList<String>): EffectInstance {
        val mode = params?.get("mode").asStringOrNull()
        val space = params?.get("posteriseSpace").asStringOrNull() ?: "rgb"
        val r = params?.get("rLevels").asNumberOrNull()?.asIntExact()
        val g = params?.get("gLevels").asNumberOrNull()?.asIntExact()
        val b = params?.get("bLevels").asNumberOrNull()?.asIntExact()
        if (mode == "posterise" && space == "rgb" && r != null && r == g && g == b && r in 2..32) {
            warnings += "Node $index quantise/posterise was mapped to Distaut posterise with $r uniform RGB levels."
            return EffectInstance(id, "posterise", enabled = enabled, opacity = opacity, parameters = mapOf("levels" to ParameterValue.Integer(r)))
        }
        return unresolved(node, index, "quantise", "Node $index quantise mode cannot be represented exactly and was retained unresolved.", warnings)
    }

    private fun unresolved(value: JsonValue, index: Int, type: String, warning: String, warnings: MutableList<String>): EffectInstance {
        warnings += warning
        return EffectInstance(
            id = "v1-$index-${type.replace(Regex("[^A-Za-z0-9_-]"), "_")}",
            type = type,
            enabled = false,
            opaquePayload = JsonCodec.stringify(value),
        )
    }
}
