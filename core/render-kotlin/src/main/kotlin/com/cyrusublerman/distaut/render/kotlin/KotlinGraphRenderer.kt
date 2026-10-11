package com.cyrusublerman.distaut.render.kotlin

import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.effects.normalise
import com.cyrusublerman.distaut.model.*
import com.cyrusublerman.distaut.render.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Executes only ancestors of the requested output; shared branches execute once per request. */
class KotlinGraphRenderer : RenderBackend {
    private val linear = KotlinPipelineRenderer()
    fun clearCache() = linear.clearCache()
    private sealed interface Value {
        data class Image(val pixels: PixelBuffer) : Value
        data class Mask(val bytes: ByteArray) : Value
    }
    @Synchronized override fun render(request: RenderRequest): RenderResult {
        val graph = request.graph ?: return linear.render(request)
        val started = System.nanoTime()
        val target = if (request.quality == RenderQuality.FINAL) graph.outputNodeId else request.viewerNodeId ?: graph.outputNodeId
        val order = GraphRules.executionOrder(request.effects, graph, target)
        val nodes = request.effects.associateBy { it.id }
        val uses = mutableMapOf<String, Int>()
        order.forEach { id -> GraphRules.dependencies(nodes.getValue(id), graph).forEach { uses[it] = (uses[it] ?: 0) + 1 } }
        uses[target] = (uses[target] ?: 0) + 1
        // Bound branch storage before allocating. Four extra buffers cover kernel scratch and output conversion.
        val live = mutableSetOf(SOURCE_NODE_ID); val remaining = uses.toMutableMap(); var peak = 1
        order.forEach { id ->
            live += id; peak = maxOf(peak, live.size)
            GraphRules.dependencies(nodes.getValue(id), graph).forEach { parent ->
                remaining[parent] = remaining.getValue(parent) - 1
                if (remaining[parent] == 0) live -= parent
            }
        }
        val required = request.source.rgba.size.toLong() * (peak + 4)
        require(required <= request.workingMemoryBudgetBytes) {
            "Graph needs about ${required / 1048576} MiB working memory; reduce image or export size"
        }
        val values = mutableMapOf<String, Value>(SOURCE_NODE_ID to Value.Image(request.source))
        val applied = mutableListOf<String>()
        order.forEach { id ->
            cancelled(request)
            val node = nodes.getValue(id)
            fun input(name: String) = values.getValue(requireNotNull(graph.upstream(id, name)))
            fun image(name: String) = (input(name) as Value.Image).pixels
            val special = GraphPorts.type(node) in GraphPorts.graphTypes
            if (special) {
                require(node.isResolved) { "Unresolved graph node: $id" }
                val definition = requireNotNull(BuiltInEffects.registry.definition(node.type))
                require(node.algorithmVersion == definition.algorithmVersion) { "Unsupported algorithm at $id" }
            }
            val effect = if (special) BuiltInEffects.registry.definition(node.type)!!.normalise(node) else node
            fun number(key: String): Double = when (val value = effect.parameters[key]) {
                is ParameterValue.Decimal -> value.value
                is ParameterValue.Integer -> value.value.toDouble()
                else -> error("Missing parameter $key")
            }
            values[id] = when (if (special) effect.type else "effect") {
                "mix" -> {
                    val a = image("a"); val b = image("b")
                    val mask = graph.upstream(id, "mask")?.let { (values.getValue(it) as Value.Mask).bytes }
                    Value.Image(mix(a, b, mask, number("amount"), request))
                }
                "luminance_mask" -> {
                    val source = image("image"); val threshold = number("threshold"); val softness = number("softness")
                    val mask = ByteArray(source.width * source.height)
                    for (i in mask.indices) {
                        if (i % source.width == 0) cancelled(request)
                        val rgba = source.rgba; val p = i * 4
                        val luma = (0.2126 * rgba[p].u() + 0.7152 * rgba[p + 1].u() + 0.0722 * rgba[p + 2].u()) / 255.0
                        val weight = ((luma - threshold) / softness + 0.5).coerceIn(0.0, 1.0)
                        mask[i] = (weight * rgba[p + 3].u()).roundToInt().toByte()
                    }
                    Value.Mask(mask)
                }
                "edge_mask" -> Value.Mask(edges(image("image"), number("gain"), request))
                "invert_mask" -> {
                    val input = (input("mask") as Value.Mask).bytes
                    Value.Mask(ByteArray(input.size) { i ->
                        if (i % request.source.width == 0) cancelled(request)
                        (255 - input[i].u()).toByte()
                    })
                }
                else -> {
                    val source = image("image")
                    if (!effect.enabled) Value.Image(source)
                    else Value.Image(linear.render(request.copy(source = source, effects = listOf(effect),
                        graph = null, viewerNodeId = null)).output)
                }
            }
            if (effect.enabled || special) applied += id
            GraphRules.dependencies(node, graph).forEach { parent ->
                uses[parent] = uses.getValue(parent) - 1
                if (uses[parent] == 0) values.remove(parent)
            }
        }
        cancelled(request)
        val output = when (val value = values.getValue(target)) {
            is Value.Image -> value.pixels
            is Value.Mask -> {
                val rgba = ByteArray(value.bytes.size * 4)
                value.bytes.forEachIndexed { i, v ->
                    if (i % request.source.width == 0) cancelled(request)
                    rgba[i * 4] = v; rgba[i * 4 + 1] = v; rgba[i * 4 + 2] = v; rgba[i * 4 + 3] = -1
                }
                PixelBuffer(request.source.width, request.source.height, rgba)
            }
        }
        return RenderResult(request.generation, request.sourceRevision, request.quality, output, applied, System.nanoTime() - started)
    }

