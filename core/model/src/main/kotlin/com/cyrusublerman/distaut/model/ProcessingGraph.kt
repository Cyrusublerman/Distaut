package com.cyrusublerman.distaut.model

const val SOURCE_NODE_ID = "@source"
enum class PortType { IMAGE, MASK }
data class NodePosition(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite() && x in -10000f..10000f && y in -10000f..10000f) }
}
data class GraphConnection(val from: String, val to: String, val input: String)
data class ProcessingGraph(
    val version: Int = 1,
    val connections: List<GraphConnection> = emptyList(),
    val outputNodeId: String = SOURCE_NODE_ID,
    val positions: Map<String, NodePosition> = emptyMap(),
) {
    init { require(version == 1) { "Unsupported graph version: $version" } }
    fun upstream(node: String, input: String): String? = connections.firstOrNull { it.to == node && it.input == input }?.from
}

/** Effects are graph nodes. Their list order has no execution meaning once topology exists. */
object GraphPorts {
    val graphTypes = setOf("mix", "luminance_mask", "edge_mask", "invert_mask")
    fun type(node: EffectInstance): String = if (node.isResolved) node.type else "unresolved"
    fun output(type: String): PortType = if (type in setOf("luminance_mask", "edge_mask", "invert_mask")) PortType.MASK else PortType.IMAGE
    fun inputs(type: String): Map<String, PortType> = when (type) {
        "mix" -> linkedMapOf("a" to PortType.IMAGE, "b" to PortType.IMAGE, "mask" to PortType.MASK)
        "invert_mask" -> linkedMapOf("mask" to PortType.MASK)
        else -> linkedMapOf("image" to PortType.IMAGE)
    }
    fun required(type: String): Set<String> = inputs(type).keys - if (type == "mix") setOf("mask") else emptySet()
}

fun ProjectState.processingGraph(): ProcessingGraph = graph ?: GraphRules.chain(effects)

object GraphRules {
    const val MAX_NODES = 128
    fun chain(effects: List<EffectInstance>): ProcessingGraph {
        var previous = SOURCE_NODE_ID
        val edges = effects.map { node -> GraphConnection(previous, node.id, "image").also { previous = node.id } }
        return ProcessingGraph(connections = edges, outputNodeId = previous,
            positions = mapOf(SOURCE_NODE_ID to NodePosition(24f, 48f)) + effects.mapIndexed { i, node ->
                node.id to NodePosition(24f + 280f * ((i + 1) % 8), 48f + 240f * ((i + 1) / 8))
            }.toMap())
    }
    fun outputType(id: String, effects: List<EffectInstance>): PortType = if (id == SOURCE_NODE_ID) PortType.IMAGE
        else GraphPorts.output(GraphPorts.type(effects.first { it.id == id }))

    /** Missing sockets are permitted while editing; dangling edges, bad types and cycles never are. */
    fun validate(effects: List<EffectInstance>, graph: ProcessingGraph) {
        require(effects.size <= MAX_NODES) { "At most $MAX_NODES nodes are supported" }
        require(effects.none { it.id == SOURCE_NODE_ID }) { "Reserved source ID" }
        effects.filter { GraphPorts.type(it) in GraphPorts.graphTypes }.forEach {
            require(it.enabled && it.opacity == 1.0 && it.blendMode == "normal") {
                "Graph utility nodes use their declared inputs and mix controls; bypass/opacity is unsupported"
            }
        }
        val nodes = effects.associateBy { it.id }
        require(nodes.size == effects.size) { "Duplicate node IDs" }
        val ids = nodes.keys + SOURCE_NODE_ID
        require(graph.outputNodeId in ids) { "Export output is missing" }
        require(outputType(graph.outputNodeId, effects) == PortType.IMAGE) { "Export output must be an image" }
        require(graph.positions.keys.all { it in ids }) { "Position references a missing node" }
        require(graph.connections.map { it.to to it.input }.distinct().size == graph.connections.size) { "Only one connection per input" }
        graph.connections.forEach { edge ->
            require(edge.from in ids && edge.to in nodes) { "Connection references a missing node" }
            val inputType = GraphPorts.inputs(GraphPorts.type(nodes.getValue(edge.to)))[edge.input]
                ?: error("Unknown input ${edge.input}")
            require(outputType(edge.from, effects) == inputType) { "Cannot connect ${outputType(edge.from, effects)} to $inputType" }
        }
        val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id == SOURCE_NODE_ID || id in visited) return
            require(visiting.add(id)) { "Connection would create a cycle" }
            graph.connections.filter { it.to == id }.forEach { visit(it.from) }
            visiting.remove(id); visited.add(id)
        }
        effects.forEach { visit(it.id) }
    }

    fun dependencies(node: EffectInstance, graph: ProcessingGraph): List<String> {
        val ports = if (!node.enabled && GraphPorts.type(node) !in GraphPorts.graphTypes) setOf("image") else GraphPorts.inputs(GraphPorts.type(node)).keys
        return ports.mapNotNull { graph.upstream(node.id, it) }
    }

    fun executionOrder(effects: List<EffectInstance>, graph: ProcessingGraph, target: String): List<String> {
        validate(effects, graph)
        val nodes = effects.associateBy { it.id }; val order = mutableListOf<String>(); val seen = mutableSetOf<String>()
        require(target == SOURCE_NODE_ID || target in nodes) { "Viewer node no longer exists" }
        fun visit(id: String) {
            if (id == SOURCE_NODE_ID || !seen.add(id)) return
            val node = nodes.getValue(id)
            val required = if (!node.enabled && GraphPorts.type(node) !in GraphPorts.graphTypes) setOf("image") else GraphPorts.required(GraphPorts.type(node))
            required.forEach { require(graph.upstream(id, it) != null) { "${node.type}: connect input ${it.uppercase()}" } }
            dependencies(node, graph).forEach(::visit)
            order += id
        }
        visit(target)
        return order
    }
}
