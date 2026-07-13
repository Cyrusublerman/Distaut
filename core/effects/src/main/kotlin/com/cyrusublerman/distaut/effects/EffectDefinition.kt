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

    data class Integer(
        override val key: String,
        override val label: String,
        override val default: ParameterValue.Integer,
        val minimum: Int,
        val maximum: Int,
        val step: Int = 1,
    ) : ParameterDefinition {
        init {
            require(minimum <= default.value && default.value <= maximum)
            require(step > 0)
        }
    }

    data class Choice(
        override val key: String,
        override val label: String,
        override val default: ParameterValue.Choice,
        val choices: List<String>,
    ) : ParameterDefinition {
        init {
            require(choices.isNotEmpty())
            require(default.value in choices)
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
    private val allDefinitions = definitions.toList()
    private val byType = allDefinitions.associateBy { it.type }

    init {
        require(byType.size == allDefinitions.size) { "Effect type keys must be unique" }
    }

    fun definition(type: String): EffectDefinition? = byType[type]
    fun all(): List<EffectDefinition> = allDefinitions.sortedWith(
        compareBy<EffectDefinition> { it.category }.thenBy { it.displayName }
    )
    fun supportedTypes(): Set<String> = byType.keys
}

object BuiltInEffects {
    val Greyscale = EffectDefinition(
        type = "greyscale",
        displayName = "GREYSCALE",
        category = "COLOUR / TONE",
        algorithmVersion = "rec709-encoded-v1",
    )

    val Invert = EffectDefinition(
        type = "invert",
        displayName = "INVERT",
        category = "COLOUR / TONE",
        algorithmVersion = "encoded-rgb-v1",
    )

    val Posterise = EffectDefinition(
        type = "posterise",
        displayName = "POSTERISE",
        category = "COLOUR / TONE",
        algorithmVersion = "uniform-rgb-levels-v1",
        parameters = listOf(
            ParameterDefinition.Integer(
                key = "levels",
                label = "LEVELS",
                default = ParameterValue.Integer(6),
                minimum = 2,
                maximum = 32,
            )
        ),
    )

    val registry = EffectRegistry(listOf(Greyscale, Invert, Posterise))
}
