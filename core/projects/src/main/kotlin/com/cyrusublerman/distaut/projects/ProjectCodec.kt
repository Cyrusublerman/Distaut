package com.cyrusublerman.distaut.projects

import com.cyrusublerman.distaut.model.*
import com.cyrusublerman.distaut.model.SourceAsset
import com.cyrusublerman.distaut.recipes.GraphJsonCodec
import com.cyrusublerman.distaut.recipes.EffectJsonCodec
import com.cyrusublerman.distaut.recipes.JsonCodec
import com.cyrusublerman.distaut.recipes.JsonValue
import com.cyrusublerman.distaut.recipes.asArrayOrNull
import com.cyrusublerman.distaut.recipes.asBooleanOrNull
import com.cyrusublerman.distaut.recipes.asNumberOrNull
import com.cyrusublerman.distaut.recipes.asObjectOrNull
import com.cyrusublerman.distaut.recipes.asStringOrNull

data class ProjectDocument(
    val schemaVersion: Int = 4,
    val engineVersion: String,
    val savedAtEpochMillis: Long,
    val project: ProjectState,
) { init {
    require(project.graph == null || schemaVersion >= 3) { "Graph projects require schema 3" }
    require(schemaVersion >= 4 || (project.assets.isEmpty() && project.processingGraph().sources == ProcessingGraph().sources && project.processingGraph().outputs.isEmpty())) { "Multiple assets and outputs require project schema 4" }
} }

object ProjectCodec {
    fun encode(document: ProjectDocument, pretty: Boolean = true): String {
        val source = document.project.source?.let(::encodeSource) ?: JsonValue.NullValue
        val project = JsonValue.Object(
            "schemaVersion" to JsonValue.NumberValue(document.project.schemaVersion.toString()),
            "source" to (if (document.schemaVersion < 4) source else JsonValue.NullValue),
            "assets" to JsonValue.Object(LinkedHashMap(document.project.allAssets().mapValues { encodeSource(it.value) })),
            "effects" to JsonValue.Array(document.project.effects.map(EffectJsonCodec::encode)),
            "graph" to GraphJsonCodec.encode(document.project.graph),
            "soloEffectId" to document.project.soloEffectId.jsonStringOrNull(),
            "globalSeed" to JsonValue.NumberValue(document.project.globalSeed.toString()),
            "revision" to JsonValue.NumberValue(document.project.revision.toString()),
        )
        return JsonCodec.stringify(
            JsonValue.Object(
                "schemaVersion" to JsonValue.NumberValue(document.schemaVersion.toString()),
                "engineVersion" to JsonValue.StringValue(document.engineVersion),
                "savedAtEpochMillis" to JsonValue.NumberValue(document.savedAtEpochMillis.toString()),
                "project" to project,
            ),
            pretty,
        )
    }

    fun decode(text: String, supportedEffectTypes: Set<String>): ProjectDocument {
        val root = JsonCodec.parse(text).asObjectOrNull()
            ?: error("Project root must be a JSON object")
        val schema = root["schemaVersion"].asNumberOrNull()?.asIntExact()
            ?: error("Project schemaVersion is required")
        require(schema in 1..4) { "Unsupported project schema: $schema" }
        val projectObject = root["project"].asObjectOrNull()
            ?: error("Project state is required")
        val effects = projectObject["effects"].asArrayOrNull()?.values
            ?.mapIndexed { index, node -> EffectJsonCodec.decode(node, index, supportedEffectTypes) }
            ?: emptyList()
        val solo = projectObject["soloEffectId"].asStringOrNull()
            ?.takeIf { id -> effects.any { it.id == id } }
        val assets = projectObject["assets"].asObjectOrNull()?.values?.mapValues { (_, value) ->
            decodeSource(value.asObjectOrNull() ?: error("Asset must be an object"))
        }.orEmpty()
        require(assets.size <= GraphRules.MAX_SOURCES) { "Too many source assets" }
        val project = ProjectState(
            schemaVersion = projectObject["schemaVersion"].asNumberOrNull()?.asIntExact() ?: 1,
            source = if (schema >= 4) assets[PRIMARY_ASSET_ID] else projectObject["source"].asObjectOrNull()?.let(::decodeSource),
            assets = assets - PRIMARY_ASSET_ID,
            effects = effects,
            graph = GraphJsonCodec.decode(projectObject["graph"], effects),
            soloEffectId = solo,
            globalSeed = projectObject["globalSeed"].asNumberOrNull()?.asLongExact() ?: 42L,
            revision = projectObject["revision"].asNumberOrNull()?.asLongExact() ?: 0L,
        )
        require(project.assets.keys.all { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }) { "Invalid asset ID" }
        return ProjectDocument(
            schemaVersion = schema,
            engineVersion = root["engineVersion"].asStringOrNull() ?: "unknown",
            savedAtEpochMillis = root["savedAtEpochMillis"].asNumberOrNull()?.asLongExact() ?: 0L,
            project = project,
        )
    }

    private fun encodeSource(source: SourceAsset): JsonValue.Object = JsonValue.Object(
        "uri" to JsonValue.StringValue(source.uri),
        "displayName" to source.displayName.jsonStringOrNull(),
        "mimeType" to source.mimeType.jsonStringOrNull(),
        "width" to JsonValue.NumberValue(source.width.toString()),
        "height" to JsonValue.NumberValue(source.height.toString()),
        "checksum" to source.checksum.jsonStringOrNull(),
        "persistedPermission" to JsonValue.BooleanValue(source.persistedPermission),
        "managedCopy" to JsonValue.BooleanValue(source.managedCopy),
    )

    private fun decodeSource(source: JsonValue.Object): SourceAsset {
        val uri = source["uri"].asStringOrNull() ?: error("Source URI is required")
        val width = source["width"].asNumberOrNull()?.asIntExact()
            ?: error("Source width is required")
        val height = source["height"].asNumberOrNull()?.asIntExact()
            ?: error("Source height is required")
        return SourceAsset(
            uri = uri,
            displayName = source["displayName"].asStringOrNull(),
            mimeType = source["mimeType"].asStringOrNull(),
            width = width,
            height = height,
            checksum = source["checksum"].asStringOrNull(),
            persistedPermission = source["persistedPermission"].asBooleanOrNull() ?: false,
            managedCopy = source["managedCopy"].asBooleanOrNull() ?: false,
        )
    }
}

private fun String?.jsonStringOrNull(): JsonValue =
    this?.let(JsonValue::StringValue) ?: JsonValue.NullValue
