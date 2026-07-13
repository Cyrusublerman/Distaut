package com.cyrusublerman.distaut.recipes

import com.cyrusublerman.distaut.model.EffectInstance
import com.cyrusublerman.distaut.model.ProjectState

/** Portable transformation state. JSON encoding is the next implementation step. */
data class RecipeV2(
    val schemaVersion: Int = 2,
    val engineVersion: String,
    val sourceChecksum: String?,
    val sourceWidth: Int?,
    val sourceHeight: Int?,
    val globalSeed: Long,
    val effects: List<RecipeEffect>,
)

data class RecipeEffect(
    val instance: EffectInstance?,
    val unresolvedType: String? = null,
    val unresolvedPayload: String? = null,
) {
    init {
        require((instance != null) xor (unresolvedType != null)) {
            "Recipe effect must be resolved or unresolved"
        }
    }
}

object RecipeMapper {
    fun fromProject(project: ProjectState, engineVersion: String): RecipeV2 = RecipeV2(
        engineVersion = engineVersion,
        sourceChecksum = project.source?.checksum,
        sourceWidth = project.source?.width,
        sourceHeight = project.source?.height,
        globalSeed = project.globalSeed,
        effects = project.effects.map { RecipeEffect(instance = it) },
    )
}
