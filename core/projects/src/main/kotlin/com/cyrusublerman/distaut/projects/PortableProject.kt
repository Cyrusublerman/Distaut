package com.cyrusublerman.distaut.projects

import com.cyrusublerman.distaut.model.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Streams a bounded archive, verifies every asset before publishing any of them. */
object PortableProject {
    private const val MANIFEST_LIMIT = 16L * 1024 * 1024
    private const val SOURCE_LIMIT = 512L * 1024 * 1024
    private const val PACKAGE_LIMIT = 1024L * 1024 * 1024

    fun write(document: ProjectDocument, source: InputStream?, output: OutputStream, checkCancelled: () -> Unit = {}) {
        require(document.project.assets.isEmpty()) { "Use the multi-asset writer for this project" }
        writeAssets(document, { requireNotNull(source) { "Project source is missing" } }, output, checkCancelled)
    }
    fun writeAssets(document: ProjectDocument, openAsset: (SourceAsset) -> InputStream, output: OutputStream,
        checkCancelled: () -> Unit = {}) {
        val assets = document.project.allAssets()
        require(assets.size <= GraphRules.MAX_SOURCES)
        val portableAssets = assets.mapValues { (id, asset) -> asset.copy(uri = "asset:$id", persistedPermission = false, managedCopy = true) }
        val portable = document.copy(schemaVersion = 4, project = document.project.copy(source = portableAssets[PRIMARY_ASSET_ID], assets = portableAssets - PRIMARY_ASSET_ID))
        var total = 0L
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            val manifest = ProjectCodec.encode(portable).toByteArray(Charsets.UTF_8)
            require(manifest.size <= MANIFEST_LIMIT)
            zip.write(manifest); zip.closeEntry()
            assets.forEach { (id, asset) ->
                checkCancelled()
                require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
                zip.putNextEntry(ZipEntry("assets/$id"))
                openAsset(asset).use { input -> total += copyLimited(input, zip, minOf(SOURCE_LIMIT, PACKAGE_LIMIT - total), checkCancelled) }
                zip.closeEntry()
            }
        }
    }
    fun read(input: InputStream, directory: File, supported: Set<String>, checkCancelled: () -> Unit = {}): ProjectDocument {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create source directory" }
        val staging = mutableMapOf<String, File>()
        try {
            var manifest: String? = null
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    checkCancelled()
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "Unexpected directory in project" }
                    if (entry.name == "manifest.json") {
                        require(manifest == null) { "Duplicate project manifest" }
                        val bytes = java.io.ByteArrayOutputStream()
                        copyLimited(zip, bytes, MANIFEST_LIMIT, checkCancelled)
                        manifest = bytes.toString(Charsets.UTF_8.name())
                    } else {
                        val id = when {
                            entry.name == "source" -> PRIMARY_ASSET_ID // 0.4/0.5 archives
                            entry.name.matches(Regex("assets/[A-Za-z0-9_-]{1,100}")) -> entry.name.removePrefix("assets/")
                            else -> error("Unexpected project entry: ${entry.name}")
                        }
                        require(id !in staging && staging.size < GraphRules.MAX_SOURCES) { "Duplicate or excessive assets" }
                        val temporary = File.createTempFile("package-", ".tmp", directory)
                        staging[id] = temporary
                        temporary.outputStream().use { total += copyLimited(zip, it, minOf(SOURCE_LIMIT, PACKAGE_LIMIT - total), checkCancelled) }
                    }
                    zip.closeEntry()
                }
            }
            val document = ProjectCodec.decode(requireNotNull(manifest) { "Project manifest missing" }, supported)
            val assets = document.project.allAssets()
            require(assets.keys == staging.keys) { "Packaged assets do not match manifest" }
            val checksums = assets.mapValues { (id, asset) ->
                require(asset.uri == "asset:$id" || (id == PRIMARY_ASSET_ID && asset.uri == "asset:source")) { "Invalid packaged asset locator" }
                val digest = MessageDigest.getInstance("SHA-256")
                staging.getValue(id).inputStream().use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancelled(); val count = stream.read(buffer); if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                require(asset.checksum == null || asset.checksum == checksum) { "Project asset checksum mismatch: $id" }
                checksum
            }
            val restored = assets.mapValues { (id, asset) ->
                val checksum = checksums.getValue(id)
                val destination = File(directory, "$checksum.asset")
                checkCancelled()
                Files.move(staging.getValue(id).toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                asset.copy(uri = destination.toURI().toString(), checksum = checksum, managedCopy = true, persistedPermission = false)
            }
            return document.copy(project = document.project.copy(source = restored[PRIMARY_ASSET_ID], assets = restored - PRIMARY_ASSET_ID))
        } finally { staging.values.forEach { it.delete() } }
    }
}

internal fun copyLimited(input: InputStream, output: OutputStream, limit: Long, checkCancelled: () -> Unit = {}): Long {
    val buffer = ByteArray(65536); var total = 0L
    while (true) {
        checkCancelled(); val count = input.read(buffer); if (count < 0) break
        total += count; require(total <= limit) { "Project entry exceeds $limit bytes" }
        output.write(buffer, 0, count)
    }
    return total
}
