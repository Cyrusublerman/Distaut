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
    val algorithmVersion: String? = null,
    val compositionVersion: Int = 1,
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
    val graph: ProcessingGraph? = null,
    val assets: Map<String, SourceAsset> = emptyMap(),
) {
    init {
        require(PRIMARY_ASSET_ID !in assets) { "Primary asset uses the source compatibility field" }
        require(schemaVersion >= 1)
        require(effects.map { it.id }.distinct().size == effects.size) {
            "Effect instance IDs must be unique"
        }
        require(soloEffectId == null || effects.any { it.id == soloEffectId }) {
            "Solo effect must exist in the stack"
        }
    }

    /** Solo means render through this node rather than bypassing its upstream inputs. */
    fun finalEffects(): List<EffectInstance> = effects.filter { it.enabled }

    fun activeEffects(): List<EffectInstance> {
        val enabled = effects.filter { it.enabled }
        val solo = soloEffectId ?: return enabled
        val index = enabled.indexOfFirst { it.id == solo }
        return if (index < 0) enabled else enabled.take(index + 1)
    }
}

sealed interface EditorCommand {
    data class AddSource(val nodeId: String, val assetId: String, val asset: SourceAsset, val label: String) : EditorCommand
    data class ReplaceSource(val nodeId: String, val asset: SourceAsset) : EditorCommand
    data class RemoveSource(val nodeId: String) : EditorCommand
    data class SaveOutput(val id: String, val name: String, val endpoint: PortRef) : EditorCommand
    data class SelectOutput(val id: String) : EditorCommand
    data class RemoveOutput(val id: String) : EditorCommand
    data class AddGraphNode(val effect: EffectInstance, val upstream: String? = null) : EditorCommand
    data class Connect(val from: String, val to: String, val input: String, val output: String = DEFAULT_PORT_ID) : EditorCommand
    data class Disconnect(val to: String, val input: String) : EditorCommand
    data class SetGraphOutput(val nodeId: String) : EditorCommand
    data class SetNodePosition(val nodeId: String, val position: NodePosition) : EditorCommand
    data class ReplaceGraph(val effects: List<EffectInstance>, val graph: ProcessingGraph) : EditorCommand
    data class SetSource(val source: SourceAsset?) : EditorCommand
    data class AddEffect(val effect: EffectInstance, val index: Int? = null) : EditorCommand
    data class RemoveEffect(val effectId: String) : EditorCommand
    data class MoveEffect(val effectId: String, val toIndex: Int) : EditorCommand
    data class SetEffectEnabled(val effectId: String, val enabled: Boolean) : EditorCommand
    data class SetSoloEffect(val effectId: String?) : EditorCommand
    data class SetParameter(val effectId: String, val key: String, val value: ParameterValue) : EditorCommand
    data class SetOpacity(val effectId: String, val opacity: Double) : EditorCommand
    data class SetSeed(val seed: Long) : EditorCommand
    data class UpgradeEffect(val effectId: String, val algorithmVersion: String) : EditorCommand
}

