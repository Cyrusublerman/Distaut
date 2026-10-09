package com.cyrusublerman.distaut.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.effects.normalise
import com.cyrusublerman.distaut.model.*
import com.cyrusublerman.distaut.projects.ProjectStore
import com.cyrusublerman.distaut.projects.SourceAssetLoader
import com.cyrusublerman.distaut.recipes.RecipeMapper
import com.cyrusublerman.distaut.render.*
import com.cyrusublerman.distaut.render.kotlin.KotlinPipelineRenderer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val ENGINE_VERSION = "0.4.0-dev"

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
    val stalePreview: Boolean = false,
    val fullDetail: Boolean = false,
    val compare: Boolean = false,
    val exportMaximumDimension: Int? = null,
    val warnings: List<String> = emptyList(),
    val saved: Boolean = true,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val renderer = KotlinPipelineRenderer(minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16))
    private val history = ProjectHistory(ProjectState())
    private val previewGeneration = AtomicLong()
    private var sourceGeneration = 0L
    private var interactionEpoch = 0L
    private val projectStore = ProjectStore(application)
    private val sourceLoader = SourceAssetLoader(application) { message, error ->
        if (error == null) DiagnosticsLog.info("source", message) else DiagnosticsLog.warning("source", message, error)
    }
    private var renderJob: Job? = null
    private var sourceJob: Job? = null
    private var autosaveJob: Job? = null
    private var boundSource: SourceAsset? = null
    private var sourcePixels: PixelBuffer? = null
    private var exporting = false
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    init { restoreAutosave() }

    fun importSource(uri: Uri) {
        interactionEpoch++
        launchSourceOperation("IMPORTING IMAGE") { ticket ->
            val loaded = sourceLoader.importSource(uri)
            val pixels = withContext(Dispatchers.Default) { loaded.bitmap.toPixelBuffer() }
            if (ticket != sourceGeneration) return@launchSourceOperation
            history.endTransaction()
            history.dispatch(EditorCommand.SetSource(loaded.asset))
            bind(loaded.asset, loaded.bitmap, pixels)
            publishHistoryState()
            _uiState.update { it.copy(fullDetail = false, saved = false) }
            scheduleAutosave()
        }
    }

    /** Relinking retains effects and is itself undoable; the picker makes replacement explicit. */
    fun addEffect(type: String) {
        val definition = BuiltInEffects.registry.definition(type) ?: return
        val effect = definition.instantiate(UUID.randomUUID().toString())
        dispatch(EditorCommand.AddEffect(effect))
        _uiState.update { it.copy(selectedEffectId = effect.id) }
    }
    fun setEnabled(id: String, enabled: Boolean) = dispatch(EditorCommand.SetEffectEnabled(id, enabled))
    fun setSolo(id: String?) = dispatch(EditorCommand.SetSoloEffect(id))
    fun setParameter(id: String, key: String, value: ParameterValue) {
        val effect = history.current.effects.firstOrNull { it.id == id } ?: return
        val parameter = BuiltInEffects.registry.definition(effect.type)?.parameters?.firstOrNull { it.key == key } ?: return
        try { dispatch(EditorCommand.SetParameter(id, key, parameter.normalise(value)), gesture = true) }
        catch (error: IllegalArgumentException) { failOperation("Invalid parameter", error) }
    }
    fun setOpacity(id: String, opacity: Double) = dispatch(EditorCommand.SetOpacity(id, opacity), gesture = true)
    fun finishAdjustment() { history.endTransaction(); publishHistoryState(); scheduleAutosave(); scheduleRender() }
    fun resetParameter(id: String, key: String) {
        history.endTransaction()
        val effect = history.current.effects.firstOrNull { it.id == id } ?: return
        val parameter = BuiltInEffects.registry.definition(effect.type)?.parameters?.firstOrNull { it.key == key } ?: return
        setParameter(id, key, parameter.default)
        finishAdjustment()
    }
    fun upgradeEffect(id: String) {
        val effect = history.current.effects.firstOrNull { it.id == id } ?: return
        val definition = BuiltInEffects.registry.definition(effect.type) ?: return
        dispatch(EditorCommand.UpgradeEffect(id, definition.algorithmVersion))
    }
    fun moveEffect(id: String, delta: Int) {
        val index = history.current.effects.indexOfFirst { it.id == id }
        if (index >= 0) dispatch(EditorCommand.MoveEffect(id, index + delta))
    }
    fun remove(id: String) = dispatch(EditorCommand.RemoveEffect(id))
    fun select(id: String) = _uiState.update { it.copy(selectedEffectId = id.takeUnless { _ -> it.selectedEffectId == id }) }
    fun setShowSource(show: Boolean) = _uiState.update { it.copy(showSource = show) }
    fun setCompare(compare: Boolean) = _uiState.update { it.copy(compare = compare) }
    fun setExportMaximumDimension(value: Int?) = _uiState.update { it.copy(exportMaximumDimension = value) }
    fun clearMessage() = _uiState.update { it.copy(message = null, error = null) }
    fun clearDiagnostics() = DiagnosticsLog.clear()
    fun setFullDetail(full: Boolean) {
        if (full == _uiState.value.fullDetail || exporting) return
        _uiState.update { it.copy(fullDetail = full) }
        reloadSource()
    }
    fun undo() { cancelSourceLoad(); interactionEpoch++; history.undo(); afterHistoryChange() }
    fun redo() { cancelSourceLoad(); interactionEpoch++; history.redo(); afterHistoryChange() }
    private fun cancelSourceLoad() {
        if (sourceJob?.isActive == true) {
            sourceJob?.cancel(); sourceGeneration++
            _uiState.update { it.copy(operationInProgress = false) }
        }
    }
    private fun afterHistoryChange() {
        publishHistoryState()
        _uiState.update { it.copy(saved = false) }
        if (history.current.source != boundSource) reloadSource() else scheduleRender()
        scheduleAutosave()
    }

    fun saveProject(uri: Uri) {
        history.endTransaction()
        val snapshot = history.current
        launchOperation("SAVING PORTABLE PROJECT", "PROJECT SAVED") {
            projectStore.saveProject(uri, snapshot, ENGINE_VERSION)
            _uiState.update { it.copy(saved = history.current == snapshot) }
        }
    }
    fun openProject(uri: Uri) {
        interactionEpoch++
        launchSourceOperation("OPENING PROJECT") { ticket ->
            val document = projectStore.loadProject(uri)
            if (ticket != sourceGeneration) return@launchSourceOperation
            history.replace(document.project)
            clearBinding()
            publishHistoryState()
            _uiState.update { it.copy(fullDetail = false, saved = true, warnings = emptyList()) }
            scheduleAutosave()
            loadCurrentSource(ticket)
        }
    }
    fun saveRecipe(uri: Uri) {
        val snapshot = RecipeMapper.fromProject(history.current, ENGINE_VERSION)
        launchOperation("SAVING RECIPE", "RECIPE SAVED") { projectStore.saveRecipe(uri, snapshot) }
    }
    fun openRecipe(uri: Uri) {
        interactionEpoch++
        launchOperation("OPENING RECIPE", "RECIPE OPENED") {
            val imported = projectStore.loadRecipe(uri)
            history.replace(RecipeMapper.applyToProject(imported.recipe, history.current), clearHistory = false)
            val warnings = imported.warnings + history.current.effects.filter { !it.isResolved }
                .map { "${it.id}: ${it.type} is preserved but disabled; an explicit migration is required." }
            _uiState.update { it.copy(warnings = warnings.distinct(), saved = false) }
            publishHistoryState()
            scheduleRender()
            scheduleAutosave()
        }
    }

    fun exportPng(uri: Uri) {
        if (_uiState.value.operationInProgress) return
        history.endTransaction()
        val snapshot = history.current
        val asset = snapshot.source ?: return
        val maximum = _uiState.value.exportMaximumDimension
        exporting = true
        previewGeneration.incrementAndGet()
        val obsolete = renderJob
        renderJob?.cancel()
        _uiState.update { it.copy(rendering = false) }
        launchOperation("RENDERING PNG", "PNG EXPORTED") {
            val started = System.nanoTime()
            try {
                obsolete?.join()
                withContext(Dispatchers.Default) { renderer.clearCache() }
                val bitmap = if (maximum == null) sourceLoader.loadFull(asset) else sourceLoader.reopen(asset, maximum)
                val pixels = try { withContext(Dispatchers.Default) { bitmap.toPixelBuffer() } } finally { bitmap.recycle() }
                val context = currentCoroutineContext()
                val result = withContext(Dispatchers.Default) {
                    renderer.render(RenderRequest(0, snapshot.revision, pixels, snapshot.finalEffects(),
                        RenderQuality.FINAL, snapshot.globalSeed, CancellationProbe { !context.isActive },
                        asset.width, asset.height))
                }
                projectStore.exportPng(uri, result.output)
                DiagnosticsLog.info("export", "End-to-end PNG ${(System.nanoTime() - started) / 1_000_000} ms; revision=${snapshot.revision}")
            } finally {
                exporting = false
                scheduleRender()
            }
        }
    }

    fun exportDiagnostics(uri: Uri) = launchOperation("SAVING DIAGNOSTICS", "DIAGNOSTICS SAVED") {
        val report = DiagnosticsLog.report(getApplication(), history.current)
        withContext(Dispatchers.IO) {
            val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
            output.bufferedWriter().use { it.write(report) }
        }
    }
    fun runSelfCheck() = viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.Default) {
                KotlinPipelineRenderer().render(RenderRequest(0, 0,
                    PixelBuffer(1, 1, byteArrayOf(-1, 0, 0, -1)),
                    listOf(BuiltInEffects.Greyscale.instantiate("self-check")), RenderQuality.PREVIEW, 0))
            }
            check(result.output.rgba[0].toInt() == 54)
            history.current.source?.let { sourceLoader.probe(it) }
            DiagnosticsLog.info("self-check", "PASSED")
            _uiState.update { it.copy(message = "SELF-CHECK PASSED") }
        } catch (error: Exception) { failOperation("Self-check failed", error) }
    }

    private fun restoreAutosave() {
        val epoch = interactionEpoch
        launchSourceOperation("RESTORING PROJECT") { ticket ->
            val document = projectStore.loadAutosave()
            if (epoch != interactionEpoch || ticket != sourceGeneration) return@launchSourceOperation
            if (document != null) {
                history.replace(document.project)
                publishHistoryState()
                loadCurrentSource(ticket)
            }
        }
    }
    private fun launchSourceOperation(message: String, block: suspend (Long) -> Unit) {
        if (exporting) return
        sourceJob?.cancel()
        val ticket = ++sourceGeneration
        previewGeneration.incrementAndGet()
        renderJob?.cancel()
        _uiState.update { it.copy(operationInProgress = true, rendering = false, message = message, error = null) }
        sourceJob = viewModelScope.launch {
            try {
                block(ticket)
                ensureActive()
                if (ticket == sourceGeneration) {
                    _uiState.update { it.copy(operationInProgress = false, message = "READY") }
                    scheduleRender()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == sourceGeneration) failOperation(message, error)
            }
        }
    }
    private fun reloadSource() {
        clearBinding()
        launchSourceOperation("LOADING SOURCE") { loadCurrentSource(it) }
    }
    private suspend fun loadCurrentSource(ticket: Long) {
        val asset = history.current.source ?: return
        val full = _uiState.value.fullDetail
        val bitmap = try { if (full) sourceLoader.loadFull(asset) else sourceLoader.reopen(asset) }
        catch (error: Exception) {
            if (full) _uiState.update { it.copy(fullDetail = false) }
            throw error
        }
        val pixels = withContext(Dispatchers.Default) { bitmap.toPixelBuffer() }
        if (ticket == sourceGeneration && history.current.source == asset) bind(asset, bitmap, pixels)
    }
    private fun bind(asset: SourceAsset, bitmap: Bitmap, pixels: PixelBuffer) {
        boundSource = asset
        sourcePixels = pixels
        _uiState.update { it.copy(sourceBitmap = bitmap, renderedBitmap = bitmap, stalePreview = true, error = null) }
    }
    private fun clearBinding() {
        boundSource = null
        sourcePixels = null
        _uiState.update { it.copy(sourceBitmap = null, renderedBitmap = null, stalePreview = false, lastRenderDurationMillis = null) }
    }
    private fun dispatch(command: EditorCommand, gesture: Boolean = false) {
        interactionEpoch++
        if (gesture) history.beginTransaction() else history.endTransaction()
        val previous = history.current
        history.dispatch(command)
        if (previous == history.current) return
        publishHistoryState()
        _uiState.update { it.copy(saved = false) }
        scheduleRender(if (gesture) 40L else 0L)
        scheduleAutosave()
    }
    private fun publishHistoryState() {
        _uiState.update { state -> state.copy(project = history.current,
            selectedEffectId = state.selectedEffectId?.takeIf { id -> history.current.effects.any { it.id == id } },
            canUndo = history.canUndo, canRedo = history.canRedo) }
    }
    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        val snapshot = history.current
        autosaveJob = viewModelScope.launch {
            delay(350)
            try { projectStore.saveAutosave(snapshot, ENGINE_VERSION) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failOperation("Autosave failed", error) }
        }
    }
    fun checkpoint() {
        history.endTransaction()
        autosaveJob?.cancel()
        val snapshot = history.current
        autosaveJob = viewModelScope.launch {
            try { projectStore.saveAutosave(snapshot, ENGINE_VERSION) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failOperation("Autosave failed", error) }
        }
    }
    private fun scheduleRender(debounceMillis: Long = 0) {
        val project = history.current
        val asset = project.source
        val pixels = sourcePixels
        val ticket = previewGeneration.incrementAndGet()
        renderJob?.cancel()
        if (asset == null || boundSource != asset || pixels == null || exporting) {
            _uiState.update { it.copy(rendering = false, stalePreview = asset != null) }
            return
        }
        _uiState.update { it.copy(rendering = true, stalePreview = true, error = null) }
        renderJob = viewModelScope.launch {
            try {
                delay(debounceMillis)
                val started = System.nanoTime()
                val context = currentCoroutineContext()
                val bitmap = withContext(Dispatchers.Default) {
                    val result = renderer.render(RenderRequest(ticket, project.revision, pixels, project.activeEffects(),
                        RenderQuality.PREVIEW, project.globalSeed, CancellationProbe { !context.isActive }, asset.width, asset.height))
                    ensureActive()
                    result.output.toBitmap()
                }
                ensureActive()
                // Publication happens on Main, after conversion and after the last identity check.
                if (ticket == previewGeneration.get() && boundSource == asset && history.current == project) {
                    _uiState.update { it.copy(renderedBitmap = bitmap, rendering = false, stalePreview = false,
                        lastRenderDurationMillis = (System.nanoTime() - started) / 1_000_000.0) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cancelled: RenderCancelledException) { /* superseded request */ }
            catch (error: Exception) {
                if (ticket == previewGeneration.get()) {
                    _uiState.update { it.copy(rendering = false, stalePreview = true, error = error.message) }
                    DiagnosticsLog.error("render", "Preview failed", error)
                }
            } finally {
                if (ticket == previewGeneration.get()) _uiState.update { it.copy(rendering = false) }
            }
        }
    }
    private fun launchOperation(start: String, success: String, block: suspend () -> Unit) {
        if (_uiState.value.operationInProgress) return
        _uiState.update { it.copy(operationInProgress = true, message = start, error = null) }
        viewModelScope.launch {
            try { block(); _uiState.update { it.copy(message = success) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failOperation(start, error) }
            finally { _uiState.update { it.copy(operationInProgress = false) } }
        }
    }
    private fun failOperation(prefix: String, error: Throwable) {
        if (error is CancellationException) return
        DiagnosticsLog.error("operation", prefix, error)
        _uiState.update { it.copy(operationInProgress = false, error = "$prefix: ${error.message}") }
    }
}

private suspend fun Bitmap.toPixelBuffer(): PixelBuffer {
    val rgba = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), 4))
    val row = IntArray(width)
    for (y in 0 until height) {
        currentCoroutineContext().ensureActive()
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val i = (y * width + x) * 4
            val argb = row[x]
            rgba[i] = (argb shr 16).toByte(); rgba[i + 1] = (argb shr 8).toByte()
            rgba[i + 2] = argb.toByte(); rgba[i + 3] = (argb ushr 24).toByte()
        }
    }
    return PixelBuffer(width, height, rgba)
}
private suspend fun PixelBuffer.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val row = IntArray(width)
        for (y in 0 until height) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until width) {
                val i = (y * width + x) * 4
                row[x] = ((rgba[i + 3].toInt() and 255) shl 24) or ((rgba[i].toInt() and 255) shl 16) or
                    ((rgba[i + 1].toInt() and 255) shl 8) or (rgba[i + 2].toInt() and 255)
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    } catch (error: Throwable) { bitmap.recycle(); throw error }
}
