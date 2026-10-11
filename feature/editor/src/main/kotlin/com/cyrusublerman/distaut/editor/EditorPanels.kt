package com.cyrusublerman.distaut.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.effects.EffectDefinition
import com.cyrusublerman.distaut.effects.ParameterDefinition
import com.cyrusublerman.distaut.model.EffectInstance
import com.cyrusublerman.distaut.model.ParameterValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun PipelinePanel(state: EditorUiState, viewModel: EditorViewModel) {
    var showCatalogue by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BlockTitle("Source")
        SourceSummary(state)
        state.warnings.forEach { QuietMessage(it) }
        BlockTitle("Stack")
        PartitionButton(
            if (showCatalogue) "− CLOSE EFFECT CATALOGUE" else "+ ADD EFFECT",
            active = showCatalogue,
            onClick = { showCatalogue = !showCatalogue },
        )
        if (showCatalogue) EffectCatalogue(viewModel)
        if (state.project.effects.isEmpty()) {
            QuietMessage("NO EFFECTS IN PIPELINE")
        } else {
            state.project.effects.forEach { EffectNode(it, state, viewModel) }
        }
    }
}

@Composable
private fun SourceSummary(state: EditorUiState) {
    val source = state.project.source
    Column(
        Modifier
            .fillMaxWidth()
            .border(BorderWidth, UiBorder, RectangleShape)
            .padding(F)
    ) {
        Text(
            source?.displayName ?: "NO SOURCE",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1,
        )
        Text(
            if (source == null) "SELECT AN IMAGE FROM THE TOP BAR"
            else "${source.width} × ${source.height} · " +
                "${source.mimeType ?: "UNKNOWN"} · MANAGED COPY",
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun EffectCatalogue(viewModel: EditorViewModel) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = maxWidth / 2
        Column {
            BuiltInEffects.registry.all().chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { definition ->
                        ToolCell(
                            "+ ${definition.displayName}",
                            cellWidth,
                            onClick = { viewModel.addEffect(definition.type) },
                        )
                    }
                    if (row.size == 1) {
                        Box(
                            Modifier
                                .width(cellWidth)
                                .height(ToolbarHeight)
                                .border(BorderWidth, UiBorder, RectangleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectNode(
    effect: EffectInstance,
    state: EditorUiState,
    viewModel: EditorViewModel,
) {
    val selected = state.selectedEffectId == effect.id
    val definition = BuiltInEffects.registry.definition(effect.type)
    var menu by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(BorderWidth, UiBorder, RectangleShape)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(NodeHeight)) {
            val labelWidth = (maxWidth - NodeHeight * 3).coerceAtLeast(0.dp)
            Row(Modifier.fillMaxSize()) {
                NodeCell(if (effect.enabled) "✓" else "□", NodeHeight, enabled = effect.isResolved,
                    active = effect.enabled, description = "Toggle ${effect.type}") { viewModel.setEnabled(effect.id, !effect.enabled) }
                NodeCell(if (!effect.isResolved) "UNRESOLVED ${effect.type}" else definition?.displayName ?: effect.type,
                    labelWidth, active = selected, alignStart = true) { viewModel.select(effect.id) }
                NodeCell("VIEW", NodeHeight, active = state.project.soloEffectId == effect.id,
                    enabled = effect.enabled && effect.isResolved,
                    description = "Preview through ${effect.type}; final export is unchanged") {
                    viewModel.setSolo(if (state.project.soloEffectId == effect.id) null else effect.id)
                }
                Box {
                    NodeCell("···", NodeHeight, description = "Actions for ${effect.type}") { menu = true }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; viewModel.moveEffect(effect.id, -1) })
                        DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; viewModel.moveEffect(effect.id, 1) })
                        DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; viewModel.remove(effect.id) })
                    }
                }
            }
        }
        if (selected) ParameterPanel(effect, definition, viewModel)
    }
}