object ProjectReducer {
    fun reduce(state: ProjectState, command: EditorCommand): ProjectState {
        val next = when (command) {
            is EditorCommand.AddSource -> {
                val graph = state.processingGraph()
                require(command.nodeId !in state.nodeIds() && command.assetId !in state.allAssets()) { "Source already exists" }
                val nextGraph = graph.copy(sources = graph.sources + (command.nodeId to GraphSource(command.assetId, command.label)),
                    positions = graph.positions + (command.nodeId to NodePosition(24f, 40f + graph.sources.size * 180f)))
                GraphRules.validate(state.effects, nextGraph)
                state.copy(graph = nextGraph, assets = state.assets + (command.assetId to command.asset))
            }
            is EditorCommand.ReplaceSource -> {
                val assetId = state.processingGraph().sources[command.nodeId]?.assetId ?: error("Source node missing")
                if (assetId == PRIMARY_ASSET_ID) state.copy(source = command.asset)
                else state.copy(assets = state.assets + (assetId to command.asset))
            }
            is EditorCommand.RemoveSource -> {
                require(command.nodeId != SOURCE_NODE_ID) { "The original source can be replaced; remove additional sources here" }
                val graph = state.processingGraph()
                val removed = graph.sources[command.nodeId] ?: error("Source node missing")
                val outputs = graph.outputs.filterValues { it.endpoint.nodeId != command.nodeId }
                val sources = graph.sources - command.nodeId
                state.copy(assets = if (sources.values.none { it.assetId == removed.assetId }) state.assets - removed.assetId else state.assets,
                    graph = graph.copy(sources = sources, connections = graph.connections.filterNot { it.from == command.nodeId },
                        positions = graph.positions - command.nodeId, outputs = outputs,
                        selectedOutputId = graph.selectedOutputId.takeIf { it in outputs } ?: DEFAULT_OUTPUT_ID,
                        outputNodeId = graph.outputNodeId.takeUnless { it == command.nodeId } ?: SOURCE_NODE_ID))
            }
            is EditorCommand.SaveOutput -> {
                val graph = state.processingGraph().copy(outputs = state.processingGraph().outputs +
                    (command.id to GraphOutput(command.name.trim(), command.endpoint)), selectedOutputId = command.id)
                GraphRules.validate(state.effects, graph)
                state.copy(graph = graph)
            }
            is EditorCommand.SelectOutput -> {
                val graph = state.processingGraph().copy(selectedOutputId = command.id)
                GraphRules.validate(state.effects, graph)
                state.copy(graph = graph)
            }
            is EditorCommand.RemoveOutput -> {
                require(command.id != DEFAULT_OUTPUT_ID)
                val graph = state.processingGraph()
                state.copy(graph = graph.copy(outputs = graph.outputs - command.id,
                    selectedOutputId = graph.selectedOutputId.takeUnless { it == command.id } ?: DEFAULT_OUTPUT_ID))
            }
            is EditorCommand.AddGraphNode -> {
                require(state.effects.none { it.id == command.effect.id }) { "Duplicate node ID" }
                val graph = state.processingGraph()
                val effects = state.effects + command.effect
                val upstream = command.upstream ?: graph.outputNodeId
                val input = GraphPorts.inputs(command.effect.type).entries.firstOrNull {
                    it.value == GraphRules.outputType(upstream, state.effects, graph)
                }?.key
                val position = graph.positions[upstream] ?: NodePosition(24f, 48f)
                val connected = graph.copy(
                    connections = graph.connections + listOfNotNull(input?.let { GraphConnection(upstream, command.effect.id, it) }),
                    positions = graph.positions + (command.effect.id to NodePosition((position.x + 280f).coerceAtMost(9800f),
                        (position.y + if (graph.connections.any { it.from == upstream }) 210f else 0f).coerceAtMost(9800f))),
                    outputNodeId = if (input != null && upstream == graph.outputNodeId && command.effect.type !in GraphPorts.graphTypes)
                        command.effect.id else graph.outputNodeId,
                )
                GraphRules.validate(effects, connected)
                state.copy(effects = effects, graph = connected)
            }
            is EditorCommand.Connect -> {
                val graph = state.processingGraph()
                val nextGraph = graph.copy(connections = graph.connections.filterNot { it.to == command.to && it.input == command.input } +
                    GraphConnection(command.from, command.to, command.input, command.output))
                GraphRules.validate(state.effects, nextGraph)
                state.copy(graph = nextGraph)
            }
            is EditorCommand.Disconnect -> state.copy(graph = state.processingGraph().let { graph ->
                graph.copy(connections = graph.connections.filterNot { it.to == command.to && it.input == command.input })
            })
            is EditorCommand.SetGraphOutput -> {
                val graph = state.processingGraph().copy(outputNodeId = command.nodeId, selectedOutputId = DEFAULT_OUTPUT_ID)
                GraphRules.validate(state.effects, graph)
                state.copy(graph = graph)
            }
            is EditorCommand.SetNodePosition -> {
                require(command.nodeId in state.processingGraph().sources || state.effects.any { it.id == command.nodeId })
                state.copy(graph = state.processingGraph().let { it.copy(positions = it.positions + (command.nodeId to command.position)) })
            }
            is EditorCommand.ReplaceGraph -> {
                GraphRules.validate(command.effects, command.graph)
                state.copy(effects = command.effects, graph = command.graph, soloEffectId = null)
            }
            is EditorCommand.SetSource -> state.copy(source = command.source)
            is EditorCommand.AddEffect -> {
                require(state.graph == null) { "Use graph node insertion for a connected document" }
                require(state.effects.none { it.id == command.effect.id }) {
                    "Effect ID already exists: ${command.effect.id}"
                }
                val index = (command.index ?: state.effects.size).coerceIn(0, state.effects.size)
                val effects = state.effects.toMutableList().apply { add(index, command.effect) }
                state.copy(effects = effects)
            }
            is EditorCommand.RemoveEffect -> state.copy(
                graph = state.graph?.let { graph -> graph.copy(
                    connections = graph.connections.filterNot { it.from == command.effectId || it.to == command.effectId },
                    outputNodeId = graph.outputNodeId.takeUnless { it == command.effectId } ?: SOURCE_NODE_ID,
                    positions = graph.positions - command.effectId,
                    outputs = graph.outputs.filterValues { it.endpoint.nodeId != command.effectId },
                    selectedOutputId = graph.selectedOutputId.takeUnless { graph.outputs[it]?.endpoint?.nodeId == command.effectId } ?: DEFAULT_OUTPUT_ID,
                ) },
                effects = state.effects.filterNot { it.id == command.effectId },
                soloEffectId = state.soloEffectId.takeUnless { it == command.effectId },
            )
            is EditorCommand.MoveEffect -> {
                require(state.graph == null) { "Move nodes on the graph; list order is not execution order" }
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
            is EditorCommand.UpgradeEffect -> state.copy(effects = state.effects.map {
                if (it.id == command.effectId && it.isResolved) it.copy(
                    algorithmVersion = command.algorithmVersion, compositionVersion = 2,
                ) else it
            })
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
    private var transactionStart: ProjectState? = null
    val canUndo: Boolean get() = past.isNotEmpty() || transactionStart?.let { it != current } == true
    val canRedo: Boolean get() = future.isNotEmpty()

    fun dispatch(command: EditorCommand): ProjectState {
        val next = ProjectReducer.reduce(current, command)
        if (next == current) return current
        if (transactionStart == null) pushPast(current)
        future.clear()
        current = next
        return current
    }

    fun beginTransaction() { if (transactionStart == null) transactionStart = current }

    fun endTransaction() {
        val start = transactionStart ?: return
        transactionStart = null
        if (start.copy(revision = current.revision) != current) pushPast(start)
    }

    private fun pushPast(state: ProjectState) {
        past.addLast(state)
        while (past.size > capacity) past.removeFirst()
    }

    fun retainedSources(): Set<SourceAsset> = (past + future + listOfNotNull(current, transactionStart))
        .flatMap { it.allAssets().values }.toSet()

    fun replace(project: ProjectState, clearHistory: Boolean = true): ProjectState {
        endTransaction()
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
        endTransaction()
        if (past.isEmpty()) return current
        future.addLast(current)
        current = past.removeLast()
        return current
    }

    fun redo(): ProjectState {
        endTransaction()
        if (future.isEmpty()) return current
        past.addLast(current)
        current = future.removeLast()
        return current
    }
}