    private fun mix(a: PixelBuffer, b: PixelBuffer, mask: ByteArray?, amount: Double, request: RenderRequest): PixelBuffer {
        require(a.width == b.width && a.height == b.height)
        val out = ByteArray(a.rgba.size)
        for (pixel in 0 until a.width * a.height) {
            if (pixel % a.width == 0) cancelled(request)
            val p = pixel * 4; val weight = amount * (mask?.get(pixel)?.u()?.div(255.0) ?: 1.0)
            val aa = a.rgba[p + 3].u() / 255.0; val ba = b.rgba[p + 3].u() / 255.0
            val alpha = aa * (1 - weight) + ba * weight
            for (channel in 0..2) out[p + channel] = if (alpha <= 0) 0 else
                ((a.rgba[p + channel].u() * aa * (1 - weight) + b.rgba[p + channel].u() * ba * weight) / alpha)
                    .roundToInt().coerceIn(0, 255).toByte()
            out[p + 3] = (alpha * 255).roundToInt().toByte()
        }
        return PixelBuffer(a.width, a.height, out)
    }
    private fun edges(source: PixelBuffer, gain: Double, request: RenderRequest): ByteArray {
        fun luma(x: Int, y: Int): Double {
            val p = (y.coerceIn(0, source.height - 1) * source.width + x.coerceIn(0, source.width - 1)) * 4
            return (0.2126 * source.rgba[p].u() + 0.7152 * source.rgba[p + 1].u() + 0.0722 * source.rgba[p + 2].u()) / 255.0
        }
        val out = ByteArray(source.width * source.height)
        for (y in 0 until source.height) {
            cancelled(request)
            for (x in 0 until source.width) {
                val dx = (luma(x + 1, y) - luma(x - 1, y)) * request.scaleX * 0.5
                val dy = (luma(x, y + 1) - luma(x, y - 1)) * request.scaleY * 0.5
                out[y * source.width + x] = (sqrt(dx * dx + dy * dy) * gain * source.rgba[(y * source.width + x) * 4 + 3].u())
                    .roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }
    private fun Byte.u() = toInt() and 255
    private fun cancelled(request: RenderRequest) { if (request.cancellationProbe.isCancelled()) throw RenderCancelledException() }
}
