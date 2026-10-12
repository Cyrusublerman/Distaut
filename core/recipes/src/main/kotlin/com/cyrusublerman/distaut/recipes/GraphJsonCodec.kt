package com.cyrusublerman.distaut.recipes

import com.cyrusublerman.distaut.model.*

object GraphJsonCodec {
    fun encode(graph: ProcessingGraph?): JsonValue = graph?.let {
        JsonValue.Object(
            "version" to JsonValue.NumberValue("2"),
            "outputNodeId" to JsonValue.StringValue(it.outputNodeId),
            "connections" to JsonValue.Array(it.connections.map { edge -> JsonValue.Object(
                "from" to JsonValue.StringValue(edge.from), "to" to JsonValue.StringValue(edge.to),
                "input" to JsonValue.StringValue(edge.input), "output" to JsonValue.StringValue(edge.output),
            ) }),
            "sources" to JsonValue.Object(LinkedHashMap(it.sources.mapValues { (_, source) -> JsonValue.Object(
                "assetId" to JsonValue.StringValue(source.assetId), "label" to JsonValue.StringValue(source.label),
            ) })),
            "outputs" to JsonValue.Object(LinkedHashMap(it.outputs.mapValues { (_, target) -> JsonValue.Object(
                "name" to JsonValue.StringValue(target.name), "nodeId" to JsonValue.StringValue(target.endpoint.nodeId),
                "portId" to JsonValue.StringValue(target.endpoint.portId),
            ) })),
            "selectedOutputId" to JsonValue.StringValue(it.selectedOutputId),
            "positions" to JsonValue.Object(LinkedHashMap(it.positions.mapValues { (_, p) -> JsonValue.Object(
                "x" to JsonValue.NumberValue(p.x.toString()), "y" to JsonValue.NumberValue(p.y.toString()),
            ) })),
        )
    } ?: JsonValue.NullValue

    fun decode(value: JsonValue?, effects: List<EffectInstance>): ProcessingGraph? {
        if (value == null || value == JsonValue.NullValue) return null
        val root = value.asObjectOrNull() ?: error("Graph must be an object")
        val connections = root["connections"].asArrayOrNull()?.values ?: error("Graph connections required")
        require(connections.size <= GraphRules.MAX_NODES * 3) { "Too many connections" }
        val version = root["version"].asNumberOrNull()?.asIntExact() ?: error("Graph version required")
        require(version in 1..2) { "Unsupported graph version" }
        require(version >= 2 || (root["sources"] == null && root["outputs"] == null)) { "Sources and outputs require graph version 2" }
        val graph = ProcessingGraph(
            version = 2,
            outputNodeId = root["outputNodeId"].asStringOrNull() ?: error("Graph output required"),
            connections = connections.map { value ->
                val edge = value.asObjectOrNull() ?: error("Connection must be an object")
                GraphConnection(edge["from"].asStringOrNull() ?: error("Connection source required"),
                    edge["to"].asStringOrNull() ?: error("Connection destination required"),
                    edge["input"].asStringOrNull() ?: error("Connection input required"),
                    edge["output"].asStringOrNull() ?: DEFAULT_PORT_ID)
            },
            sources = if (version == 1) ProcessingGraph().sources else (root["sources"].asObjectOrNull()?.values ?: error("Graph sources required")).mapValues { (_, value) ->
                val source = value.asObjectOrNull() ?: error("Source node must be an object")
                GraphSource(source["assetId"].asStringOrNull() ?: error("Source asset ID required"), source["label"].asStringOrNull() ?: "IMAGE")
            },
            outputs = root["outputs"].asObjectOrNull()?.values?.mapValues { (_, value) ->
                val target = value.asObjectOrNull() ?: error("Export target must be an object")
                GraphOutput(target["name"].asStringOrNull() ?: error("Output name required"),
                    PortRef(target["nodeId"].asStringOrNull() ?: error("Output node required"), target["portId"].asStringOrNull() ?: DEFAULT_PORT_ID))
            }.orEmpty(),
            selectedOutputId = root["selectedOutputId"].asStringOrNull() ?: DEFAULT_OUTPUT_ID,
            positions = root["positions"].asObjectOrNull()?.values?.mapValues { (_, value) ->
                val position = value.asObjectOrNull() ?: error("Position must be an object")
                NodePosition(position["x"].asNumberOrNull()?.asDouble()?.toFloat() ?: error("Position x required"),
                    position["y"].asNumberOrNull()?.asDouble()?.toFloat() ?: error("Position y required"))
            }.orEmpty(),
        )
        GraphRules.validate(effects, graph)
        return graph
    }
}
