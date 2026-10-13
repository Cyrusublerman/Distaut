package com.cyrusublerman.distaut.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.cyrusublerman.distaut.model.*
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NodeCanvasTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: EditorViewModel
    private val store = ViewModelStore()

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "projects").deleteRecursively()
        vm = EditorViewModel(app)
        store.put("editor", vm)
        compose.setContent { EditorRoute(vm) }
        settle()
        val file = File(app.cacheDir, "node-ui-source.png")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        for (y in 0 until 80) for (x in 0 until 120) {
            bitmap.setPixel(x, y, Color.rgb(x * 255 / 119, y * 255 / 79, if (x % 20 < 10) 210 else 40))
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.runOnIdle { vm.importSource(Uri.fromFile(file)) }
        settle()
        compose.runOnIdle { vm.createBranchExample() }
        settle()
        capture("initial-${app.resources.configuration.screenWidthDp}")
    }
    @After fun tearDown() { compose.runOnIdle { store.clear() } }
    private fun settle() {
        // IO/render work resumes through Android's paused main Looper, outside the Compose frame clock.
        compose.waitUntil(15000) {
            shadowOf(Looper.getMainLooper()).idle()
            !vm.uiState.value.operationInProgress && !vm.uiState.value.rendering
        }
        compose.waitForIdle()
        assertNull(vm.uiState.value.error)
    }
    private fun capture(name: String) {
        val file = File("build/reports/node-ui/$name.png")
        file.parentFile!!.mkdirs()
        // Capture the native Robolectric view directly; PixelCopy waits for a real window redraw.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(file.parentFile, "$name.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
    }
    @Test fun tabletShowsBranchesAndSupportsTouchWiringDraggingAndUndo() {
        compose.onNodeWithContentDescription("Move or select SOURCE").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connect input mask of MIX IMAGES").assertIsDisplayed()
        capture("tablet-split")
        compose.onNodeWithText("EXPAND").performClick()
        compose.waitForIdle()
        capture("tablet-expanded")
        val original = vm.uiState.value.project
        val mix = original.effects.first { it.type == "mix" }
        compose.onNodeWithContentDescription("Connect output of SOURCE").performTouchInput { click() }
        compose.onNodeWithContentDescription("Connect input b of MIX IMAGES").performTouchInput { click() }
        settle()
        assertEquals(SOURCE_NODE_ID, vm.uiState.value.project.graph!!.upstream(mix.id, "b"))
        compose.runOnIdle { vm.undo() }; settle()
        assertEquals(original.graph, vm.uiState.value.project.graph)
        val before = vm.uiState.value.project.graph!!.positions.getValue(SOURCE_NODE_ID)
        val header = compose.onNodeWithContentDescription("Move or select SOURCE")
        val beforeBounds = header.fetchSemanticsNode().boundsInRoot
        header.performTouchInput { down(center); moveBy(Offset(30f, 0f)); moveBy(Offset(40f, 0f)); up() }
        compose.waitForIdle()
        val after = vm.uiState.value.project.graph!!.positions.getValue(SOURCE_NODE_ID)
        assertTrue(after.x > before.x)
        val movement = header.fetchSemanticsNode().boundsInRoot.left - beforeBounds.left
        assertTrue(movement in 30f..90f, "Node should track finger at fitted zoom; moved $movement px")
        compose.runOnIdle { vm.undo() }; settle()
        assertEquals(before, vm.uiState.value.project.graph!!.positions[SOURCE_NODE_ID])
    }
    @Test fun secondImageCardAndNamedOutputControlsAreUsable() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "second-ui-source.png")
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.runOnIdle { vm.importSource(Uri.fromFile(file), additional = true) }; settle()
        compose.onNodeWithContentDescription("Move or select IMAGE 2").assertIsDisplayed()
        compose.onNodeWithText("SAVE NAMED OUTPUT").performScrollTo().performClick()
        compose.onNodeWithText("Output name").performTextInput("Second image")
        compose.onAllNodesWithText("SAVE").onLast().performClick()
        settle()
        assertEquals("Second image", vm.uiState.value.project.processingGraph().exportTargets()
            .getValue(vm.uiState.value.project.processingGraph().selectedOutputId).name)
        capture("multi-source-named-output")
    }
    @Test @Config(qualifiers = "w600dp-h960dp-mdpi")
    fun narrowLayoutCanInspectMasksAndReturnToCanvas() {
        val mask = vm.uiState.value.project.effects.first { it.type == "edge_mask" }
        compose.onNodeWithContentDescription("Move or select EDGE MASK").performClick()
        compose.onNodeWithText("INSPECT").performClick()
        compose.onNodeWithText("VIEW THIS OUTPUT").performClick()
        settle()
        assertEquals(mask.id, vm.uiState.value.viewerNodeId)
        assertEquals("mix", vm.uiState.value.project.effects.first { it.id == vm.uiState.value.project.graph!!.outputNodeId }.type)
        capture("portrait-inspector")
        compose.onNodeWithText("GRAPH").performClick()
        compose.onNodeWithContentDescription("Move or select SOURCE").assertIsDisplayed()
        capture("portrait-graph")
    }
}
