package com.cyrusublerman.distaut.model

import java.util.ArrayDeque

sealed interface ParameterValue {
    data class Decimal(val value: Double) : ParameterValue
    data class Integer(val value: Int) : ParameterValue
    data class Choice(val value: String) : ParameterValue
    data class Toggle(val value: Boolean) : ParameterValue
    data class OpaqueJson(val json: String) : ParameterValue
}

data class SourceAsset(
    val uri: String,
    val displayName: String? = null,
    val mimeType: String? = null,
    val width: Int,
    val height: Int,
    val checksum: String? = null,
    val persistedPermission: Boolean = false,
    val managedCopy: Boolean = false,
) {
    init {
        require(uri.isNotBlank())
        require(width > 0 && height > 0)
    }
}

data class EffectInstance(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    val parameters: Map<String, ParameterValue> = emptyMap(),
    val opacity: Double = 1.0,
    val blendMode: String = "normal",
    /** Canonical JSON for an effect node that this build cannot resolve. */
    val opaquePayload: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(type.isNotBlank())
        require(opacity in 0.0..1.0)
        require(blendMode.isNotBlank())
    }

    val isResolved: Boolean get() = opaquePayload == null
}

data class ProjectState(
    val schemaVersion: Int = 1,
    val source: SourceAsset? = null,
    val effects: List<EffectInstance> = emptyList(),
    val soloEffectId: String? = null,
    val globalSeed: Long = 42L,
    val revision: Long = 0L,
) {
    init {
        require(schemaVersion >= 1)
        require(effects.map { it.id }.distinct().size == effects.size) {
            "Effect instance IDs must be unique"
        }
        require(soloEffectId == null || effects.any { it.id == soloEffectId }) {
            "Solo effect must exist in the stack"
        }
    }

    /** Solo means render through this node rather than bypassing its upstream inputs. */
    fun activeEffects(): List<EffectInstance> {
        val enabled = effects.filter { it.enabled }
        val solo = soloEffectId ?: return enabled
        val index = enabled.indexOfFirst { it.id == solo }
        return if (index < 0) enabled else enabled.take(index + 1)
    }
}

sealed interface EditorCommand {
    data class SetSource(val source: SourceAsset?) : EditorCommand
    data class AddEffect(val effect: EffectInstance, val index: Int? = null) : EditorCommand
    data class RemoveEffect(val effectId: String) : EditorCommand
    data class MoveEffect(val effectId: String, val toIndex: Int) : EditorCommand
    data class SetEffectEnabled(val effectId: String, val enabled: Boolean) : EditorCommand
    data class SetSoloEffect(val effectId: String?) : EditorCommand
    data class SetParameter(val effectId: String, val key: String, val value: ParameterValue) : EditorCommand
    data class SetOpacity(val effectId: String, val opacity: Double) : EditorCommand
    data class SetSeed(val seed: Long) : EditorCommand
}

object ProjectReducer {
    fun reduce(state: ProjectState, command: EditorCommand): ProjectState {
        val next = when (command) {
            is EditorCommand.SetSource -> state.copy(source = command.source)
            is EditorCommand.AddEffect -> {
                require(state.effects.none { it.id == command.effect.id }) {
                    "Effect ID already exists: ${command.effect.id}"
                }
                val index = (command.index ?: state.effects.size).coerceIn(0, state.effects.size)
                val effects = state.effects.toMutableList().apply { add(index, command.effect) }
                state.copy(effects = effects)
            }
            is EditorCommand.RemoveEffect -> state.copy(
                effects = state.effects.filterNot { it.id == command.effectId },
                soloEffectId = state.soloEffectId.takeUnless { it == command.effectId },
            )
            is EditorCommand.MoveEffect -> {
                val from = state.effects.indexOfFirst { it.id == command.effectId }
                if (from < 0) state else {
                    val effects = state.effects.toMutableList()
                    val effect = effects.removeAt(from)
                    effects.add(command.toIndex.coerceIn(0, effects.size), effect)
                    state.copy(effects = effects)
                }
            }
            is EditorCommand.SetEffectEnabled -> state.copy(
                effects = state.effects.map {
                    if (it.id == command.effectId) it.copy(enabled = command.enabled) else it
                },
                soloEffectId = state.soloEffectId.takeUnless {
                    it == command.effectId && !command.enabled
                },
            )
            is EditorCommand.SetSoloEffect -> {
                require(command.effectId == null || state.effects.any { it.id == command.effectId })
                state.copy(soloEffectId = command.effectId)
            }
            is EditorCommand.SetParameter -> state.copy(
                effects = state.effects.map {
                    if (it.id == command.effectId) {
                        it.copy(parameters = it.parameters + (command.key to command.value))
                    } else it
                },
            )
            is EditorCommand.SetOpacity -> state.copy(
                effects = state.effects.map {
                    if (it.id == command.effectId) it.copy(opacity = command.opacity.coerceIn(0.0, 1.0)) else it
                },
            )
            is EditorCommand.SetSeed -> state.copy(globalSeed = command.seed)
        }
        return if (next == state) state else next.copy(revision = state.revision + 1)
    }
}

class ProjectHistory(initial: ProjectState, private val capacity: Int = 50) {
    init { require(capacity > 0) }
    private val past = ArrayDeque<ProjectState>()
    private val future = ArrayDeque<ProjectState>()
    var current: ProjectState = initial
        private set
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun dispatch(command: EditorCommand): ProjectState {
        val next = ProjectReducer.reduce(current, command)
        if (next == current) return current
        past.addLast(current)
        while (past.size > capacity) past.removeFirst()
        future.clear()
        current = next
        return current
    }

    fun replace(project: ProjectState, clearHistory: Boolean = true): ProjectState {
        if (clearHistory) {
            past.clear()
            future.clear()
        } else if (project != current) {
            past.addLast(current)
            while (past.size > capacity) past.removeFirst()
            future.clear()
        }
        current = project
        return current
    }

    fun undo(): ProjectState {
        if (past.isEmpty()) return current
        future.addLast(current)
        current = past.removeLast()
        return current
    }

    fun redo(): ProjectState {
        if (future.isEmpty()) return current
        past.addLast(current)
        current = future.removeLast()
        return current
    }
}
