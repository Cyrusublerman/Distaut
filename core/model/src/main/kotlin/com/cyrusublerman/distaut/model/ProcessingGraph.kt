package com.cyrusublerman.distaut.model

const val SOURCE_NODE_ID = "@source"
const val PRIMARY_ASSET_ID = "primary"
const val DEFAULT_OUTPUT_ID = "final"
const val DEFAULT_PORT_ID = "out"
enum class PortType { IMAGE, MASK }
data class PortRef(val nodeId: String, val portId: String = DEFAULT_PORT_ID)
data class NodePosition(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite() && x in -10000f..10000f && y in -10000f..10000f) }
}
data class GraphSource(val assetId: String, val label: String = "IMAGE") {
    init { require(assetId.matches(Regex("[A-Za-z0-9_-]{1,100}"))); require(label.isNotBlank() && label.length <= 80) }
}
data class GraphOutput(val name: String, val endpoint: PortRef) {
    init { require(name.isNotBlank() && name.length <= 80) }
}
data class GraphConnection(val from: String, val to: String, val input: String, val output: String = DEFAULT_PORT_ID) {
    val source: PortRef get() = PortRef(from, output)
}
data class ProcessingGraph(
    val version: Int = 2,
    val connections: List<GraphConnection> = emptyList(),
    val outputNodeId: String = SOURCE_NODE_ID,
    val positions: Map<String, NodePosition> = emptyMap(),
    val sources: Map<String, GraphSource> = mapOf(SOURCE_NODE_ID to GraphSource(PRIMARY_ASSET_ID, "SOURCE")),
    val outputs: Map<String, GraphOutput> = emptyMap(),
    val selectedOutputId: String = DEFAULT_OUTPUT_ID,
) {
    init { require(version in 1..2) { "Unsupported graph version: $version" } }
    fun upstream(node: String, input: String): String? = connection(node, input)?.from
    fun connection(node: String, input: String): GraphConnection? = connections.firstOrNull { it.to == node && it.input == input }
    fun exportTargets(): Map<String, GraphOutput> = linkedMapOf(DEFAULT_OUTPUT_ID to GraphOutput("Final", PortRef(outputNodeId))) + outputs
    fun exportEndpoint(): PortRef = exportTargets().getValue(selectedOutputId).endpoint
}

/** One declarative port contract is used by validation, rendering and controls. */
data class NodePortSpec(val inputs: Map<String, PortType>, val outputs: Map<String, PortType>, val optional: Set<String> = emptySet())
object GraphPorts {
    private val image = NodePortSpec(linkedMapOf("image" to PortType.IMAGE), mapOf(DEFAULT_PORT_ID to PortType.IMAGE))
    private val definitions: Map<String, NodePortSpec> = mapOf(
        "image_source" to NodePortSpec(emptyMap(), mapOf(DEFAULT_PORT_ID to PortType.IMAGE)),
        "mix" to NodePortSpec(linkedMapOf("a" to PortType.IMAGE, "b" to PortType.IMAGE, "mask" to PortType.MASK), mapOf(DEFAULT_PORT_ID to PortType.IMAGE), setOf("mask")),
        "luminance_mask" to NodePortSpec(linkedMapOf("image" to PortType.IMAGE), mapOf(DEFAULT_PORT_ID to PortType.MASK)),
        "edge_mask" to NodePortSpec(linkedMapOf("image" to PortType.IMAGE), mapOf(DEFAULT_PORT_ID to PortType.MASK)),
        "invert_mask" to NodePortSpec(linkedMapOf("mask" to PortType.MASK), mapOf(DEFAULT_PORT_ID to PortType.MASK)),
    ) + listOf("greyscale", "invert", "posterise", "ordered_dither", "pixelate", "box_blur", "unresolved").associateWith { image }
    val graphTypes = setOf("mix", "luminance_mask", "edge_mask", "invert_mask")
    fun type(node: EffectInstance): String = if (node.isResolved) node.type else "unresolved"
    fun definition(type: String): NodePortSpec = definitions[type] ?: image // unknown preserved legacy effects are image passthroughs
    fun output(type: String, port: String = DEFAULT_PORT_ID): PortType = definition(type).outputs[port] ?: error("Unknown output $port")
    fun inputs(type: String): Map<String, PortType> = definition(type).inputs
    fun required(type: String): Set<String> = inputs(type).keys - definition(type).optional
}

fun ProjectState.processingGraph(): ProcessingGraph = graph ?: GraphRules.chain(effects)
fun ProjectState.allAssets(): Map<String, SourceAsset> = assets + listOfNotNull(source?.let { PRIMARY_ASSET_ID to it }).toMap()
fun ProjectState.sourceAsset(nodeId: String): SourceAsset? = processingGraph().sources[nodeId]?.assetId?.let { allAssets()[it] }
fun ProjectState.nodeIds(): List<String> = processingGraph().sources.keys.toList() + effects.map { it.id }

