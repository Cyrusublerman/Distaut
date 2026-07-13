package com.cyrusublerman.distaut.effects

import com.cyrusublerman.distaut.model.EffectInstance
import com.cyrusublerman.distaut.model.ParameterValue

sealed interface ParameterDefinition {
    val key: String
    val label: String
    val default: ParameterValue

    data class Decimal(
        override val key: String,
        override val label: String,
        override val default: ParameterValue.Decimal,
        val minimum: Double,
        val maximum: Double,
        val step: Double,
        val unit: String? = null,
    ) : ParameterDefinition {
        init {
            require(minimum <= default.value && default.value <= maximum)
            require(step > 0.0)
        }
    }

    data class Toggle(
        override val key: String,
        override val label: String,
        override val default: ParameterValue.Toggle,
    ) : ParameterDefinition
}

data class EffectDefinition(
    val type: String,
    val displayName: String,
    val category: String,
    val algorithmVersion: String,
    val parameters: List<ParameterDefinition> = emptyList(),
) {
    init {
        require(type.matches(Regex("[a-z][a-z0-9_]*")))
        require(displayName.isNotBlank())
        require(parameters.map { it.key }.distinct().size == parameters.size)
    }

    fun instantiate(id: String): EffectInstance = EffectInstance(
        id = id,
        type = type,
        parameters = parameters.associate { it.key to it.default },
    )
}

class EffectRegistry(definitions: Iterable<EffectDefinition>) {
    private val byType = definitions.associateBy { it.type }

    init {
        val list = definitions.toList()
        require(byType.size == list.size) { "Effect type keys must be unique" }
    }

    fun definition(type: String): EffectDefinition? = byType[type]
    fun all(): List<EffectDefinition> = byType.values.sortedWith(
        compareBy<EffectDefinition> { it.category }.thenBy { it.displayName }
    )
}

object BuiltInEffects {
    val Greyscale = EffectDefinition(
        type = "greyscale",
        displayName = "GREYSCALE",
        category = "COLOUR / TONE",
        algorithmVersion = "rec709-encoded-v1",
    )

    val registry = EffectRegistry(listOf(Greyscale))
}
