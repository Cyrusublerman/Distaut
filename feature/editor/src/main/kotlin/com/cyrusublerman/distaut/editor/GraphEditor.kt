package com.cyrusublerman.distaut.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.model.*
import kotlin.math.roundToInt

private const val NODE_WIDTH = 220f
private val ImageWire = Color(0xFF77CEEE)
private val MaskWire = Color(0xFFFFBF69)

internal fun nodeName(id: String, state: EditorUiState): String = if (id == SOURCE_NODE_ID) "SOURCE"
    else state.project.effects.firstOrNull { it.id == id }?.let { node ->
        if (!node.isResolved) "UNRESOLVED ${node.type}" else BuiltInEffects.registry.definition(node.type)?.displayName ?: node.type
    } ?: "MISSING"

@Composable
internal fun GraphWorkbench(state: EditorUiState, viewModel: EditorViewModel, modifier: Modifier, onImageSettings: () -> Unit) {
    var catalogue by remember { mutableStateOf(false) }
    var example by remember { mutableStateOf(false) }
    var inspector by rememberSaveable { mutableStateOf(false) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var wireSource by remember { mutableStateOf<String?>(null) }
    val graph = state.project.processingGraph()
    LaunchedEffect(state.project.effects.map { it.id }) {
        if (wireSource != SOURCE_NODE_ID && state.project.effects.none { it.id == wireSource }) wireSource = null
    }
    Column(modifier.border(BorderWidth, UiBorder)) {
        Row(Modifier.fillMaxWidth().height(ToolbarHeight).horizontalScroll(rememberScrollState())) {
            Box {
                ToolCell("+ NODE", 8 * F) { catalogue = true }
                DropdownMenu(catalogue, onDismissRequest = { catalogue = false }) {
                    BuiltInEffects.registry.all().forEach { definition ->
                        DropdownMenuItem(text = { Text(definition.displayName) }, onClick = {
                            catalogue = false; viewModel.addEffect(definition.type); fitRequest++
                        })
                    }
                }
            }
            ToolCell(if (inspector) "GRAPH" else "INSPECT", 8 * F, active = inspector) { inspector = !inspector }
            ToolCell("FIT", 5 * F) { fitRequest++; inspector = false }
            ToolCell("ARRANGE", 8 * F) { viewModel.arrangeNodes(); fitRequest++ }
            ToolCell("FINAL", 6 * F, active = state.viewerNodeId == null) { viewModel.viewNode(null) }
            ToolCell("EXAMPLE", 8 * F) { example = true }
            ToolCell("IMAGE SETTINGS", 13 * F, onClick = onImageSettings)
        }
        Text(if (wireSource == null) "DRAG HEADERS TO MOVE · TAP OUT, THEN AN INPUT · PINCH BACKGROUND TO ZOOM"
            else "${nodeName(wireSource!!, state)} SELECTED · TAP A MATCHING INPUT · TAP OUT AGAIN TO CANCEL",
            Modifier.fillMaxWidth().background(UiMuted).padding(horizontal = F, vertical = 3.dp), fontSize = 9.sp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 1000.dp) {
                Row(Modifier.fillMaxSize()) {
                    NodeCanvas(state, viewModel, fitRequest, wireSource,
                        { id -> wireSource = if (wireSource == id) null else id },
                        { to, port -> wireSource?.let { if (viewModel.connect(it, to, port)) wireSource = null } },
                        Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(320.dp).fillMaxHeight()) { NodeInspector(state, viewModel) }
                }
            } else if (inspector) NodeInspector(state, viewModel)
            else NodeCanvas(state, viewModel, fitRequest, wireSource,
                { id -> wireSource = if (wireSource == id) null else id },
                { to, port ->
                    val from = wireSource
                    if (from != null) { if (viewModel.connect(from, to, port)) wireSource = null }
                    else { viewModel.select(to); inspector = true }
                }, Modifier.fillMaxSize())
        }
    }
    if (example) AlertDialog(onDismissRequest = { example = false }, title = { Text("Load branching example?") },
        text = { Text("Replace this graph with two image branches, an edge mask and a Mix node. Your source stays in place. Undo restores the previous graph.") },
        confirmButton = { TextButton(onClick = { example = false; viewModel.createBranchExample(); fitRequest++; inspector = false }) { Text("LOAD") } },
        dismissButton = { TextButton(onClick = { example = false }) { Text("CANCEL") } })
}