object GraphRules {
    const val MAX_NODES = 128
    const val MAX_SOURCES = 16
    const val MAX_OUTPUTS = 32
    fun chain(effects: List<EffectInstance>): ProcessingGraph {
        var previous = SOURCE_NODE_ID
        val edges = effects.map { node -> GraphConnection(previous, node.id, "image").also { previous = node.id } }
        return ProcessingGraph(connections = edges, outputNodeId = previous,
            positions = mapOf(SOURCE_NODE_ID to NodePosition(24f, 48f)) + effects.mapIndexed { i, node ->
                node.id to NodePosition(24f + 280f * ((i + 1) % 8), 48f + 240f * ((i + 1) / 8))
            }.toMap())
    }
    fun outputType(id: String, effects: List<EffectInstance>, graph: ProcessingGraph = ProcessingGraph(), port: String = DEFAULT_PORT_ID): PortType =
        GraphPorts.output(if (id in graph.sources) "image_source" else GraphPorts.type(effects.first { it.id == id }), port)

    fun validate(effects: List<EffectInstance>, graph: ProcessingGraph) {
        require(effects.size <= MAX_NODES) { "At most $MAX_NODES processing nodes are supported" }
        require(graph.sources.size in 1..MAX_SOURCES && graph.sources[SOURCE_NODE_ID]?.assetId == PRIMARY_ASSET_ID) { "Invalid source nodes" }
        require(graph.sources.values.map { it.assetId }.distinct().size == graph.sources.size) { "Each source needs its own asset slot" }
        effects.filter { GraphPorts.type(it) in GraphPorts.graphTypes }.forEach {
            require(it.enabled && it.opacity == 1.0 && it.blendMode == "normal") { "Graph utility nodes use their declared inputs and mix controls" }
        }
        val nodes = effects.associateBy { it.id }
        require(nodes.size == effects.size) { "Duplicate node IDs" }
        require(nodes.keys.intersect(graph.sources.keys).isEmpty()) { "Source and processing node IDs overlap" }
        val ids = nodes.keys + graph.sources.keys
        require(ids.all { it.isNotBlank() }) { "Empty node ID" }
        require(graph.outputNodeId in ids) { "Export output is missing" }
        require(outputType(graph.outputNodeId, effects, graph) == PortType.IMAGE) { "Final output must be an image; save masks as named outputs" }
        require(graph.outputs.size <= MAX_OUTPUTS && DEFAULT_OUTPUT_ID !in graph.outputs) { "Invalid named outputs" }
        require(graph.outputs.keys.all { it.isNotBlank() && it.length <= 100 })
        require(graph.exportTargets().containsKey(graph.selectedOutputId)) { "Selected export is missing" }
        require(graph.exportTargets().values.map { it.name.lowercase() }.distinct().size == graph.outputs.size + 1) { "Output names must be unique" }
        graph.outputs.values.forEach { target ->
            require(target.endpoint.nodeId in ids) { "Named export node is missing" }
            outputType(target.endpoint.nodeId, effects, graph, target.endpoint.portId)
        }
        require(graph.positions.keys.all { it in ids }) { "Position references a missing node" }
        require(graph.connections.map { it.to to it.input }.distinct().size == graph.connections.size) { "Only one connection per input" }
        graph.connections.forEach { edge ->
            require(edge.from in ids && edge.to in nodes) { "Connection references a missing node" }
            val inputType = GraphPorts.inputs(GraphPorts.type(nodes.getValue(edge.to)))[edge.input] ?: error("Unknown input ${edge.input}")
            require(outputType(edge.from, effects, graph, edge.output) == inputType) { "Incompatible connection type" }
        }
        val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in graph.sources || id in visited) return
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
    fun requiredSources(effects: List<EffectInstance>, graph: ProcessingGraph, target: String): Set<String> {
        val order = executionOrder(effects, graph, target)
        val nodes = effects.associateBy { it.id }
        return (order.flatMap { dependencies(nodes.getValue(it), graph) } + target).filter { it in graph.sources }.toSet()
    }
    fun executionOrder(effects: List<EffectInstance>, graph: ProcessingGraph, target: String): List<String> {
        validate(effects, graph)
        val nodes = effects.associateBy { it.id }; val order = mutableListOf<String>(); val seen = mutableSetOf<String>()
        require(target in graph.sources || target in nodes) { "Viewer node no longer exists" }
        fun visit(id: String) {
            if (id in graph.sources || !seen.add(id)) return
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
