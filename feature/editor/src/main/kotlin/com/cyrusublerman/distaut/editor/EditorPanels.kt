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
    Column(Modifier.fillMaxWidth().border(BorderWidth, UiBorder, RectangleShape)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(NodeHeight)) {
            val actionWidth = NodeHeight
            val labelWidth = (maxWidth - actionWidth * 5).coerceAtLeast(6 * F)
            Row(Modifier.fillMaxSize()) {
                NodeCell("↑", actionWidth) { viewModel.moveEffect(effect.id, -1) }
                NodeCell("↓", actionWidth) { viewModel.moveEffect(effect.id, 1) }
                NodeCell(
                    if (effect.enabled) "✓" else "□",
                    actionWidth,
                    enabled = effect.isResolved,
                    active = effect.enabled,
                ) { viewModel.setEnabled(effect.id, !effect.enabled) }
                NodeCell(
                    definition?.displayName ?: "UNRESOLVED ${effect.type}",
                    labelWidth,
                    active = selected,
                    alignStart = true,
                ) { viewModel.select(effect.id) }
                NodeCell(
                    if (state.project.soloEffectId == effect.id) "S!" else "S",
                    actionWidth,
                    active = state.project.soloEffectId == effect.id,
                ) {
                    viewModel.setSolo(
                        if (state.project.soloEffectId == effect.id) null
                        else effect.id,
                    )
                }
                NodeCell("×", actionWidth) { viewModel.remove(effect.id) }
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
        NumericSliderRow(
            "OPACITY",
            "${(effect.opacity * 100).roundToInt()}%",
            effect.opacity.toFloat(),
            0f..1f,
            0,
        ) { viewModel.setOpacity(effect.id, it.toDouble()) }

        definition?.parameters?.forEach { parameter ->
            when (parameter) {
                is ParameterDefinition.Integer -> {
                    val current = (
                        effect.parameters[parameter.key] as? ParameterValue.Integer
                        )?.value ?: parameter.default.value
                    NumericSliderRow(
                        parameter.label,
                        current.toString(),
                        current.toFloat(),
                        parameter.minimum.toFloat()..parameter.maximum.toFloat(),
                        ((parameter.maximum - parameter.minimum) /
                            parameter.step - 1).coerceAtLeast(0),
                    ) {
                        viewModel.setParameter(
                            effect.id,
                            parameter.key,
                            ParameterValue.Integer(it.roundToInt()),
                        )
                    }
                }
                else -> QuietMessage("${parameter.label}: CONTROL PENDING")
            }
        }
        if (!effect.isResolved) {
            QuietMessage("NODE PRESERVED BUT NOT EXECUTABLE IN THIS BUILD")
        }
    }
}

@Composable
private fun NumericSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    change: (Float) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(4 * F)
            .border(BorderWidth, UiBorder, RectangleShape)
    ) {
        val labelWidth = 8 * F
        val valueWidth = 5 * F
        val sliderWidth = (maxWidth - labelWidth - valueWidth).coerceAtLeast(8 * F)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(labelWidth)
                    .fillMaxHeight()
                    .border(BorderWidth, UiBorder, RectangleShape)
                    .padding(horizontal = F),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value,
                change,
                Modifier.width(sliderWidth).padding(horizontal = F / 2),
                valueRange = range,
                steps = steps,
            )
            Box(
                Modifier
                    .width(valueWidth)
                    .fillMaxHeight()
                    .border(BorderWidth, UiBorder, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(valueText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun CanvasPanel(state: EditorUiState, viewModel: EditorViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
