package com.cyrusublerman.distaut.recipes

import com.cyrusublerman.distaut.model.EffectInstance
import com.cyrusublerman.distaut.model.ParameterValue
import com.cyrusublerman.distaut.model.ProjectState
import com.cyrusublerman.distaut.model.ProcessingGraph
import com.cyrusublerman.distaut.model.processingGraph
import com.cyrusublerman.distaut.model.GraphRules

data class RecipeV2(
    val schemaVersion: Int = 5,
    val engineVersion: String,
    val sourceChecksum: String?,
    val sourceWidth: Int?,
    val sourceHeight: Int?,
    val globalSeed: Long,
    val effects: List<EffectInstance>,
    val graph: ProcessingGraph? = null,
) {
    init {
        require(schemaVersion in 2..5)
        require(engineVersion.isNotBlank())
        require(graph == null || schemaVersion >= 4) { "Graph recipes require schema 4" }
        require(schemaVersion >= 5 || graph == null || (graph.sources == ProcessingGraph().sources && graph.outputs.isEmpty())) { "Multiple sources and outputs require schema 5" }
    }
}

object RecipeMapper {
    fun fromProject(project: ProjectState, engineVersion: String): RecipeV2 = RecipeV2(
        engineVersion = engineVersion,
        sourceChecksum = project.source?.checksum,
        sourceWidth = project.source?.width,
        sourceHeight = project.source?.height,
        globalSeed = project.globalSeed,
        effects = project.effects,
        graph = project.processingGraph(),
    )

    fun applyToProject(recipe: RecipeV2, project: ProjectState): ProjectState = project.copy(
        assets = project.assets.filterKeys { id -> recipe.graph?.sources?.values?.any { it.assetId == id } == true },
        effects = recipe.effects,
        graph = recipe.graph ?: GraphRules.chain(recipe.effects),
        soloEffectId = null,
        globalSeed = recipe.globalSeed,
        revision = project.revision + 1,
    )
}

object RecipeCodec {
    fun encode(recipe: RecipeV2, pretty: Boolean = true): String {
        val source = JsonValue.Object(
            "checksum" to recipe.sourceChecksum.jsonStringOrNull(),
            "width" to recipe.sourceWidth.jsonNumberOrNull(),
            "height" to recipe.sourceHeight.jsonNumberOrNull(),
        )
        return JsonCodec.stringify(
            JsonValue.Object(
                "schemaVersion" to JsonValue.NumberValue(recipe.schemaVersion.toString()),
                "engineVersion" to JsonValue.StringValue(recipe.engineVersion),
                "source" to source,
                "globalSeed" to JsonValue.NumberValue(recipe.globalSeed.toString()),
                "effects" to JsonValue.Array(recipe.effects.map(EffectJsonCodec::encode)),
                "graph" to GraphJsonCodec.encode(recipe.graph),
            ),
            pretty,
        )
    }

    fun decodeAny(text: String, supportedEffectTypes: Set<String>): RecipeImportResult {
        val root = JsonCodec.parse(text).asObjectOrNull() ?: error("Recipe root must be a JSON object")
        return when {
            root["schemaVersion"].asNumberOrNull()?.asIntExact()?.let { it in 2..5 } == true -> RecipeImportResult(decode(text, supportedEffectTypes))
            root["version"].asNumberOrNull()?.asIntExact() == 1 -> SiteBoyRecipeV1Importer.import(text)
            else -> error("Unrecognised recipe schema")
        }
    }

    fun decode(text: String, supportedEffectTypes: Set<String>): RecipeV2 {
        val root = JsonCodec.parse(text).asObjectOrNull() ?: error("Recipe root must be a JSON object")
        val schema = root["schemaVersion"].asNumberOrNull()?.asIntExact() ?: error("Recipe schemaVersion is required")
        require(schema in 2..5)
        val source = root["source"].asObjectOrNull()
        val effects = root["effects"].asArrayOrNull()?.values
            ?.mapIndexed { index, node -> EffectJsonCodec.decode(node, index, supportedEffectTypes) }
            ?: error("Recipe effects must be an array")
        return RecipeV2(
            schemaVersion = schema,
            engineVersion = root["engineVersion"].asStringOrNull() ?: error("Recipe engineVersion is required"),
            sourceChecksum = source?.get("checksum").asStringOrNull(),
            sourceWidth = source?.get("width").asNumberOrNull()?.asIntExact(),
            sourceHeight = source?.get("height").asNumberOrNull()?.asIntExact(),
            globalSeed = root["globalSeed"].asNumberOrNull()?.asLongExact() ?: 42L,
            effects = effects,
            graph = GraphJsonCodec.decode(root["graph"], effects),
        )
    }
}

