package com.cyrusublerman.distaut.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.cyrusublerman.distaut.model.ParameterValue
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class EditorRegressionTest {
    private lateinit var app: Application
    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir,"projects").deleteRecursively()
        File(app.filesDir,"sources").deleteRecursively()
    }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun source(name: String, colour: Int): Uri {
        val file=File(app.cacheDir,name)
        val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(colour)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }
    private suspend fun EditorViewModel.settled(): EditorUiState = withTimeout(10000) {
        uiState.first { !it.operationInProgress && !it.rendering }
    }
    @Test fun undoRestoresSourcePixelsAlongWithMetadata() = runBlocking {
        val vm=EditorViewModel(app)
        vm.settled()
        vm.importSource(source("a.png",Color.RED))
        val a=vm.settled()
        assertEquals(null,a.error)
        vm.importSource(source("b.png",Color.BLUE))
        assertEquals(Color.BLUE,vm.settled().sourceBitmap!!.getPixel(0,0))
        vm.undo()
        val restored=vm.settled()
        assertEquals(a.project.source,restored.project.source)
        assertEquals(Color.RED,restored.sourceBitmap!!.getPixel(0,0))
        assertEquals(Color.RED,restored.renderedBitmap!!.getPixel(0,0))
        vm.redo()
        assertEquals(Color.BLUE,vm.settled().renderedBitmap!!.getPixel(0,0))
        vm.checkpoint()
    }
    @Test fun viewThroughDoesNotTruncateExportAndExportUsesCapturedSettings() = runBlocking {
        val vm=EditorViewModel(app)
        vm.settled()
        vm.importSource(source("export.png",Color.RED)); vm.settled()
        vm.addEffect("invert"); vm.settled()
        vm.addEffect("invert"); vm.settled()
        val ids=vm.uiState.value.project.effects.map { it.id }
        vm.setSolo(ids.first()); vm.settled()
        val destination=File(app.cacheDir,"result.png")
        vm.exportPng(Uri.fromFile(destination))
        // Editing remains available while the export uses its immutable snapshot.
        vm.setEnabled(ids.last(),false)
        val finished=vm.settled()
        assertEquals(null,finished.error)
        val image=android.graphics.BitmapFactory.decodeFile(destination.path)
        assertEquals(Color.RED,image.getPixel(0,0))
        vm.runSelfCheck()
        assertTrue(!vm.settled().rendering)
        vm.checkpoint()
    }
    @Test fun sliderGestureHasOneUndoEntry() = runBlocking {
        val vm=EditorViewModel(app); vm.settled()
        vm.addEffect("posterise")
        val id=vm.uiState.value.project.effects.single().id
        for (value in 7..30) vm.setParameter(id,"levels",ParameterValue.Integer(value))
        vm.finishAdjustment(); vm.undo()
        assertEquals(ParameterValue.Integer(6),vm.uiState.value.project.effects.single().parameters["levels"])
        vm.checkpoint()
    }
}
