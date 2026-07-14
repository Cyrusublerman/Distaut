package com.cyrusublerman.distaut.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

internal enum class SidebarMode {
    PIPELINE,
    CANVAS,
}

@Composable
fun EditorRoute(viewModel: EditorViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val diagnostics by DiagnosticsLog.entries.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(SidebarMode.PIPELINE) }
    var debug by rememberSaveable { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) DiagnosticsLog.info("import", "Picker closed")
        else viewModel.importSource(uri)
    }
    val projectSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { it?.let(viewModel::saveProject) }
    val projectOpener = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { it?.let(viewModel::openProject) }
    val recipeSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { it?.let(viewModel::saveRecipe) }
    val recipeOpener = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { it?.let(viewModel::openRecipe) }
    val pngExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { it?.let(viewModel::exportPng) }
    val diagnosticExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { it?.let(viewModel::exportDiagnostics) }

    DistautScreen(
        state,
        diagnostics,
        mode,
        debug,
        setMode = {
            mode = it
            debug = false
        },
        setDebug = { debug = it },
        pickImage = {
            imagePicker.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                )
            )
        },
        saveProject = { projectSaver.launch("distaut-project.json") },
        openProject = {
            projectOpener.launch(arrayOf("application/json", "text/plain"))
        },
        saveRecipe = { recipeSaver.launch("distaut-recipe-v2.json") },
        openRecipe = {
            recipeOpener.launch(arrayOf("application/json", "text/plain"))
        },
        exportPng = { pngExporter.launch("distaut-export.png") },
        exportDiagnostics = {
            diagnosticExporter.launch("distaut-diagnostics.txt")
        },
        viewModel = viewModel,
    )
}

@Composable
private fun DistautScreen(
    state: EditorUiState,
    diagnostics: List<DiagnosticEntry>,
    mode: SidebarMode,
    debug: Boolean,
    setMode: (SidebarMode) -> Unit,
    setDebug: (Boolean) -> Unit,
    pickImage: () -> Unit,
    saveProject: () -> Unit,
    openProject: () -> Unit,
    saveRecipe: () -> Unit,
    openRecipe: () -> Unit,
    exportPng: () -> Unit,
    exportDiagnostics: () -> Unit,
    viewModel: EditorViewModel,
) {
    Surface(
        color = UiBackground,
        contentColor = UiText,
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(
                state,
                debug,
                pickImage,
                saveProject,
                openProject,
                saveRecipe,
                openRecipe,
                exportPng,
                setDebug,
                viewModel,
            )
            StatusStrip(state, diagnostics.size, viewModel::clearMessage)
            Workspace(
                state,
                diagnostics,
                mode,
                debug,
                setMode,
                exportDiagnostics,
                viewModel,
            )
        }
    }
}

@Composable
private fun Toolbar(
    state: EditorUiState,
    debug: Boolean,
    pickImage: () -> Unit,
    saveProject: () -> Unit,
    openProject: () -> Unit,
    saveRecipe: () -> Unit,
    openRecipe: () -> Unit,
    exportPng: () -> Unit,
    setDebug: (Boolean) -> Unit,
    viewModel: EditorViewModel,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ToolbarHeight)
            .horizontalScroll(rememberScrollState())
            .border(BorderWidth, UiBorder, RectangleShape),
    ) {
        ToolCell(
            state.project.source?.displayName ?: "NO SOURCE ▾",
            14 * F,
            alignStart = true,
            onClick = pickImage,
        )
        ToolCell("OPEN", 5 * F, onClick = openProject)
        ToolCell("SAVE", 5 * F, onClick = saveProject)
        ToolCell("UNDO", 5 * F, state.canUndo, onClick = viewModel::undo)
        ToolCell("REDO", 5 * F, state.canRedo, onClick = viewModel::redo)
        ToolCell(
            if (state.showSource) "SOURCE" else "OUTPUT",
            6 * F,
            enabled = state.sourceBitmap != null,
            active = state.showSource,
        ) { viewModel.setShowSource(!state.showSource) }
        ToolCell("LOAD RCP", 7 * F, onClick = openRecipe)
        ToolCell("SAVE RCP", 7 * F, onClick = saveRecipe)
        ToolCell(
            "EXPORT PNG",
            8 * F,
            enabled = state.project.source != null && !state.operationInProgress,
            onClick = exportPng,
        )
        ToolCell(
            "DEBUG ${DiagnosticsLog.entries.value.count {
                it.level == DiagnosticLevel.ERROR
            }}",
            7 * F,
            active = debug,
        ) { setDebug(!debug) }
    }
}

@Composable
private fun Workspace(
    state: EditorUiState,
    diagnostics: List<DiagnosticEntry>,
    mode: SidebarMode,
    debug: Boolean,
    setMode: (SidebarMode) -> Unit,
    exportDiagnostics: () -> Unit,
    viewModel: EditorViewModel,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 800.dp) {
            val sidebar = SidebarWidth.coerceAtMost(maxWidth * 0.45f)
            Row(Modifier.fillMaxSize()) {
                Sidebar(
                    state,
                    diagnostics,
                    mode,
                    debug,
                    setMode,
                    exportDiagnostics,
                    viewModel,
                    Modifier.width(sidebar).fillMaxHeight(),
                )
                Box(
                    Modifier
                        .width(BorderWidth)
                        .fillMaxHeight()
                        .background(UiBorder)
                )
                Viewport(
                    state,
                    Modifier
                        .width((maxWidth - sidebar - BorderWidth).coerceAtLeast(20 * F))
                        .fillMaxHeight(),
                )
            }
        } else {
            val canvas = (maxHeight * 0.52f).coerceAtLeast(18 * F)
            Column(Modifier.fillMaxSize()) {
                Viewport(state, Modifier.fillMaxWidth().height(canvas))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(BorderWidth)
                        .background(UiBorder)
                )
                Sidebar(
                    state,
                    diagnostics,
                    mode,
                    debug,
                    setMode,
                    exportDiagnostics,
                    viewModel,
                    Modifier
                        .fillMaxWidth()
                        .height((maxHeight - canvas - BorderWidth).coerceAtLeast(18 * F)),
                )
            }
        }
    }
}

@Composable
private fun Sidebar(
    state: EditorUiState,
    diagnostics: List<DiagnosticEntry>,
    mode: SidebarMode,
    debug: Boolean,
    setMode: (SidebarMode) -> Unit,
    exportDiagnostics: () -> Unit,
    viewModel: EditorViewModel,
    modifier: Modifier,
) {
    Column(
        modifier
            .background(UiBackground)
            .border(BorderWidth, UiBorder, RectangleShape)
    ) {
        if (debug) {
            DebugPanel(state, diagnostics, exportDiagnostics, viewModel)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth().height(ToolbarHeight)) {
                val tabWidth = maxWidth / 2
                Row(Modifier.fillMaxSize()) {
                    ToolCell(
                        "PIPELINE",
                        tabWidth,
                        active = mode == SidebarMode.PIPELINE,
                    ) { setMode(SidebarMode.PIPELINE) }
                    ToolCell(
                        "CANVAS",
                        tabWidth,
                        active = mode == SidebarMode.CANVAS,
                    ) { setMode(SidebarMode.CANVAS) }
                }
            }
            when (mode) {
                SidebarMode.PIPELINE -> PipelinePanel(state, viewModel)
                SidebarMode.CANVAS -> CanvasPanel(state, viewModel)
            }
        }
    }
}
