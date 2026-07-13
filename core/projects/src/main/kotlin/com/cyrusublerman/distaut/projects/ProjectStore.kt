package com.cyrusublerman.distaut.projects

import android.content.Context
import android.graphics.Bitmap
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

class ProjectStore(private val context: Context) {
    private val autosaveDirectory = File(context.filesDir, "projects")
    private val autosaveFile = File(autosaveDirectory, "autosave.distaut.json")
    private val supportedTypes get() = BuiltInEffects.registry.supportedTypes()

    suspend fun saveAutosave(project: ProjectState, engineVersion: String) = withContext(Dispatchers.IO) {
        autosaveDirectory.mkdirs()
        val document = ProjectDocument(
            engineVersion = engineVersion,
            savedAtEpochMillis = System.currentTimeMillis(),
            project = project,
        )
        writeAtomic(autosaveFile, ProjectCodec.encode(document).toByteArray(Charsets.UTF_8))
    }

    suspend fun loadAutosave(): ProjectDocument? = withContext(Dispatchers.IO) {
        if (!autosaveFile.isFile) return@withContext null
        ProjectCodec.decode(autosaveFile.readText(), supportedTypes)
    }

    suspend fun saveProject(uri: Uri, project: ProjectState, engineVersion: String) =
        withContext(Dispatchers.IO) {
            val document = ProjectDocument(
                engineVersion = engineVersion,
                savedAtEpochMillis = System.currentTimeMillis(),
                project = project,
            )
            writeText(uri, ProjectCodec.encode(document))
        }

    suspend fun loadProject(uri: Uri): ProjectDocument = withContext(Dispatchers.IO) {
        ProjectCodec.decode(readText(uri), supportedTypes)
    }

    suspend fun saveRecipe(uri: Uri, recipe: RecipeV2) = withContext(Dispatchers.IO) {
        writeText(uri, RecipeCodec.encode(recipe))
    }

    suspend fun loadRecipe(uri: Uri): RecipeImportResult = withContext(Dispatchers.IO) {
        RecipeCodec.decodeAny(readText(uri), supportedTypes)
    }

    suspend fun exportPng(uri: Uri, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Unable to open PNG destination")
        output.use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                "Android bitmap encoder failed"
            }
        }
    }

    private fun writeText(uri: Uri, text: String) {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Unable to open destination")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    private fun readText(uri: Uri, maximumBytes: Int = 16 * 1024 * 1024): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open document")
        return input.use {
            val bytes = it.readBytesLimited(maximumBytes)
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun writeAtomic(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.outputStream().use { output ->
            output.write(bytes)
            output.flush()
            if (output is java.io.FileOutputStream) output.fd.sync()
        }
        if (!temporary.renameTo(destination)) {
            destination.delete()
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                throw IOException("Unable to replace autosave")
            }
        }
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
