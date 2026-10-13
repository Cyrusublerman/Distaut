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
        data class Image(val pixels: PixelBuffer, val document: ImageDimensions) : Value
        data class Mask(val bytes: ByteArray, val width: Int, val height: Int, val document: ImageDimensions) : Value
    }
    @Synchronized override fun render(request: RenderRequest): RenderResult {
        val graph = request.graph ?: return linear.render(request)
        val started = System.nanoTime()
        val endpoint = if (request.quality == RenderQuality.FINAL) graph.exportEndpoint() else request.viewerNodeId?.let { PortRef(it) } ?: graph.exportEndpoint()
        val target = endpoint.nodeId
        val order = GraphRules.executionOrder(request.effects, graph, target)
        val nodes = request.effects.associateBy { it.id }
        val uses = mutableMapOf<String, Int>()
        order.forEach { id -> GraphRules.dependencies(nodes.getValue(id), graph).forEach { uses[it] = (uses[it] ?: 0) + 1 } }
        uses[target] = (uses[target] ?: 0) + 1
        val needed = GraphRules.requiredSources(request.effects, graph, target)
        val provided = mapOf(SOURCE_NODE_ID to request.source) + request.sources
        val inputValues = needed.associateWith { id ->
            val pixels = provided[id] ?: error("Source ${graph.sources.getValue(id).label} is missing; relink this source")
            Value.Image(pixels, request.sourceDimensions[id] ?: if (id == SOURCE_NODE_ID) ImageDimensions(request.documentWidth, request.documentHeight) else ImageDimensions(pixels.width, pixels.height))
        }
        val largest = inputValues.values.maxOfOrNull { it.pixels.rgba.size.toLong() } ?: request.source.rgba.size.toLong()
        // Request-owned source buffers remain alive even after their last graph consumer.
        val sourceBytes = inputValues.values.sumOf { it.pixels.rgba.size.toLong() }
        val live = mutableSetOf<String>(); val remaining = uses.toMutableMap(); var peak = 0
        order.forEach { id ->
            live += id; peak = maxOf(peak, live.size)
            GraphRules.dependencies(nodes.getValue(id), graph).forEach { parent ->
                remaining[parent] = remaining.getValue(parent) - 1
                if (remaining[parent] == 0) live -= parent
            }
        }
        val required = sourceBytes + largest * (peak + 6)
        require(required <= request.workingMemoryBudgetBytes) {
            "Graph needs about ${required / 1048576} MiB working memory; reduce image or export size"
        }
        val values = mutableMapOf<PortRef, Value>()
        inputValues.forEach { (id, value) -> values[PortRef(id)] = value }
        val applied = mutableListOf<String>()
        order.forEach { id ->
            cancelled(request)
            val node = nodes.getValue(id)
            fun input(name: String) = values.getValue(requireNotNull(graph.connection(id, name)).source)
            fun image(name: String) = (input(name) as Value.Image).pixels
            val special = GraphPorts.type(node) in GraphPorts.graphTypes
            if (special) {
                require(node.isResolved) { "Unresolved graph node: $id" }
                val definition = requireNotNull(BuiltInEffects.registry.definition(node.type))
                require(node.algorithmVersion == definition.algorithmVersion || (node.type == "mix" && node.algorithmVersion == "associated-mix-v1")) { "Unsupported algorithm at $id" }
            }
            val effect = if (special) BuiltInEffects.registry.definition(node.type)!!.normalise(node) else node
            fun number(key: String): Double = when (val value = effect.parameters[key]) {
                is ParameterValue.Decimal -> value.value
                is ParameterValue.Integer -> value.value.toDouble()
                else -> error("Missing parameter $key")
            }
            values[PortRef(id)] = when (if (special) effect.type else "effect") {
                "mix" -> {
                    val a = input("a") as Value.Image; val bValue = input("b") as Value.Image; val b = bValue.pixels
                    val rawMask = graph.connection(id, "mask")?.let { values.getValue(it.source) as Value.Mask }
                    fun choice(key: String) = (effect.parameters.getValue(key) as ParameterValue.Choice).value
                    if (effect.algorithmVersion == "associated-mix-v1") {
                        require(a.pixels.width == b.width && a.pixels.height == b.height) { "Upgrade this Mix node to align different image sizes" }
                        require(rawMask == null || (rawMask.width == a.pixels.width && rawMask.height == a.pixels.height)) { "Upgrade this Mix node to align a different mask size" }
                    }
                    val alignedB = alignImage(b, a.pixels.width, a.pixels.height, choice("fit"),
                        a.pixels.width.toDouble() / a.document.width * bValue.document.width / b.width,
                        a.pixels.height.toDouble() / a.document.height * bValue.document.height / b.height) { cancelled(request) }
                    val mask = rawMask?.let { m ->
                        if (m.width == a.pixels.width && m.height == a.pixels.height && (choice("maskFit") != "native" || m.document == a.document)) m.bytes else {
                            val pixels = ByteArray(m.bytes.size * 4)
                            m.bytes.forEachIndexed { i, value ->
                                if (i % m.width == 0) cancelled(request)
                                pixels[i*4] = value; pixels[i*4+1] = value; pixels[i*4+2] = value; pixels[i*4+3] = -1
                            }
                            val aligned = alignImage(PixelBuffer(m.width, m.height, pixels), a.pixels.width, a.pixels.height, choice("maskFit"),
                                a.pixels.width.toDouble() / a.document.width * m.document.width / m.width,
                                a.pixels.height.toDouble() / a.document.height * m.document.height / m.height) { cancelled(request) }
                            ByteArray(aligned.width * aligned.height) { i -> ((aligned.rgba[i*4].u() * aligned.rgba[i*4+3].u() + 127) / 255).toByte() }
                        }
                    }
                    Value.Image(mix(a.pixels, alignedB, mask, number("amount"), request), a.document)
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
                    Value.Mask(mask, source.width, source.height, (input("image") as Value.Image).document)
                }
                "edge_mask" -> {
                    val source = input("image") as Value.Image
                    Value.Mask(edges(source.pixels, number("gain"), request.copy(source = source.pixels, documentWidth = source.document.width, documentHeight = source.document.height)),
                        source.pixels.width, source.pixels.height, source.document)
                }
                "invert_mask" -> {
                    val source = input("mask") as Value.Mask
                    Value.Mask(ByteArray(source.bytes.size) { i ->
                        if (i % source.width == 0) cancelled(request)
                        (255 - source.bytes[i].u()).toByte()
                    }, source.width, source.height, source.document)
                }
                else -> {
                    val source = input("image") as Value.Image
                    if (!effect.enabled) source
                    else Value.Image(linear.render(request.copy(source = source.pixels, effects = listOf(effect),
                        documentWidth = source.document.width, documentHeight = source.document.height,
                        graph = null, viewerNodeId = null)).output, source.document)
                }
            }
            if (effect.enabled || special) applied += id
            GraphRules.dependencies(node, graph).forEach { parent ->
                uses[parent] = uses.getValue(parent) - 1
                if (uses[parent] == 0) values.remove(PortRef(parent))
            }
        }
        cancelled(request)
        val output = when (val value = values.getValue(endpoint)) {
            is Value.Image -> value.pixels
            is Value.Mask -> {
                val rgba = ByteArray(value.bytes.size * 4)
                value.bytes.forEachIndexed { i, v ->
                    if (i % value.width == 0) cancelled(request)
                    rgba[i * 4] = v; rgba[i * 4 + 1] = v; rgba[i * 4 + 2] = v; rgba[i * 4 + 3] = -1
                }
                PixelBuffer(value.width, value.height, rgba)
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
