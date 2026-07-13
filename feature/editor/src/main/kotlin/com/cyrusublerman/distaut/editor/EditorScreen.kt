package com.cyrusublerman.distaut.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        it?.let(viewModel::importSource)
    }
    val projectSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { it?.let(viewModel::saveProject) }
    val projectOpener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::openProject)
    }
    val recipeSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { it?.let(viewModel::saveRecipe) }
    val recipeOpener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::openRecipe)
    }
    val pngExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { it?.let(viewModel::exportPng) }

    EditorScreen(
        state = state,
        pickImage = {
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        saveProject = { projectSaver.launch("distaut-project.json") },
        openProject = { projectOpener.launch(arrayOf("application/json", "text/plain")) },
        saveRecipe = { recipeSaver.launch("distaut-recipe-v2.json") },
        openRecipe = { recipeOpener.launch(arrayOf("application/json", "text/plain")) },
        exportPng = { pngExporter.launch("distaut-export.png") },
        viewModel = viewModel,
    )
}

@Composable
private fun EditorScreen(
    state: EditorUiState,
    pickImage: () -> Unit,
    saveProject: () -> Unit,
    openProject: () -> Unit,
    saveRecipe: () -> Unit,
    openRecipe: () -> Unit,
    exportPng: () -> Unit,
    viewModel: EditorViewModel,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(
                state,
                pickImage,
                saveProject,
                openProject,
                saveRecipe,
                openRecipe,
                exportPng,
                viewModel,
            )
            state.error?.let { Status(it, true, viewModel::clearMessage) }
            state.message?.let { Status(it, false, viewModel::clearMessage) }
            HorizontalDivider()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= 900.dp) {
                    val viewportWidth = (maxWidth - 640.dp).coerceAtLeast(240.dp)
                    Row(Modifier.fillMaxSize()) {
                        StackPanel(state, viewModel, Modifier.width(320.dp).fillMaxHeight())
                        Viewport(state, Modifier.width(viewportWidth).fillMaxHeight())
                        Inspector(state, viewModel, Modifier.width(320.dp).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Viewport(state, Modifier.fillMaxWidth().height(360.dp))
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().height(320.dp)) {
                            StackPanel(state, viewModel, Modifier.fillMaxHeight().fillMaxWidth(0.5f))
                            Inspector(state, viewModel, Modifier.fillMaxSize())
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
    pickImage: () -> Unit,
    saveProject: () -> Unit,
    openProject: () -> Unit,
    saveRecipe: () -> Unit,
    openRecipe: () -> Unit,
    exportPng: () -> Unit,
    viewModel: EditorViewModel,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = pickImage) { Text("IMAGE") }
        OutlinedButton(onClick = openProject) { Text("OPEN") }
        OutlinedButton(onClick = saveProject) { Text("SAVE") }
        OutlinedButton(onClick = openRecipe) { Text("LOAD RECIPE") }
        OutlinedButton(onClick = saveRecipe) { Text("SAVE RECIPE") }
        Button(
            onClick = exportPng,
            enabled = state.project.source != null && !state.operationInProgress,
        ) { Text("EXPORT PNG") }
        OutlinedButton(onClick = viewModel::undo, enabled = state.canUndo) { Text("UNDO") }
        OutlinedButton(onClick = viewModel::redo, enabled = state.canRedo) { Text("REDO") }
        Text("SOURCE")
        Switch(checked = state.showSource, onCheckedChange = viewModel::setShowSource)
        if (state.rendering || state.operationInProgress) {
            CircularProgressIndicator(Modifier.width(24.dp))
        }
    }
}

@Composable
private fun Status(text: String, error: Boolean, clear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (error) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
            .clickable(onClick = clear)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text)
        Text("DISMISS")
    }
}

@Composable
private fun StackPanel(state: EditorUiState, viewModel: EditorViewModel, modifier: Modifier) {
    Column(modifier.padding(12.dp)) {
        Text("EFFECT STACK", style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BuiltInEffects.registry.all().forEach { definition ->
                OutlinedButton(onClick = { viewModel.addEffect(definition.type) }) {
                    Text("+ ${definition.displayName}")
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.project.effects, key = { it.id }) { effect ->
                EffectRow(effect, state, viewModel)
            }
        }
    }
}

@Composable
private fun EffectRow(
    effect: EffectInstance,
    state: EditorUiState,
    viewModel: EditorViewModel,
) {
    val selected = state.selectedEffectId == effect.id
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { viewModel.select(effect.id) }
            .padding(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(BuiltInEffects.registry.definition(effect.type)?.displayName ?: "UNRESOLVED: ${effect.type}")
            Switch(
                checked = effect.enabled,
                onCheckedChange = { viewModel.setEnabled(effect.id, it) },
                enabled = effect.isResolved,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { viewModel.moveEffect(effect.id, -1) }) { Text("↑") }
            OutlinedButton(onClick = { viewModel.moveEffect(effect.id, 1) }) { Text("↓") }
            OutlinedButton(onClick = {
                viewModel.setSolo(if (state.project.soloEffectId == effect.id) null else effect.id)
            }) { Text(if (state.project.soloEffectId == effect.id) "UNSOLO" else "SOLO") }
            OutlinedButton(onClick = { viewModel.remove(effect.id) }) { Text("REMOVE") }
        }
    }
}

@Composable
private fun Viewport(state: EditorUiState, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = if (state.showSource) state.sourceBitmap else state.renderedBitmap
        if (bitmap == null) {
            Text("OPEN AN IMAGE")
        } else {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Processed image preview",
                modifier = Modifier.fillMaxSize().padding(12.dp),
                contentScale = ContentScale.Fit,
            )
        }
        state.lastRenderDurationMillis?.let {
            Text("${"%.1f".format(it)} ms", Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
    }
}

@Composable
private fun Inspector(state: EditorUiState, viewModel: EditorViewModel, modifier: Modifier) {
    val effect = state.project.effects.firstOrNull { it.id == state.selectedEffectId }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("INSPECTOR", style = MaterialTheme.typography.titleMedium)
        if (effect == null) {
            Text("Select an effect")
            return@Column
        }
        Text(BuiltInEffects.registry.definition(effect.type)?.displayName ?: effect.type)
        Text("OPACITY ${(effect.opacity * 100).roundToInt()}%")
        Slider(
            value = effect.opacity.toFloat(),
            onValueChange = { viewModel.setOpacity(effect.id, it.toDouble()) },
            valueRange = 0f..1f,
        )
        BuiltInEffects.registry.definition(effect.type)?.parameters?.forEach { parameter ->
            when (parameter) {
                is ParameterDefinition.Integer -> {
                    val current = (effect.parameters[parameter.key] as? ParameterValue.Integer)?.value
                        ?: parameter.default.value
                    Text("${parameter.label} $current")
                    Slider(
                        value = current.toFloat(),
                        onValueChange = {
                            viewModel.setParameter(
                                effect.id,
                                parameter.key,
                                ParameterValue.Integer(it.roundToInt()),
                            )
                        },
                        valueRange = parameter.minimum.toFloat()..parameter.maximum.toFloat(),
                        steps = ((parameter.maximum - parameter.minimum) / parameter.step - 1)
                            .coerceAtLeast(0),
                    )
                }
                else -> Text("${parameter.label}: control pending")
            }
        }
        if (!effect.isResolved) {
            Text("This node is preserved but cannot execute in this build.")
        }
    }
}
