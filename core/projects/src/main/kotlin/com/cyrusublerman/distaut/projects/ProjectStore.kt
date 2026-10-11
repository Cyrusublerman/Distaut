package com.cyrusublerman.distaut.projects

import android.content.Context
import com.cyrusublerman.distaut.render.PixelBuffer
import android.net.Uri
import com.cyrusublerman.distaut.effects.BuiltInEffects
import com.cyrusublerman.distaut.model.ProjectState
import com.cyrusublerman.distaut.recipes.RecipeCodec
import com.cyrusublerman.distaut.recipes.RecipeImportResult
import com.cyrusublerman.distaut.recipes.RecipeV2
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.FileInputStream

class ProjectStore(private val context: Context) {
    private val autosaveDirectory = File(context.filesDir, "projects")
    private val autosaveFile = File(autosaveDirectory, "autosave.distaut.json")
    private val autosaveMutex = Mutex()
    private val supportedTypes get() = BuiltInEffects.registry.supportedTypes()

    suspend fun saveAutosave(project: ProjectState, engineVersion: String) = withContext(Dispatchers.IO) {
        autosaveDirectory.mkdirs()
        val document = ProjectDocument(engineVersion = engineVersion, savedAtEpochMillis = System.currentTimeMillis(), project = project)
        autosaveMutex.withLock {
            currentCoroutineContext().ensureActive()
            AtomicDocument.write(autosaveFile, ProjectCodec.encode(document).toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun loadAutosave(): ProjectDocument? = withContext(Dispatchers.IO) {
        autosaveMutex.withLock {
            if (!autosaveFile.isFile) null else ProjectCodec.decode(autosaveFile.readText(), supportedTypes)
        }
    }

    suspend fun saveProject(uri: Uri, project: ProjectState, engineVersion: String) = withContext(Dispatchers.IO) {
        val snapshot = ProjectDocument(engineVersion = engineVersion, savedAtEpochMillis = System.currentTimeMillis(), project = project)
        val coroutine = currentCoroutineContext()
        val source = project.source?.let { openSource(Uri.parse(it.uri)) }
        try {
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Unable to open destination")
            output.use { PortableProject.write(snapshot, source, it) { coroutine.ensureActive() } }
        } finally { source?.close() }
    }

    suspend fun loadProject(uri: Uri): ProjectDocument = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        BufferedInputStream(openSource(uri)).use { stream ->
            stream.mark(4)
            val zip = stream.read() == 0x50 && stream.read() == 0x4b
            stream.reset()
            if (zip) PortableProject.read(stream, File(context.filesDir, "sources"), supportedTypes) { coroutine.ensureActive() }
            else ProjectCodec.decode(stream.readBytesLimited(16 * 1024 * 1024).toString(Charsets.UTF_8), supportedTypes)
        }
    }

    private fun openSource(uri: Uri): java.io.InputStream = if (uri.scheme == "file") {
        FileInputStream(File(requireNotNull(uri.path)))
    } else context.contentResolver.openInputStream(uri) ?: error("Source is missing; relink it before saving")

    suspend fun saveRecipe(uri: Uri, recipe: RecipeV2) = withContext(Dispatchers.IO) {
        writeText(uri, RecipeCodec.encode(recipe))
    }

    suspend fun loadRecipe(uri: Uri): RecipeImportResult = withContext(Dispatchers.IO) {
        RecipeCodec.decodeAny(readText(uri), supportedTypes)
    }

    suspend fun exportPng(uri: Uri, pixels: PixelBuffer) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Unable to open PNG destination")
        val coroutine = currentCoroutineContext()
        output.use { PngWriter.write(pixels, it) { coroutine.ensureActive() } }
    }

    private fun writeText(uri: Uri, text: String) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Unable to open destination")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    private fun readText(uri: Uri, maximumBytes: Int = 16 * 1024 * 1024): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Unable to open document")
        return input.use { it.readBytesLimited(maximumBytes).toString(Charsets.UTF_8) }
    }


}

private fun java.io.InputStream.readBytesLimited(maximumBytes: Int): ByteArray {
    require(maximumBytes > 0)
    val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= maximumBytes) { "Document exceeds $maximumBytes bytes" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