@Composable
private fun NodeCell(
    label: String,
    width: Dp,
    enabled: Boolean = true,
    active: Boolean = false,
    alignStart: Boolean = false,
    description: String = label,
    onClick: () -> Unit,
) {
    val background = when {
        active -> UiText
        enabled -> UiBackground
        else -> UiMuted
    }
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(background)
            .border(BorderWidth, UiBorder, RectangleShape)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (alignStart) F else 0.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) UiInverseText else UiText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ParameterPanel(
    effect: EffectInstance,
    definition: EffectDefinition?,
    viewModel: EditorViewModel,
) {
    Column(Modifier.fillMaxWidth().background(UiBackground)) {
        NumericSliderRow("OPACITY", "${(effect.opacity * 100).roundToInt()}%", effect.opacity.toFloat(), 0f..1f, 0,
            finished = viewModel::finishAdjustment, reset = { viewModel.setOpacity(effect.id, 1.0); viewModel.finishAdjustment() }) {
            viewModel.setOpacity(effect.id, it.toDouble())
        }
        definition?.parameters?.forEach { parameter ->
            when (parameter) {
                is ParameterDefinition.Integer -> {
                    val current = when (val value = effect.parameters[parameter.key]) {
                        is ParameterValue.Integer -> value.value
                        is ParameterValue.Decimal -> value.value.roundToInt()
                        else -> parameter.default.value
                    }.coerceIn(parameter.minimum, parameter.maximum)
                    NumericSliderRow(parameter.label, current.toString(), current.toFloat(),
                        parameter.minimum.toFloat()..parameter.maximum.toFloat(),
                        ((parameter.maximum - parameter.minimum) / parameter.step - 1).coerceAtLeast(0),
                        finished = viewModel::finishAdjustment, reset = { viewModel.resetParameter(effect.id, parameter.key) }) {
                        viewModel.setParameter(effect.id, parameter.key, ParameterValue.Integer(it.roundToInt()))
                    }
                }
                is ParameterDefinition.Decimal -> {
                    val current = (effect.parameters[parameter.key] as? ParameterValue.Decimal)?.value ?: parameter.default.value
                    NumericSliderRow(parameter.label, "%.3f".format(current), current.toFloat(),
                        parameter.minimum.toFloat()..parameter.maximum.toFloat(), 0,
                        finished = viewModel::finishAdjustment, reset = { viewModel.resetParameter(effect.id, parameter.key) }) {
                        viewModel.setParameter(effect.id, parameter.key, ParameterValue.Decimal(it.toDouble()))
                    }
                }
                is ParameterDefinition.Toggle -> Row(Modifier.fillMaxWidth().padding(horizontal = F), verticalAlignment = Alignment.CenterVertically) {
                    Text(parameter.label, Modifier.weight(1f))
                    Switch((effect.parameters[parameter.key] as? ParameterValue.Toggle)?.value ?: parameter.default.value,
                        onCheckedChange = { viewModel.setParameter(effect.id, parameter.key, ParameterValue.Toggle(it)); viewModel.finishAdjustment() })
                }
                is ParameterDefinition.Choice -> {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    val selected = (effect.parameters[parameter.key] as? ParameterValue.Choice)?.value ?: parameter.default.value
                    Box {
                        PartitionButton("${parameter.label}: $selected") { expanded = true }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            parameter.choices.forEach { choice ->
                                DropdownMenuItem(text = { Text(choice) }, onClick = {
                                    expanded = false
                                    viewModel.setParameter(effect.id, parameter.key, ParameterValue.Choice(choice))
                                    viewModel.finishAdjustment()
                                })
                            }
                        }
                    }
                }
            }
        }
        if (!effect.isResolved) QuietMessage("PRESERVED AND DISABLED. ORIGINAL SETTINGS REMAIN IN THE PROJECT.")
        else if (definition != null && (effect.algorithmVersion != definition.algorithmVersion || effect.compositionVersion < 2)) {
            QuietMessage("LEGACY RENDERING RETAINED FOR THIS EFFECT")
            PartitionButton("UPDATE TO CORRECTED ALGORITHM") { viewModel.upgradeEffect(effect.id) }
        }
    }
}

@Composable
private fun NumericSliderRow(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    finished: () -> Unit, reset: () -> Unit, change: (Float) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(BorderWidth, UiBorder, RectangleShape)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = F), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 12.sp)
            TextButton(onClick = { typed = value.toString(); error = false; editing = true }) { Text(valueText) }
            TextButton(onClick = reset) { Text("RESET") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val increment = (range.endInclusive - range.start) / if (steps > 0) (steps + 1) else 100
            TextButton(onClick = { change((value - increment).coerceIn(range)); finished() }) { Text("−") }
            Slider(value.coerceIn(range), change, Modifier.weight(1f), valueRange = range, steps = steps,
                onValueChangeFinished = finished)
            TextButton(onClick = { change((value + increment).coerceIn(range)); finished() }) { Text("+") }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(label) },
        text = { OutlinedTextField(typed, onValueChange = { typed = it; error = false }, singleLine = true,
            isError = error, label = { Text("${range.start} to ${range.endInclusive}") }) },
        confirmButton = { TextButton(onClick = {
            val number = typed.toFloatOrNull()
            if (number == null || !number.isFinite() || number !in range) error = true
            else { change(number); finished(); editing = false }
        }) { Text("APPLY") } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("CANCEL") } })
}