@Composable
private fun NodeCanvas(
    state: EditorUiState, viewModel: EditorViewModel, fitRequest: Int, wireSource: String?,
    onOutput: (String) -> Unit, onInput: (String, String) -> Unit, modifier: Modifier,
) {
    val graph = state.project.processingGraph()
    val nodes = state.project.effects.associateBy { it.id }
    val ids = listOf(SOURCE_NODE_ID) + nodes.keys
    val density = LocalDensity.current.density
    var zoom by remember { mutableFloatStateOf(0.8f) }
    var pan by remember { mutableStateOf(Offset(20f, 20f)) }
    val dragged = remember(graph.positions) { mutableStateMapOf<String, NodePosition>() }
    fun position(id: String): NodePosition = dragged[id] ?: graph.positions[id]
        ?: NodePosition(24f + (ids.indexOf(id) % 8) * 280f, 40f + (ids.indexOf(id) / 8) * 240f)
    fun inputs(id: String) = nodes[id]?.let { GraphPorts.inputs(GraphPorts.type(it)).keys.toList() }.orEmpty()
    fun nodeHeight(id: String) = 42f + (inputs(id).size + 1) * 40f
    fun portColour(id: String) = if (GraphRules.outputType(id, state.project.effects) == PortType.MASK) MaskWire else ImageWire
    BoxWithConstraints(modifier.background(Color(0xFF171A1E)).clipToBounds()) {
        val width = maxWidth.value; val height = maxHeight.value
        LaunchedEffect(fitRequest, width, height) {
            val minX = ids.minOf { position(it).x }; val minY = ids.minOf { position(it).y }
            val maxX = ids.maxOf { position(it).x + NODE_WIDTH }; val maxY = ids.maxOf { position(it).y + nodeHeight(it) }
            zoom = minOf((width - 32) / (maxX - minX), (height - 32) / (maxY - minY)).coerceIn(0.15f, 1.1f)
            pan = Offset((16 - minX * zoom) * density, (16 - minY * zoom) * density)
        }
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { centroid, change, scale, _ ->
                val next = (zoom * scale).coerceIn(0.15f, 2.0f)
                pan = centroid - (centroid - pan) * (next / zoom) + change
                zoom = next
            }
        }) {
            val grid = 32 * density * zoom
            if (grid >= 8f) {
                var x = ((pan.x % grid) + grid) % grid
                while (x < size.width) {
                    var y = ((pan.y % grid) + grid) % grid
                    while (y < size.height) { drawCircle(Color(0xFF454B51), 1f, Offset(x, y)); y += grid }
                    x += grid
                }
            }
            graph.connections.forEach { edge ->
                val from = position(edge.from); val to = position(edge.to)
                val start = pan + Offset((from.x + NODE_WIDTH) * density * zoom,
                    (from.y + 42f + inputs(edge.from).size * 40f + 20f) * density * zoom)
                val end = pan + Offset(to.x * density * zoom,
                    (to.y + 42f + inputs(edge.to).indexOf(edge.input) * 40f + 20f) * density * zoom)
                val bend = maxOf(50 * density * zoom, kotlin.math.abs(end.x - start.x) * 0.5f)
                val path = Path().apply { moveTo(start.x, start.y); cubicTo(start.x + bend, start.y, end.x - bend, end.y, end.x, end.y) }
                drawPath(path, portColour(edge.from), style = Stroke(2.5f * density * zoom.coerceAtLeast(0.5f)))
            }
        }
        ids.forEach { id ->
            key(id) {
                val pos = position(id)
                val selected = state.selectedEffectId == id
                val target = graph.outputNodeId == id
                val viewing = (state.viewerNodeId ?: graph.outputNodeId) == id
                val node = nodes[id]
                Box(Modifier.offset { IntOffset((pan.x + pos.x * density * zoom).roundToInt(), (pan.y + pos.y * density * zoom).roundToInt()) }
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(NODE_WIDTH.dp, nodeHeight(id).dp)
                    .graphicsLayer { scaleX = zoom; scaleY = zoom; transformOrigin = TransformOrigin(0f, 0f) }) {
                    Column(Modifier.fillMaxSize()
                        .background(UiBackground).border(if (selected) 2.dp else 1.dp, if (selected) ImageWire else UiBorder)) {
                        Row(Modifier.fillMaxWidth().height(42.dp).background(if (selected) UiText else UiMuted)
                            .semantics { contentDescription = "Move or select ${nodeName(id, state)}" }
                            .pointerInput(id, density) {
                                detectDragGestures(onDragStart = { viewModel.select(id) },
                                    onDragEnd = { dragged[id]?.let { viewModel.positionNode(id, it) } },
                                    onDragCancel = { dragged.remove(id) }) { change, delta ->
                                    change.consume()
                                    val current = position(id)
                                    dragged[id] = NodePosition((current.x + delta.x / (density * zoom)).coerceIn(-9800f, 9800f),
                                        (current.y + delta.y / (density * zoom)).coerceIn(-9800f, 9800f))
                                }
                            }.clickable { viewModel.select(id) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(nodeName(id, state), Modifier.weight(1f), color = if (selected) UiInverseText else UiText,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text((if (target) "F" else "") + (if (viewing) " V" else ""), color = if (selected) UiInverseText else UiText, fontSize = 10.sp)
                        }
                        inputs(id).forEach { input ->
                            val connected = graph.upstream(id, input) != null
                            val type = GraphPorts.inputs(GraphPorts.type(node!!)).getValue(input)
                            Row(Modifier.fillMaxWidth().height(40.dp).clickable { onInput(id, input) }
                                .semantics { contentDescription = "Connect input $input of ${nodeName(id, state)}" }
                                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (connected) "●" else "○", color = if (type == PortType.MASK) MaskWire else ImageWire, fontSize = 18.sp)
                                Text("  ${input.uppercase()}  ${type.name}", fontSize = 11.sp)
                            }
                        }
                        Row(Modifier.fillMaxWidth().height(40.dp).background(if (wireSource == id) UiMuted else UiBackground)
                            .clickable { onOutput(id) }.semantics { contentDescription = "Connect output of ${nodeName(id, state)}" }
                            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                            Text("${GraphRules.outputType(id, state.project.effects)} OUT  ", fontSize = 11.sp)
                            Text("●", color = portColour(id), fontSize = 18.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).height(40.dp).background(UiBackground)) {
            ToolCell("−", 5 * F) { zoom = (zoom / 1.25f).coerceAtLeast(0.15f) }
            ToolCell("${(zoom * 100).roundToInt()}%", 6 * F) { zoom = 1f }
            ToolCell("+", 5 * F) { zoom = (zoom * 1.25f).coerceAtMost(2f) }
        }
    }
}

@Composable
internal fun NodeInspector(state: EditorUiState, viewModel: EditorViewModel) {
    val selected = state.selectedEffectId
    val node = state.project.effects.firstOrNull { it.id == selected }
    val graph = state.project.processingGraph()
    Column(Modifier.fillMaxSize().background(UiBackground).verticalScroll(rememberScrollState())) {
        BlockTitle(if (selected == null) "Node inspector" else nodeName(selected, state))
        if (selected == null) {
            QuietMessage("TAP A NODE HEADER, THEN INSPECT. SELECT SOURCE BEFORE ADDING A PARALLEL BRANCH.")
        } else {
            PartitionButton("VIEW THIS OUTPUT", active = state.viewerNodeId == selected) { viewModel.viewNode(selected) }
            if (GraphRules.outputType(selected, state.project.effects) == PortType.IMAGE) {
                PartitionButton("SET AS FINAL EXPORT", active = graph.outputNodeId == selected) { viewModel.setOutput(selected) }
            }
            if (node != null) {
                GraphPorts.inputs(GraphPorts.type(node)).forEach { (input, type) ->
                    var choices by remember(node.id, input) { mutableStateOf(false) }
                    val upstream = graph.upstream(node.id, input)
                    Box {
                        PartitionButton("${input.uppercase()} ← ${upstream?.let { nodeName(it, state) } ?: "DISCONNECTED"}") { choices = true }
                        DropdownMenu(choices, onDismissRequest = { choices = false }) {
                            val candidates = (listOf(SOURCE_NODE_ID) + state.project.effects.map { it.id }).filter {
                                it != node.id && GraphRules.outputType(it, state.project.effects) == type
                            }
                            candidates.forEach { id -> DropdownMenuItem(text = { Text(nodeName(id, state) + " · " + id.takeLast(5)) }, onClick = {
                                choices = false; viewModel.connect(id, node.id, input)
                            }) }
                            DropdownMenuItem(text = { Text("Disconnect") }, onClick = { choices = false; viewModel.disconnect(node.id, input) })
                        }
                    }
                }
                if (node.isResolved && node.type !in GraphPorts.graphTypes) {
                    PartitionButton(if (node.enabled) "BYPASS NODE" else "ENABLE NODE", active = !node.enabled) {
                        viewModel.setEnabled(node.id, !node.enabled)
                    }
                }
                ParameterPanel(node, BuiltInEffects.registry.definition(node.type).takeIf { node.isResolved }, viewModel)
                PartitionButton("DELETE NODE") { viewModel.remove(node.id) }
            } else QuietMessage("ONE RETAINED SOURCE CAN FEED ANY NUMBER OF IMAGE INPUTS.")
        }
        QuietMessage("F = FINAL EXPORT · V = VIEWER. VIEWING A MASK DOES NOT CHANGE EXPORT.")
        state.warnings.forEach { QuietMessage(it) }
    }
}