object EffectJsonCodec {
    fun encode(effect: EffectInstance): JsonValue {
        return JsonValue.Object(
            "id" to JsonValue.StringValue(effect.id),
            "type" to JsonValue.StringValue(effect.type),
            "enabled" to JsonValue.BooleanValue(effect.enabled),
            "opacity" to JsonValue.NumberValue(effect.opacity.toString()),
            "blendMode" to JsonValue.StringValue(effect.blendMode),
            "algorithmVersion" to effect.algorithmVersion.jsonStringOrNull(),
            "compositionVersion" to JsonValue.NumberValue(effect.compositionVersion.toString()),
            "resolution" to JsonValue.StringValue(if (effect.isResolved) "resolved" else "unresolved"),
            "opaquePayload" to effect.opaquePayload.jsonStringOrNull(),
            "parameters" to JsonValue.Object(LinkedHashMap(effect.parameters.mapValues { encodeParameter(it.value) })),
        )
    }

    fun decode(node: JsonValue, index: Int, supportedEffectTypes: Set<String>): EffectInstance {
        val objectNode = node.asObjectOrNull()
        if (objectNode == null) {
            return EffectInstance("unresolved-$index", "unknown", enabled = false, opaquePayload = JsonCodec.stringify(node))
        }
        val type = objectNode["type"].asStringOrNull() ?: "unknown"
        val id = objectNode["id"].asStringOrNull()?.takeIf(String::isNotBlank)
            ?: "recipe-$index-${type.replace(Regex("[^A-Za-z0-9_-]"), "_")}"
        val enabled = objectNode["enabled"].asBooleanOrNull() ?: true
        val opacity = objectNode["opacity"].asNumberOrNull()?.asDouble()?.coerceIn(0.0, 1.0) ?: 1.0
        val blendMode = objectNode["blendMode"].asStringOrNull() ?: "normal"
        val algorithm = objectNode["algorithmVersion"].asStringOrNull()
        val composition = objectNode["compositionVersion"].asNumberOrNull()?.asIntExact() ?: 1
        val payload = objectNode["opaquePayload"].asStringOrNull()
            ?: objectNode["unresolvedPayload"].asStringOrNull()
        val unresolved = payload != null || objectNode["resolution"].asStringOrNull() == "unresolved"
            || type !in supportedEffectTypes
            || (objectNode["params"] != null && objectNode["parameters"] == null)
        if (unresolved) {
            return EffectInstance(id, type, enabled = false, opacity = opacity, blendMode = blendMode,
                opaquePayload = payload ?: JsonCodec.stringify(objectNode),
                algorithmVersion = algorithm, compositionVersion = composition)
        }
        val parameters = objectNode["parameters"].asObjectOrNull()?.values?.mapValues { decodeParameter(it.value) }.orEmpty()
        return EffectInstance(id, type, enabled, parameters, opacity, blendMode,
            algorithmVersion = algorithm, compositionVersion = composition)
    }

    private fun encodeParameter(value: ParameterValue): JsonValue = when (value) {
        is ParameterValue.Decimal -> JsonValue.NumberValue(value.value.toString())
        is ParameterValue.Integer -> JsonValue.NumberValue(value.value.toString())
        is ParameterValue.Choice -> JsonValue.StringValue(value.value)
        is ParameterValue.Toggle -> JsonValue.BooleanValue(value.value)
        is ParameterValue.OpaqueJson -> runCatching { JsonCodec.parse(value.json) }.getOrElse { JsonValue.StringValue(value.json) }
    }

    private fun decodeParameter(value: JsonValue): ParameterValue = when (value) {
        is JsonValue.BooleanValue -> ParameterValue.Toggle(value.value)
        is JsonValue.StringValue -> ParameterValue.Choice(value.value)
        is JsonValue.NumberValue -> if (value.isIntegral()) {
            runCatching { ParameterValue.Integer(value.asIntExact()) }.getOrElse { ParameterValue.Decimal(value.asDouble()) }
        } else ParameterValue.Decimal(value.asDouble())
        JsonValue.NullValue, is JsonValue.Array, is JsonValue.Object -> ParameterValue.OpaqueJson(JsonCodec.stringify(value))
    }
}

private fun String?.jsonStringOrNull(): JsonValue = this?.let(JsonValue::StringValue) ?: JsonValue.NullValue
private fun Int?.jsonNumberOrNull(): JsonValue = this?.let { JsonValue.NumberValue(it.toString()) } ?: JsonValue.NullValue
