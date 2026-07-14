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
import java.io.IOException
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

private const val ENGINE_VERSION = "0.3.0-dev"

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
    private val sourceLoader = SourceAssetLoader(application) { message, error ->
        if (error == null) DiagnosticsLog.info("source", message)
        else DiagnosticsLog.warning("source", message, error)
    }
    private var renderJob: Job? = null
    private var autosaveJob: Job? = null

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    init {
        DiagnosticsLog.info("session", "Distaut $ENGINE_VERSION started")
        restoreAutosave()
    }

    fun importSource(uri: Uri) = viewModelScope.launch {
        setOperation(true, "IMPORTING IMAGE")
        DiagnosticsLog.info("import", "Picker URI: ${uri.scheme}://${uri.authority}")
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
                DiagnosticsLog.info(
                    "import",
                    "Ready ${loaded.asset.width}x${loaded.asset.height} " +
                        "${loaded.asset.mimeType ?: "unknown"}",
                )
                scheduleRender()
            }
            .onFailure { failOperation("Image import failed", it) }
    }

    fun addEffect(type: String) {
        val definition = BuiltInEffects.registry.definition(type) ?: return
        val effect = definition.instantiate(UUID.randomUUID().toString())
        dispatch(EditorCommand.AddEffect(effect))
        _uiState.update { it.copy(selectedEffectId = effect.id) }
        DiagnosticsLog.info("editor", "Added ${definition.displayName}")
    }

    fun setEnabled(id: String, enabled: Boolean) =
        dispatch(EditorCommand.SetEffectEnabled(id, enabled))

    fun setSolo(id: String?) = dispatch(EditorCommand.SetSoloEffect(id))

    fun setParameter(id: String, key: String, value: ParameterValue) =
        dispatch(EditorCommand.SetParameter(id, key, value))

    fun setOpacity(id: String, opacity: Double) =
        dispatch(EditorCommand.SetOpacity(id, opacity))

    fun moveEffect(id: String, delta: Int) {
        val index = history.current.effects.indexOfFirst { it.id == id }
        if (index >= 0) dispatch(EditorCommand.MoveEffect(id, index + delta))
    }

    fun remove(id: String) {
        dispatch(EditorCommand.RemoveEffect(id))
        if (_uiState.value.selectedEffectId == id) {
            _uiState.update { it.copy(selectedEffectId = null) }
        }
    }

    fun select(id: String) = _uiState.update { state ->
        state.copy(selectedEffectId = id.takeUnless { it == state.selectedEffectId })
    }

    fun setShowSource(show: Boolean) = _uiState.update { it.copy(showSource = show) }
    fun clearMessage() = _uiState.update { it.copy(message = null, error = null) }
    fun clearDiagnostics() = DiagnosticsLog.clear()

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

    fun saveProject(uri: Uri) = launchOperation("SAVING PROJECT", "PROJECT SAVED") {
        projectStore.saveProject(uri, history.current, ENGINE_VERSION)
        DiagnosticsLog.info("project", "Project saved")
    }

    fun openProject(uri: Uri) = viewModelScope.launch {
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
                    )
                }
                setOperation(false, "PROJECT OPENED")
                DiagnosticsLog.info(
                    "project",
                    "Opened revision=${document.project.revision} " +
                        "effects=${document.project.effects.size}",
                )
                reopenProjectSource(document.project)
            }
            .onFailure { failOperation("Project open failed", it) }
    }

    fun saveRecipe(uri: Uri) = launchOperation("SAVING RECIPE", "RECIPE SAVED") {
        projectStore.saveRecipe(
            uri,
            RecipeMapper.fromProject(history.current, ENGINE_VERSION),
        )
    }

    fun openRecipe(uri: Uri) = viewModelScope.launch {
        setOperation(true, "OPENING RECIPE")
        runCatching { projectStore.loadRecipe(uri) }
            .onSuccess { imported ->
                val next = RecipeMapper.applyToProject(imported.recipe, history.current)
                history.replace(next, clearHistory = false)
                publishHistoryState()
                val unresolved = next.effects.count { !it.isResolved }
                _uiState.update {
                    it.copy(
                        selectedEffectId = next.effects.firstOrNull()?.id,
                        operationInProgress = false,
                        message = "RECIPE OPENED · $unresolved UNRESOLVED · " +
                            "${imported.warnings.size} WARNINGS",
                        error = null,
                    )
                }
                DiagnosticsLog.info(
                    "recipe",
                    "Loaded ${next.effects.size} effects; unresolved=$unresolved",
                )
                scheduleRender()
                scheduleAutosave()
            }
            .onFailure { failOperation("Recipe open failed", it) }
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
                val result = withContext(Dispatchers.Default) {
                    renderer.render(
                        RenderRequest(
                            generation = generation.incrementAndGet(),
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
                val ms = result.durationNanos / 1_000_000.0
                setOperation(false, "PNG EXPORTED · ${"%.1f".format(ms)} MS")
                DiagnosticsLog.info("export", "PNG exported in ${"%.1f".format(ms)} ms")
            }.onFailure { failOperation("PNG export failed", it) }
        }
    }

    fun exportDiagnostics(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val output = getApplication<Application>().contentResolver
                .openOutputStream(uri, "wt")
                ?: throw IOException("Unable to open diagnostic destination")
            output.bufferedWriter(Charsets.UTF_8).use {
                it.write(DiagnosticsLog.report(getApplication(), history.current))
            }
        }.onSuccess {
            DiagnosticsLog.info("diagnostics", "Report exported")
            _uiState.update { it.copy(message = "DIAGNOSTIC REPORT EXPORTED") }
        }.onFailure { failOperation("Diagnostic export failed", it) }
    }

    fun runSelfCheck() = viewModelScope.launch {
        DiagnosticsLog.info("self-check", "Started")
        var failures = 0
        runCatching {
            val dir = getApplication<Application>().filesDir
            check(dir.isDirectory && dir.canWrite())
            DiagnosticsLog.info("self-check", "Storage OK; free=${dir.freeSpace}")
        }.onFailure {
            failures++
            DiagnosticsLog.error("self-check", "Storage failed", it)
        }

        history.current.source?.let { source ->
            runCatching { sourceLoader.probe(source) }
                .onSuccess { DiagnosticsLog.info("self-check", it) }
                .onFailure {
                    failures++
                    DiagnosticsLog.error("self-check", "Source failed", it)
                }
        } ?: DiagnosticsLog.warning("self-check", "No source loaded")

        runCatching {
            val result = withContext(Dispatchers.Default) {
                renderer.render(
                    RenderRequest(
                        generation = generation.incrementAndGet(),
                        sourceRevision = history.current.revision,
                        source = PixelBuffer(
                            1,
                            1,
                            byteArrayOf(255.toByte(), 0, 0, 255.toByte()),
                        ),
                        effects = listOf(BuiltInEffects.Greyscale.instantiate("self-check")),
                        quality = RenderQuality.PREVIEW,
                        globalSeed = 0L,
                    )
                )
            }
            check((result.output.rgba[0].toInt() and 0xff) == 54)
            DiagnosticsLog.info("self-check", "Renderer fixture OK")
        }.onFailure {
            failures++
            DiagnosticsLog.error("self-check", "Renderer failed", it)
        }

        val message = if (failures == 0) "SELF-CHECK PASSED"
        else "SELF-CHECK FAILED · $failures"
        DiagnosticsLog.info("self-check", message)
        _uiState.update {
            it.copy(
                message = message,
                error = if (failures == 0) null else "Open DEBUG for details",
            )
        }
    }

    private fun launchOperation(
        start: String,
        success: String,
        block: suspend () -> Unit,
    ) = viewModelScope.launch {
        setOperation(true, start)
        runCatching { block() }
            .onSuccess { setOperation(false, success) }
            .onFailure { failOperation(start.lowercase().replaceFirstChar(Char::uppercase), it) }
    }

    private fun restoreAutosave() = viewModelScope.launch {
        DiagnosticsLog.info("autosave", "Checking")
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
                    DiagnosticsLog.info(
                        "autosave",
                        "Restored revision=${document.project.revision}",
                    )
                    reopenProjectSource(document.project)
                }
            }
            .onFailure { failOperation("Autosave restore failed", it) }
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
            .onFailure { failOperation("Source reopen failed", it) }
    }

    private fun dispatch(command: EditorCommand, render: Boolean = true) {
        history.dispatch(command)
        publishHistoryState()
        DiagnosticsLog.debug(
            "command",
            "${command::class.java.simpleName} -> rev ${history.current.revision}",
        )
        if (render) scheduleRender()
        scheduleAutosave()
    }

    private fun publishHistoryState() {
        val selected = _uiState.value.selectedEffectId?.takeIf { id ->
            history.current.effects.any { it.id == id }
        }
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
                .onFailure { failOperation("Autosave failed", it) }
        }
    }

    private fun scheduleRender() {
        val sourceBitmap = _uiState.value.sourceBitmap ?: return
        val project = history.current
        val currentGeneration = generation.incrementAndGet()
        renderJob?.cancel()
        _uiState.update { it.copy(rendering = true, error = null) }
        DiagnosticsLog.debug(
            "render",
            "Start gen=$currentGeneration rev=${project.revision} " +
                "${sourceBitmap.width}x${sourceBitmap.height}",
        )

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
                    val ms = result.durationNanos / 1_000_000.0
                    _uiState.update {
                        it.copy(
                            renderedBitmap = result.output.toBitmap(),
                            rendering = false,
                            lastRenderDurationMillis = ms,
                        )
                    }
                    DiagnosticsLog.debug(
                        "render",
                        "Complete gen=${result.generation} ${"%.1f".format(ms)} ms",
                    )
                } else {
                    DiagnosticsLog.debug("render", "Discarded stale gen=${result.generation}")
                }
            }.onFailure { error ->
                if (error is CancellationException || error is RenderCancelledException) {
                    DiagnosticsLog.debug("render", "Cancelled gen=$currentGeneration")
                } else if (generation.get() == currentGeneration) {
                    DiagnosticsLog.error("render", "Failed gen=$currentGeneration", error)
                    _uiState.update {
                        it.copy(
                            rendering = false,
                            error = "${error::class.java.simpleName}: ${error.message}",
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
        val summary = "$prefix: ${error::class.java.simpleName}: " +
            (error.message ?: "No message")
        DiagnosticsLog.error("operation", summary, error)
        _uiState.update { it.copy(operationInProgress = false, error = summary) }
    }
}

private fun Bitmap.toPixelBuffer(): PixelBuffer {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val rgba = ByteArray(width * height * 4)
    var i = 0
    for (argb in pixels) {
        rgba[i++] = ((argb shr 16) and 0xff).toByte()
        rgba[i++] = ((argb shr 8) and 0xff).toByte()
        rgba[i++] = (argb and 0xff).toByte()
        rgba[i++] = ((argb ushr 24) and 0xff).toByte()
    }
    return PixelBuffer(width, height, rgba)
}

private fun PixelBuffer.toBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    var i = 0
    for (pixel in pixels.indices) {
        val r = rgba[i++].toInt() and 0xff
        val g = rgba[i++].toInt() and 0xff
        val b = rgba[i++].toInt() and 0xff
        val a = rgba[i++].toInt() and 0xff
        pixels[pixel] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
