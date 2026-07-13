package com.cyrusublerman.distaut.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.effects.ParameterDefinition
import com.cyrusublerman.distaut.model.EffectInstance
import com.cyrusublerman.distaut.model.ParameterValue
import kotlin.math.roundToInt

@Composable
fun EditorRoute(viewModel: EditorViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::importSource)
    }
    val projectSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::saveProject) }
    val projectOpener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::openProject)
    }
    val recipeSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::saveRecipe) }
    val recipeOpener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::openRecipe)
    }
    val pngExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> uri?.let(viewModel::exportPng) }

    EditorScreen(
        state = state,
        onPickImage = {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onSaveProject = { projectSaver.launch("distaut-project.distaut.json") },
        onOpenProject = { projectOpener.launch(arrayOf("application/json", "text/plain")) },
        onSaveRecipe = { recipeSaver.launch("distaut-recipe.v2.json") },
        onOpenRecipe = { recipeOpener.launch(arrayOf("application/json", "text/plain")) },
        onExportPng = { pngExporter.launch("distaut-export.png") },
        onAddEffect = viewModel::addEffect,
        onEnable = viewModel::setEnabled,
        onSolo = viewModel::setSolo,
        onMove = viewModel::moveEffect,
        onRemove = viewModel::remove,
        onSelect = viewModel::select,
        onParameter = viewModel::setParameter,
        onOpacity = viewModel::setOpacity,
        onShowSource = viewModel::setShowSource,
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onClearStatus = viewModel::clearMessage,
    )
}

