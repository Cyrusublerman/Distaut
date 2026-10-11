package com.cyrusublerman.distaut.recipes

import com.cyrusublerman.distaut.model.*

object GraphJsonCodec {
    fun encode(graph: ProcessingGraph?): JsonValue = graph?.let {
        JsonValue.Object(
            "version" to JsonValue.NumberValue(it.version.toString()),
            "outputNodeId" to JsonValue.StringValue(it.outputNodeId),
            "connections" to JsonValue.Array(it.connections.map { edge -> JsonValue.Object(
                "from" to JsonValue.StringValue(edge.from), "to" to JsonValue.StringValue(edge.to),
                "input" to JsonValue.StringValue(edge.input),
            ) }),
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
        val graph = ProcessingGraph(
            version = root["version"].asNumberOrNull()?.asIntExact() ?: error("Graph version required"),
            outputNodeId = root["outputNodeId"].asStringOrNull() ?: error("Graph output required"),
            connections = connections.map { value ->
                val edge = value.asObjectOrNull() ?: error("Connection must be an object")
                GraphConnection(edge["from"].asStringOrNull() ?: error("Connection source required"),
                    edge["to"].asStringOrNull() ?: error("Connection destination required"),
                    edge["input"].asStringOrNull() ?: error("Connection input required"))
            },
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
