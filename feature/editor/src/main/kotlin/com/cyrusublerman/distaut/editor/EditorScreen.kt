package com.cyrusublerman.distaut.editor

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun EditorRoute(viewModel: EditorViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.let { viewModel.setSource(uri.toString(), it) }
            }
        }
    }

    EditorScreen(
        state = state,
        onPickImage = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onAddGreyscale = viewModel::addGreyscale,
        onEnable = viewModel::setEnabled,
        onSolo = viewModel::setSolo,
        onRemove = viewModel::remove,
        onSelect = viewModel::select,
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
    )
}

@Composable
private fun EditorScreen(
    state: EditorUiState,
    onPickImage: () -> Unit,
    onAddGreyscale: () -> Unit,
    onEnable: (String, Boolean) -> Unit,
    onSolo: (String?) -> Unit,
    onRemove: (String) -> Unit,
    onSelect: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(state, onPickImage, onUndo, onRedo)
            HorizontalDivider()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= 1000.dp) {
                    Row(Modifier.fillMaxSize()) {
                        StackPanel(
                            state,
                            onAddGreyscale,
                            onEnable,
                            onSolo,
                            onRemove,
                            onSelect,
                            Modifier.width(320.dp).fillMaxHeight(),
                        )
                        Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Viewport(state, Modifier.weight(1f).fillMaxHeight())
                        Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Inspector(state, Modifier.width(300.dp).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Viewport(state, Modifier.weight(1f).fillMaxWidth())
                        HorizontalDivider()
                        Row(Modifier.height(280.dp).fillMaxWidth()) {
                            StackPanel(
                                state,
                                onAddGreyscale,
                                onEnable,
                                onSolo,
                                onRemove,
                                onSelect,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                            Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            Inspector(state, Modifier.weight(1f).fillMaxHeight())
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
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onPickImage) { Text(if (state.project.source == null) "OPEN IMAGE" else "REPLACE") }
        OutlinedButton(onClick = onUndo, enabled = state.canUndo) { Text("UNDO") }
        OutlinedButton(onClick = onRedo, enabled = state.canRedo) { Text("REDO") }
        Spacer(Modifier.weight(1f))
        if (state.rendering) CircularProgressIndicator(Modifier.width(24.dp))
        Text("PREVIEW", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StackPanel(
    state: EditorUiState,
    onAddGreyscale: () -> Unit,
    onEnable: (String, Boolean) -> Unit,
    onSolo: (String?) -> Unit,
    onRemove: (String) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(12.dp)) {
        Text("PIPELINE", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAddGreyscale, modifier = Modifier.fillMaxWidth()) { Text("+ GREYSCALE") }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.project.effects, key = { it.id }) { effect ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .background(
                            if (state.selectedEffectId == effect.id) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable { onSelect(effect.id) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = effect.enabled, onCheckedChange = { onEnable(effect.id, it) })
                    Text(effect.type.uppercase(), Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            onSolo(if (state.project.soloEffectId == effect.id) null else effect.id)
                        },
                    ) { Text(if (state.project.soloEffectId == effect.id) "ALL" else "SOLO") }
                    OutlinedButton(onClick = { onRemove(effect.id) }) { Text("×") }
                }
            }
        }
    }
}

@Composable
private fun Viewport(state: EditorUiState, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = state.renderedBitmap
        if (bitmap == null) {
            Text("OPEN AN IMAGE TO BEGIN")
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Rendered image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        state.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
    }
}

@Composable
private fun Inspector(state: EditorUiState, modifier: Modifier) {
    Column(modifier.padding(12.dp)) {
        Text("INSPECTOR", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))
        val selected = state.project.effects.firstOrNull { it.id == state.selectedEffectId }
        if (selected == null) {
            Text("Select an effect")
        } else {
            Text(selected.type.uppercase(), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Algorithm: rec709-encoded-v1")
            Text("Opacity and blending enter after the base render contract is validated.")
        }
    }
}