@Composable
private fun EditorScreen(
    state: EditorUiState,
    onPickImage: () -> Unit,
    onSaveProject: () -> Unit,
    onOpenProject: () -> Unit,
    onSaveRecipe: () -> Unit,
    onOpenRecipe: () -> Unit,
    onExportPng: () -> Unit,
    onAddEffect: (String) -> Unit,
    onEnable: (String, Boolean) -> Unit,
    onSolo: (String?) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onSelect: (String) -> Unit,
    onParameter: (String, String, ParameterValue) -> Unit,
    onOpacity: (String, Double) -> Unit,
    onShowSource: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearStatus: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(
                state = state,
                onPickImage = onPickImage,
                onSaveProject = onSaveProject,
                onOpenProject = onOpenProject,
                onSaveRecipe = onSaveRecipe,
                onOpenRecipe = onOpenRecipe,
                onExportPng = onExportPng,
                onShowSource = onShowSource,
                onUndo = onUndo,
                onRedo = onRedo,
            )
            StatusBanner(state, onClearStatus)
            HorizontalDivider()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= 1000.dp) {
                    Row(Modifier.fillMaxSize()) {
                        StackPanel(
                            state,
                            onAddEffect,
                            onEnable,
                            onSolo,
                            onMove,
                            onRemove,
                            onSelect,
                            Modifier.width(340.dp).fillMaxHeight(),
                        )
                        DividerLine()
                        Viewport(state, Modifier.weight(1f).fillMaxHeight())
                        DividerLine()
                        Inspector(
                            state,
                            onParameter,
                            onOpacity,
                            Modifier.width(320.dp).fillMaxHeight(),
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Viewport(state, Modifier.weight(1f).fillMaxWidth())
                        HorizontalDivider()
                        Row(Modifier.height(320.dp).fillMaxWidth()) {
                            StackPanel(
                                state,
                                onAddEffect,
                                onEnable,
                                onSolo,
                                onMove,
                                onRemove,
                                onSelect,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                            DividerLine()
                            Inspector(
                                state,
                                onParameter,
                                onOpacity,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    state: EditorUiState,
    onPickImage: () -> Unit,
    onSaveProject: () -> Unit,
    onOpenProject: () -> Unit,
    onSaveRecipe: () -> Unit,
    onOpenRecipe: () -> Unit,
    onExportPng: () -> Unit,
    onShowSource: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onPickImage, enabled = !state.operationInProgress) {
                Text(if (state.project.source == null) "OPEN IMAGE" else "REPLACE IMAGE")
            }
            OutlinedButton(onClick = onOpenProject, enabled = !state.operationInProgress) {
                Text("OPEN PROJECT")
            }
            OutlinedButton(onClick = onSaveProject, enabled = !state.operationInProgress) {
                Text("SAVE PROJECT")
            }
            OutlinedButton(onClick = onOpenRecipe, enabled = !state.operationInProgress) {
                Text("OPEN RECIPE")
            }
            OutlinedButton(onClick = onSaveRecipe, enabled = !state.operationInProgress) {
                Text("SAVE RECIPE")
            }
            Button(
                onClick = onExportPng,
                enabled = state.project.source != null && !state.operationInProgress,
            ) { Text("EXPORT PNG") }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onUndo, enabled = state.canUndo && !state.operationInProgress) {
                Text("UNDO")
            }
            OutlinedButton(onClick = onRedo, enabled = state.canRedo && !state.operationInProgress) {
                Text("REDO")
            }
            OutlinedButton(
                onClick = { onShowSource(!state.showSource) },
                enabled = state.sourceBitmap != null,
            ) { Text(if (state.showSource) "SHOW RESULT" else "SHOW SOURCE") }
            Spacer(Modifier.weight(1f))
            if (state.rendering || state.operationInProgress) {
                CircularProgressIndicator(Modifier.width(24.dp))
            }
            val timing = state.lastRenderDurationMillis?.let { " · ${"%.1f".format(it)} MS" }.orEmpty()
            Text(
                if (state.showSource) "SOURCE" else "PREVIEW$timing",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun StatusBanner(state: EditorUiState, onClearStatus: () -> Unit) {
    val text = state.error ?: state.message ?: return
    val error = state.error != null
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (error) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
            .clickable(onClick = onClearStatus)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            color = if (error) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelMedium,
        )
        Text("DISMISS", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StackPanel(
    state: EditorUiState,
    onAddEffect: (String) -> Unit,
    onEnable: (String, Boolean) -> Unit,
    onSolo: (String?) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(12.dp)) {
        Text("EFFECT CATALOGUE", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BuiltInEffects.registry.all().forEach { definition ->
                Button(onClick = { onAddEffect(definition.type) }) {
                    Text("+ ${definition.displayName}")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("PIPELINE", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.project.effects, key = { it.id }) { effect ->
                EffectRow(
                    effect = effect,
                    selected = state.selectedEffectId == effect.id,
                    solo = state.project.soloEffectId == effect.id,
                    onEnable = onEnable,
                    onSolo = onSolo,
                    onMove = onMove,
                    onRemove = onRemove,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun EffectRow(
    effect: EffectInstance,
    selected: Boolean,
    solo: Boolean,
    onEnable: (String, Boolean) -> Unit,
    onSolo: (String?) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .clickable { onSelect(effect.id) }
            .padding(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = effect.enabled,
                onCheckedChange = { onEnable(effect.id, it) },
                enabled = effect.isResolved,
            )
            Column(Modifier.weight(1f)) {
                Text(effect.type.uppercase())
                if (!effect.isResolved) {
                    Text("UNRESOLVED · RETAINED", style = MaterialTheme.typography.labelSmall)
                }
            }
            OutlinedButton(onClick = { onMove(effect.id, -1) }) { Text("↑") }
            OutlinedButton(onClick = { onMove(effect.id, 1) }) { Text("↓") }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { onSolo(if (solo) null else effect.id) },
                enabled = effect.enabled,
            ) { Text(if (solo) "SHOW ALL" else "SOLO TO HERE") }
            OutlinedButton(onClick = { onRemove(effect.id) }) { Text("REMOVE") }
        }
    }
}

@Composable
private fun Viewport(state: EditorUiState, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = if (state.showSource) state.sourceBitmap else state.renderedBitmap
        if (bitmap == null) {
            Text(
                if (state.project.source == null) "OPEN AN IMAGE TO BEGIN"
                else "SOURCE IS UNAVAILABLE — REPLACE IMAGE OR REOPEN PERMISSION"
            )
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = if (state.showSource) "Source image" else "Rendered image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun Inspector(
    state: EditorUiState,
    onParameter: (String, String, ParameterValue) -> Unit,
    onOpacity: (String, Double) -> Unit,
    modifier: Modifier,
) {
    val selected = state.project.effects.firstOrNull { it.id == state.selectedEffectId }
    LazyColumn(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("INSPECTOR", style = MaterialTheme.typography.titleSmall) }
        if (selected == null) {
            item { Text("Select an effect") }
        } else {
            item {
                Text(selected.type.uppercase(), style = MaterialTheme.typography.titleMedium)
                Text(selected.id, style = MaterialTheme.typography.labelSmall)
            }
            val definition = BuiltInEffects.registry.definition(selected.type)
            if (definition == null || !selected.isResolved) {
                item {
                    Text("This node is not executable in the current build. Its original JSON remains in the project and recipe.")
                }
            } else {
                item { Text("Algorithm: ${definition.algorithmVersion}") }
                item {
                    Text("OPACITY ${"%.2f".format(selected.opacity)}")
                    Slider(
                        value = selected.opacity.toFloat(),
                        onValueChange = { onOpacity(selected.id, it.toDouble()) },
                        valueRange = 0f..1f,
                    )
                }
                definition.parameters.forEach { parameter ->
                    item(key = parameter.key) {
                        ParameterControl(selected, parameter, onParameter)
                    }
                }
                if (definition.parameters.isEmpty()) {
                    item { Text("No effect-specific parameters.") }
                }
            }
        }
    }
}

@Composable
private fun ParameterControl(
    effect: EffectInstance,
    definition: ParameterDefinition,
    onParameter: (String, String, ParameterValue) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (definition) {
            is ParameterDefinition.Decimal -> {
                val current = (effect.parameters[definition.key] as? ParameterValue.Decimal)?.value
                    ?: definition.default.value
                Text("${definition.label} ${"%.3f".format(current)}${definition.unit.orEmpty()}")
                Slider(
                    value = current.toFloat(),
                    onValueChange = { raw ->
                        val snapped = (
                            ((raw.toDouble() - definition.minimum) / definition.step).roundToInt() *
                                definition.step + definition.minimum
                            ).coerceIn(definition.minimum, definition.maximum)
                        onParameter(effect.id, definition.key, ParameterValue.Decimal(snapped))
                    },
                    valueRange = definition.minimum.toFloat()..definition.maximum.toFloat(),
                )
            }
            is ParameterDefinition.Integer -> {
                val current = (effect.parameters[definition.key] as? ParameterValue.Integer)?.value
                    ?: definition.default.value
                Text("${definition.label} $current")
                Slider(
                    value = current.toFloat(),
                    onValueChange = { raw ->
                        val snapped = (
                            ((raw.roundToInt() - definition.minimum) / definition.step) *
                                definition.step + definition.minimum
                            ).coerceIn(definition.minimum, definition.maximum)
                        onParameter(effect.id, definition.key, ParameterValue.Integer(snapped))
                    },
                    valueRange = definition.minimum.toFloat()..definition.maximum.toFloat(),
                    steps = ((definition.maximum - definition.minimum) / definition.step - 1).coerceAtLeast(0),
                )
            }
            is ParameterDefinition.Toggle -> {
                val current = (effect.parameters[definition.key] as? ParameterValue.Toggle)?.value
                    ?: definition.default.value
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(definition.label, Modifier.weight(1f))
                    Switch(
                        checked = current,
                        onCheckedChange = {
                            onParameter(effect.id, definition.key, ParameterValue.Toggle(it))
                        },
                    )
                }
            }
            is ParameterDefinition.Choice -> {
                val current = (effect.parameters[definition.key] as? ParameterValue.Choice)?.value
                    ?: definition.default.value
                Text(definition.label)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    definition.choices.forEach { choice ->
                        if (choice == current) {
                            Button(onClick = {}) { Text(choice) }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    onParameter(effect.id, definition.key, ParameterValue.Choice(choice))
                                },
                            ) { Text(choice) }
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { onParameter(effect.id, definition.key, definition.default) },
        ) { Text("RESET ${definition.label}") }
    }
}

@Composable
private fun DividerLine() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
