package com.cyrusublerman.distaut.editor

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.model.EditorCommand
import com.cyrusublerman.distaut.model.ProjectHistory
import com.cyrusublerman.distaut.model.ProjectState
import com.cyrusublerman.distaut.model.SourceAsset
import com.cyrusublerman.distaut.render.PixelBuffer
import com.cyrusublerman.distaut.render.RenderQuality
import com.cyrusublerman.distaut.render.RenderRequest
import com.cyrusublerman.distaut.render.kotlin.KotlinPipelineRenderer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EditorUiState(
    val project: ProjectState = ProjectState(),
    val sourceBitmap: Bitmap? = null,
    val renderedBitmap: Bitmap? = null,
    val rendering: Boolean = false,
    val error: String? = null,
    val selectedEffectId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

class EditorViewModel : ViewModel() {
    private val renderer = KotlinPipelineRenderer()
    private val history = ProjectHistory(ProjectState())
    private val generation = AtomicLong(0L)
    private var renderJob: Job? = null

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    fun setSource(uri: String, bitmap: Bitmap) {
        val source = SourceAsset(
            uri = uri,
            width = bitmap.width,
            height = bitmap.height,
        )
        dispatch(EditorCommand.SetSource(source), render = false)
        _uiState.value = _uiState.value.copy(sourceBitmap = bitmap, renderedBitmap = bitmap)
        scheduleRender()
    }

    fun addGreyscale() {
        if (history.current.effects.any { it.type == BuiltInEffects.Greyscale.type }) return
        val effect = BuiltInEffects.Greyscale.instantiate(UUID.randomUUID().toString())
        dispatch(EditorCommand.AddEffect(effect))
        _uiState.value = _uiState.value.copy(selectedEffectId = effect.id)
    }

    fun setEnabled(effectId: String, enabled: Boolean) =
        dispatch(EditorCommand.SetEffectEnabled(effectId, enabled))

    fun setSolo(effectId: String?) = dispatch(EditorCommand.SetSoloEffect(effectId))

    fun remove(effectId: String) {
        dispatch(EditorCommand.RemoveEffect(effectId))
        if (_uiState.value.selectedEffectId == effectId) {
            _uiState.value = _uiState.value.copy(selectedEffectId = null)
        }
    }

    fun select(effectId: String) {
        _uiState.value = _uiState.value.copy(selectedEffectId = effectId)
    }

    fun undo() {
        history.undo()
        publishHistoryState()
        scheduleRender()
    }

    fun redo() {
        history.redo()
        publishHistoryState()
        scheduleRender()
    }

    private fun dispatch(command: EditorCommand, render: Boolean = true) {
        history.dispatch(command)
        publishHistoryState()
        if (render) scheduleRender()
    }

    private fun publishHistoryState() {
        _uiState.value = _uiState.value.copy(
            project = history.current,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    private fun scheduleRender() {
        val sourceBitmap = _uiState.value.sourceBitmap ?: return
        val project = history.current
        val currentGeneration = generation.incrementAndGet()
        renderJob?.cancel()
        _uiState.value = _uiState.value.copy(rendering = true, error = null)

        renderJob = viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val source = sourceBitmap.toPixelBuffer()
                renderer.render(
                    RenderRequest(
                        generation = currentGeneration,
                        sourceRevision = project.revision,
                        source = source,
                        effects = project.activeEffects(),
                        quality = RenderQuality.PREVIEW,
                        globalSeed = project.globalSeed,
                    )
                )
            }.onSuccess { result ->
                if (generation.get() == result.generation) {
                    _uiState.value = _uiState.value.copy(
                        renderedBitmap = result.output.toBitmap(),
                        rendering = false,
                    )
                }
            }.onFailure { error ->
                if (generation.get() == currentGeneration) {
                    _uiState.value = _uiState.value.copy(
                        rendering = false,
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
            }
        }
    }
}

private fun Bitmap.toPixelBuffer(): PixelBuffer {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val rgba = ByteArray(width * height * 4)
    var byteIndex = 0
    for (argb in pixels) {
        rgba[byteIndex++] = ((argb shr 16) and 0xff).toByte()
        rgba[byteIndex++] = ((argb shr 8) and 0xff).toByte()
        rgba[byteIndex++] = (argb and 0xff).toByte()
        rgba[byteIndex++] = ((argb ushr 24) and 0xff).toByte()
    }
    return PixelBuffer(width, height, rgba)
}

private fun PixelBuffer.toBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    var byteIndex = 0
    for (i in pixels.indices) {
        val r = rgba[byteIndex++].toInt() and 0xff
        val g = rgba[byteIndex++].toInt() and 0xff
        val b = rgba[byteIndex++].toInt() and 0xff
        val a = rgba[byteIndex++].toInt() and 0xff
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
