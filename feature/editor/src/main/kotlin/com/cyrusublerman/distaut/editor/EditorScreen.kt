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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.checkpoint() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var mode by rememberSaveable { mutableStateOf(SidebarMode.PIPELINE) }
    var debug by rememberSaveable { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) DiagnosticsLog.info("import", "Picker closed")
        else viewModel.importSource(uri)
    }
    val projectSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
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
        saveProject = { projectSaver.launch("distaut-project.distaut") },
        openProject = {
            projectOpener.launch(arrayOf("application/zip", "application/octet-stream", "application/json", "text/plain"))
        },
        saveRecipe = { recipeSaver.launch("distaut-recipe-v3.json") },
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
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    val idle = !state.operationInProgress
    Row(Modifier.fillMaxWidth().height(ToolbarHeight).horizontalScroll(rememberScrollState())) {
        ToolCell(state.project.source?.displayName ?: "OPEN IMAGE", 12 * F, enabled = idle, alignStart = true, onClick = pickImage)
        ToolCell("OPEN", 5 * F, enabled = idle, onClick = openProject)
        ToolCell("SAVE", 5 * F, enabled = idle, onClick = saveProject)
        ToolCell("UNDO", 5 * F, state.canUndo && idle, onClick = viewModel::undo)
        ToolCell("REDO", 5 * F, state.canRedo && idle, onClick = viewModel::redo)
        ToolCell(if (state.showSource) "SOURCE" else "OUTPUT", 6 * F,
            enabled = state.sourceBitmap != null, active = state.showSource) { viewModel.setShowSource(!state.showSource) }
        ToolCell("EXPORT", 6 * F, enabled = state.project.source != null && idle, onClick = exportPng)
        Box {
            ToolCell("TOOLS", 6 * F, onClick = { toolsOpen = true })
            DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }) {
                DropdownMenuItem(text = { Text("Load recipe") }, enabled = idle, onClick = { toolsOpen = false; openRecipe() })
                DropdownMenuItem(text = { Text("Save recipe") }, enabled = idle, onClick = { toolsOpen = false; saveRecipe() })
                DropdownMenuItem(text = { Text("Relink / replace source") }, enabled = idle, onClick = { toolsOpen = false; pickImage() })
                DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { toolsOpen = false; setDebug(!debug) })
            }
        }
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
        val workspaceWidth = maxWidth
        val workspaceHeight = maxHeight
        if (workspaceWidth >= 800.dp) {
            val sidebar = SidebarWidth.coerceAtMost(workspaceWidth * 0.45f)
            val viewportWidth = (
                workspaceWidth - sidebar - BorderWidth
                ).coerceAtLeast(20 * F)
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
                    Modifier.width(viewportWidth).fillMaxHeight(),
                    viewModel,
                )
            }
        } else {
            val canvas = workspaceHeight * 0.52f
            val sidebarHeight = (
                workspaceHeight - canvas - BorderWidth
                ).coerceAtLeast(0.dp)
            Column(Modifier.fillMaxSize()) {
                Viewport(state, Modifier.fillMaxWidth().height(canvas), viewModel)
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
                    Modifier.fillMaxWidth().height(sidebarHeight),
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
