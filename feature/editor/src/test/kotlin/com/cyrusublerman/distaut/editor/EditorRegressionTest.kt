package com.cyrusublerman.distaut.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.cyrusublerman.distaut.model.ParameterValue
import com.cyrusublerman.distaut.projects.SourceAssetLoader
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
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorRegressionTest {
    private lateinit var app: Application
    private val stores = mutableListOf<ViewModelStore>()
    private fun editor(): EditorViewModel = EditorViewModel(app).also { vm ->
        stores += ViewModelStore().apply { put("editor", vm) }
    }
    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir,"projects").deleteRecursively()
        File(app.filesDir,"sources").deleteRecursively()
    }
    @After fun tearDown() {
        stores.forEach { it.clear() }
        stores.clear()
        Dispatchers.resetMain()
    }
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
        val vm=editor()
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
    }
    @Test fun viewThroughDoesNotTruncateExportAndExportUsesCapturedSettings() = runBlocking {
        val vm=editor()
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
    }
    @Test fun sliderGestureHasOneUndoEntry() = runBlocking {
        val vm=editor(); vm.settled()
        vm.addEffect("posterise")
        val id=vm.uiState.value.project.effects.single().id
        for (value in 7..30) vm.setParameter(id,"levels",ParameterValue.Integer(value))
        vm.finishAdjustment(); vm.undo()
        assertEquals(ParameterValue.Integer(6),vm.uiState.value.project.effects.single().parameters["levels"])
    }
    @Test fun fallbackDecoderRotatesPixelsAndDocumentDimensions() = runBlocking {
        val file = File(app.cacheDir, "oriented.jpg")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        for (y in 0 until 20) for (x in 0 until 40) bitmap.setPixel(x, y, if (x < 20) Color.RED else Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val loaded = SourceAssetLoader(app).importSource(Uri.fromFile(file))
        assertEquals(20, loaded.asset.width)
        assertEquals(40, loaded.asset.height)
        assertEquals(20, loaded.bitmap.width)
        assertEquals(40, loaded.bitmap.height)
        val top = loaded.bitmap.getPixel(10, 5)
        val bottom = loaded.bitmap.getPixel(10, 35)
        assertTrue(Color.red(top) > 200 && Color.blue(top) < 50, "Expected red at top, got ${Integer.toHexString(top)}")
        assertTrue(Color.blue(bottom) > 200 && Color.red(bottom) < 50, "Expected blue at bottom, got ${Integer.toHexString(bottom)}")
        loaded.bitmap.recycle()
    }
}
