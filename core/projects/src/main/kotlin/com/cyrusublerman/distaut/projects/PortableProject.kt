package com.cyrusublerman.distaut.projects

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Streaming package: never expands arbitrary archive paths or retains an image in heap. */
object PortableProject {
    private const val MANIFEST_LIMIT = 16L * 1024 * 1024
    private const val SOURCE_LIMIT = 512L * 1024 * 1024

    fun write(document: ProjectDocument, source: InputStream?, output: OutputStream,
        checkCancelled: () -> Unit = {}) {
        require(document.project.source == null || source != null) { "Project source is missing" }
        val portable = document.copy(project = document.project.copy(source = document.project.source?.copy(
            uri = "asset:source", persistedPermission = false, managedCopy = true,
        )))
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(ProjectCodec.encode(portable).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            source?.use {
                zip.putNextEntry(ZipEntry("source"))
                copyLimited(it, zip, SOURCE_LIMIT, checkCancelled)
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, directory: File, supported: Set<String>,
        checkCancelled: () -> Unit = {}): ProjectDocument {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create source directory" }
        val temporary = File.createTempFile("package-", ".tmp", directory)
        try {
            var manifest: String? = null
            var hasSource = false
            ZipInputStream(input).use { zip ->
                while (true) {
                    checkCancelled()
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "Unexpected directory in project" }
                    when (entry.name) {
                        "manifest.json" -> {
                            require(manifest == null) { "Duplicate project manifest" }
                            val bytes = java.io.ByteArrayOutputStream()
                            copyLimited(zip, bytes, MANIFEST_LIMIT, checkCancelled)
                            manifest = bytes.toString(Charsets.UTF_8.name())
                        }
                        "source" -> {
                            require(!hasSource) { "Duplicate source" }
                            temporary.outputStream().use { copyLimited(zip, it, SOURCE_LIMIT, checkCancelled) }
                            hasSource = true
                        }
                        else -> error("Unexpected project entry: ${entry.name}")
                    }
                    zip.closeEntry()
                }
            }
            val document = ProjectCodec.decode(requireNotNull(manifest) { "Project manifest missing" }, supported)
            val asset = document.project.source ?: return document.also { require(!hasSource) }
            require(asset.uri == "asset:source" && hasSource) { "Packaged source is missing" }
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    checkCancelled()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(asset.checksum == null || asset.checksum == checksum) { "Project source checksum mismatch" }
            val destination = File(directory, "$checksum.asset")
            if (!destination.exists()) Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return document.copy(project = document.project.copy(source = asset.copy(
                uri = destination.toURI().toString(), checksum = checksum, managedCopy = true, persistedPermission = false,
            )))
        } finally { temporary.delete() }
    }
}

internal fun copyLimited(input: InputStream, output: OutputStream, limit: Long,
    checkCancelled: () -> Unit = {}) {
    val buffer = ByteArray(65536)
    var total = 0L
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "Project entry exceeds $limit bytes" }
        output.write(buffer, 0, count)
    }
}