@Composable
internal fun CanvasPanel(state: EditorUiState, viewModel: EditorViewModel) {
    var confirmCleanup by rememberSaveable { mutableStateOf(false) }
    if (confirmCleanup) AlertDialog(onDismissRequest = { confirmCleanup = false },
        title = { Text("Remove unused source copies?") },
        text = { Text("Current, undo and redo sources are retained. Portable projects include their images. Older JSON-only projects may need their source relinked after cleanup.") },
        confirmButton = { TextButton(onClick = { confirmCleanup = false; viewModel.removeUnusedSourceCopies() }) { Text("REMOVE UNUSED") } },
        dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("CANCEL") } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BlockTitle("Inspect")
        PartitionButton(if (state.fullDetail) "RETURN TO FIT PREVIEW" else "LOAD FULL-RESOLUTION DETAIL", enabled = !state.operationInProgress) {
            viewModel.setFullDetail(!state.fullDetail)
        }
        PartitionButton(if (state.compare) "CLOSE BEFORE / AFTER" else "BEFORE / AFTER WIPE") { viewModel.setCompare(!state.compare) }
        BlockTitle("Export size")
        listOf(null, 4096, 2048).forEach { maximum ->
            PartitionButton(maximum?.let { "AT MOST $it PX" } ?: "FULL RESOLUTION", active = state.exportMaximumDimension == maximum) {
                viewModel.setExportMaximumDimension(maximum)
            }
        }
        QuietMessage("EXPORT USES THE COMPLETE ENABLED STACK. VIEW THROUGH ONLY CHANGES THE PREVIEW.")
        state.warnings.forEach { QuietMessage(it) }
        BlockTitle("Canvas")
        KeyValue("DISPLAY", if (state.showSource) "SOURCE" else "OUTPUT")
        KeyValue("REVISION", state.project.revision.toString())
        KeyValue("NODES", state.project.effects.size.toString())
        KeyValue(
            "RENDER",
            state.lastRenderDurationMillis?.let { "${"%.1f".format(it)} MS" }
                ?: "NOT RENDERED",
        )
        KeyValue(
            "SOURCE",
            state.project.source?.let { "${it.width} × ${it.height}" } ?: "NONE",
        )
        PartitionButton(
            if (state.showSource) "SHOW OUTPUT" else "SHOW SOURCE",
            enabled = state.sourceBitmap != null,
            active = state.showSource,
        ) { viewModel.setShowSource(!state.showSource) }
        PartitionButton("REMOVE UNUSED SOURCE COPIES", enabled = !state.operationInProgress) { confirmCleanup = true }
        PartitionButton("RUN SELF-CHECK", onClick = viewModel::runSelfCheck)
    }
}

@Composable
internal fun DebugPanel(
    state: EditorUiState,
    entries: List<DiagnosticEntry>,
    exportDiagnostics: () -> Unit,
    viewModel: EditorViewModel,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(ToolbarHeight)) {
            val width = maxWidth / 4
            Row(Modifier.fillMaxSize()) {
                ToolCell("SELF TEST", width, onClick = viewModel::runSelfCheck)
                ToolCell("COPY", width, onClick = {
                    clipboard.setText(
                        AnnotatedString(DiagnosticsLog.report(context, state.project))
                    )
                })
                ToolCell("EXPORT", width, onClick = exportDiagnostics)
                ToolCell("CLEAR", width, onClick = viewModel::clearDiagnostics)
            }
        }
        KeyValue("ERRORS", entries.count { it.level == DiagnosticLevel.ERROR }.toString())
        KeyValue(
            "WARNINGS",
            entries.count { it.level == DiagnosticLevel.WARNING }.toString(),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries.asReversed(), key = { it.id }) { DiagnosticRow(it) }
        }
    }
}

@Composable
private fun DiagnosticRow(entry: DiagnosticEntry) {
    val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        .format(Date(entry.timestampMillis))
    val inverted = entry.level == DiagnosticLevel.ERROR
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (inverted) UiText else UiBackground)
            .border(BorderWidth, UiBorder, RectangleShape)
            .padding(F / 2)
    ) {
        Text(
            "$timestamp · ${entry.level.name} · ${entry.component.uppercase()}",
            color = if (inverted) UiInverseText else UiText,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            entry.message,
            color = if (inverted) UiInverseText else UiText,
            fontSize = 10.sp,
        )
        entry.details?.lineSequence()?.take(4)?.forEach {
            Text(
                it,
                color = if (inverted) UiInverseText else UiText,
                fontSize = 8.sp,
                maxLines = 1,
            )
        }
    }
}
