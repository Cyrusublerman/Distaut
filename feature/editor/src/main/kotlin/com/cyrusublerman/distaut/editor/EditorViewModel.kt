package com.cyrusublerman.distaut.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.model.EditorCommand
import com.cyrusublerman.distaut.model.ParameterValue
import com.cyrusublerman.distaut.model.ProjectHistory
import com.cyrusublerman.distaut.model.ProjectState
import com.cyrusublerman.distaut.projects.ProjectStore
import com.cyrusublerman.distaut.projects.SourceAssetLoader
import com.cyrusublerman.distaut.recipes.RecipeMapper
import com.cyrusublerman.distaut.render.CancellationProbe
import com.cyrusublerman.distaut.render.PixelBuffer
import com.cyrusublerman.distaut.render.RenderCancelledException
import com.cyrusublerman.distaut.render.RenderQuality
import com.cyrusublerman.distaut.render.RenderRequest
import com.cyrusublerman.distaut.render.kotlin.KotlinPipelineRenderer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ENGINE_VERSION = "0.2.0-dev"

data class EditorUiState(
    val project: ProjectState = ProjectState(),
    val sourceBitmap: Bitmap? = null,
    val renderedBitmap: Bitmap? = null,
    val rendering: Boolean = false,
    val operationInProgress: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val selectedEffectId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val showSource: Boolean = false,
    val lastRenderDurationMillis: Double? = null,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val renderer = KotlinPipelineRenderer()
    private val history = ProjectHistory(ProjectState())
    private val generation = AtomicLong(0L)
    private val projectStore = ProjectStore(application)
    private val sourceLoader = SourceAssetLoader(application)
    private var renderJob: Job? = null
    private var autosaveJob: Job? = null

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    init {
        restoreAutosave()
    }

    fun importSource(uri: Uri) {
        viewModelScope.launch {
            setOperation(true, "IMPORTING IMAGE")
            runCatching { sourceLoader.importSource(uri) }
                .onSuccess { loaded ->
                    dispatch(EditorCommand.SetSource(loaded.asset), render = false)
                    _uiState.update {
                        it.copy(
                            sourceBitmap = loaded.bitmap,
                            renderedBitmap = loaded.bitmap,
                            operationInProgress = false,
                            message = "IMPORTED ${loaded.asset.displayName ?: "IMAGE"}",
                            error = null,
                        )
                    }
                    scheduleRender()
                    scheduleAutosave()
                }
                .onFailure { failOperation("Image import failed", it) }
        }
    }

    fun addEffect(type: String) {
        val definition = BuiltInEffects.registry.definition(type) ?: return
        val effect = definition.instantiate(UUID.randomUUID().toString())
        dispatch(EditorCommand.AddEffect(effect))
        _uiState.update { it.copy(selectedEffectId = effect.id) }
    }

    fun setEnabled(effectId: String, enabled: Boolean) =
        dispatch(EditorCommand.SetEffectEnabled(effectId, enabled))

    fun setSolo(effectId: String?) = dispatch(EditorCommand.SetSoloEffect(effectId))

    fun setParameter(effectId: String, key: String, value: ParameterValue) =
        dispatch(EditorCommand.SetParameter(effectId, key, value))

    fun setOpacity(effectId: String, opacity: Double) =
        dispatch(EditorCommand.SetOpacity(effectId, opacity))

    fun moveEffect(effectId: String, delta: Int) {
        val index = history.current.effects.indexOfFirst { it.id == effectId }
        if (index < 0) return
        dispatch(EditorCommand.MoveEffect(effectId, index + delta))
    }

    fun remove(effectId: String) {
        dispatch(EditorCommand.RemoveEffect(effectId))
        if (_uiState.value.selectedEffectId == effectId) {
            _uiState.update { it.copy(selectedEffectId = null) }
        }
    }

    fun select(effectId: String) {
        _uiState.update { it.copy(selectedEffectId = effectId) }
    }

    fun setShowSource(show: Boolean) {
        _uiState.update { it.copy(showSource = show) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }

    fun undo() {
        history.undo()
        publishHistoryState()
        scheduleRender()
        scheduleAutosave()
    }

    fun redo() {
        history.redo()
        publishHistoryState()
        scheduleRender()
        scheduleAutosave()
    }

    fun saveProject(uri: Uri) {
        viewModelScope.launch {
            setOperation(true, "SAVING PROJECT")
            runCatching { projectStore.saveProject(uri, history.current, ENGINE_VERSION) }
                .onSuccess {
                    setOperation(false, "PROJECT SAVED")
                    scheduleAutosave()
                }
                .onFailure { failOperation("Project save failed", it) }
        }
    }

    fun openProject(uri: Uri) {
        viewModelScope.launch {
            setOperation(true, "OPENING PROJECT")
            runCatching { projectStore.loadProject(uri) }
                .onSuccess { document ->
                    history.replace(document.project)
                    publishHistoryState()
                    _uiState.update {
                        it.copy(
                            selectedEffectId = document.project.effects.firstOrNull()?.id,
                            sourceBitmap = null,
                            renderedBitmap = null,
                            error = null,
                        )
                    }
                    setOperation(false, "PROJECT OPENED")
                    reopenProjectSource(document.project)
                    scheduleAutosave()
                }
                .onFailure { failOperation("Project open failed", it) }
        }
    }

    fun saveRecipe(uri: Uri) {
        viewModelScope.launch {
            setOperation(true, "SAVING RECIPE")
            runCatching {
                projectStore.saveRecipe(uri, RecipeMapper.fromProject(history.current, ENGINE_VERSION))
            }.onSuccess {
                setOperation(false, "RECIPE SAVED")
            }.onFailure { failOperation("Recipe save failed", it) }
        }
    }

    fun openRecipe(uri: Uri) {
        viewModelScope.launch {
            setOperation(true, "OPENING RECIPE")
            runCatching { projectStore.loadRecipe(uri) }
                .onSuccess { imported ->
                    val next = RecipeMapper.applyToProject(imported.recipe, history.current)
                    history.replace(next, clearHistory = false)
                    publishHistoryState()
                    _uiState.update {
                        it.copy(
                            selectedEffectId = next.effects.firstOrNull()?.id,
                            operationInProgress = false,
                            message = "RECIPE OPENED — ${next.effects.count { effect -> !effect.isResolved }} UNRESOLVED · ${imported.warnings.size} WARNINGS",
                            error = null,
                        )
                    }
                    scheduleRender()
                    scheduleAutosave()
                }
                .onFailure { failOperation("Recipe open failed", it) }
        }
    }

    fun exportPng(uri: Uri) {
        val asset = history.current.source ?: run {
            _uiState.update { it.copy(error = "Open an image before exporting") }
            return
        }
        viewModelScope.launch {
            setOperation(true, "RENDERING FINAL PNG")
            runCatching {
                val fullBitmap = sourceLoader.loadFull(asset)
                val source = withContext(Dispatchers.Default) { fullBitmap.toPixelBuffer() }
                fullBitmap.recycle()
                val context = currentCoroutineContext()
                val currentGeneration = generation.incrementAndGet()
                val result = withContext(Dispatchers.Default) {
                    renderer.render(
                        RenderRequest(
                            generation = currentGeneration,
                            sourceRevision = history.current.revision,
                            source = source,
                            effects = history.current.activeEffects(),
                            quality = RenderQuality.FINAL,
                            globalSeed = history.current.globalSeed,
                            cancellationProbe = CancellationProbe { !context.isActive },
                        )
                    )
                }
                val output = withContext(Dispatchers.Default) { result.output.toBitmap() }
                projectStore.exportPng(uri, output)
                output.recycle()
                result
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        operationInProgress = false,
                        message = "PNG EXPORTED — ${"%.1f".format(result.durationNanos / 1_000_000.0)} MS",
                        error = null,
                    )
                }
            }.onFailure { failOperation("PNG export failed", it) }
        }
    }

    private fun restoreAutosave() {
        viewModelScope.launch {
            runCatching { projectStore.loadAutosave() }
                .onSuccess { document ->
                    if (document != null) {
                        history.replace(document.project)
                        publishHistoryState()
                        _uiState.update {
                            it.copy(
                                selectedEffectId = document.project.effects.firstOrNull()?.id,
                                message = "AUTOSAVE RESTORED",
                            )
                        }
                        reopenProjectSource(document.project)
                    }
                }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(error = "Autosave could not be restored: ${it.message}")
                    }
                }
        }
    }

    private suspend fun reopenProjectSource(project: ProjectState) {
        val source = project.source ?: return
        runCatching { sourceLoader.reopen(source) }
            .onSuccess { bitmap ->
                _uiState.update {
                    it.copy(sourceBitmap = bitmap, renderedBitmap = bitmap, error = null)
                }
                scheduleRender()
            }
            .onFailure {
                _uiState.update { state ->
                    state.copy(
                        sourceBitmap = null,
                        renderedBitmap = null,
                        error = "Source unavailable: ${it.message ?: source.uri}",
                    )
                }
            }
    }

    private fun dispatch(command: EditorCommand, render: Boolean = true) {
        history.dispatch(command)
        publishHistoryState()
        if (render) scheduleRender()
        scheduleAutosave()
    }

    private fun publishHistoryState() {
        val selected = _uiState.value.selectedEffectId
            ?.takeIf { id -> history.current.effects.any { it.id == id } }
        _uiState.update {
            it.copy(
                project = history.current,
                selectedEffectId = selected,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
            )
        }
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(350)
            runCatching { projectStore.saveAutosave(history.current, ENGINE_VERSION) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(error = "Autosave failed: ${error.message ?: error::class.java.simpleName}")
                    }
                }
        }
    }

    private fun scheduleRender() {
        val sourceBitmap = _uiState.value.sourceBitmap ?: return
        val project = history.current
        val currentGeneration = generation.incrementAndGet()
        renderJob?.cancel()
        _uiState.update { it.copy(rendering = true, error = null) }

        renderJob = viewModelScope.launch(Dispatchers.Default) {
            val context = currentCoroutineContext()
            runCatching {
                renderer.render(
                    RenderRequest(
                        generation = currentGeneration,
                        sourceRevision = project.revision,
                        source = sourceBitmap.toPixelBuffer(),
                        effects = project.activeEffects(),
                        quality = RenderQuality.PREVIEW,
                        globalSeed = project.globalSeed,
                        cancellationProbe = CancellationProbe { !context.isActive },
                    )
                )
            }.onSuccess { result ->
                if (generation.get() == result.generation) {
                    _uiState.update {
                        it.copy(
                            renderedBitmap = result.output.toBitmap(),
                            rendering = false,
                            lastRenderDurationMillis = result.durationNanos / 1_000_000.0,
                        )
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException || error is RenderCancelledException) return@onFailure
                if (generation.get() == currentGeneration) {
                    _uiState.update {
                        it.copy(
                            rendering = false,
                            error = error.message ?: error::class.java.simpleName,
                        )
                    }
                }
            }
        }
    }

    private fun setOperation(inProgress: Boolean, message: String) {
        _uiState.update {
            it.copy(operationInProgress = inProgress, message = message, error = null)
        }
    }

    private fun failOperation(prefix: String, error: Throwable) {
        if (error is CancellationException) return
        _uiState.update {
            it.copy(
                operationInProgress = false,
                error = "$prefix: ${error.message ?: error::class.java.simpleName}",
            )
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
