package com.cyrusublerman.distaut.projects

import com.cyrusublerman.distaut.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class MultiAssetPersistenceTest {
    private val first = SourceAsset("file:/a", width = 4, height = 3)
    private val second = first.copy(uri = "file:/b")
    private val graph = ProcessingGraph(sources = ProcessingGraph().sources + ("second" to GraphSource("b", "Second")),
        outputs = mapOf("alternate" to GraphOutput("Alternate", PortRef("second"))), selectedOutputId = "alternate")
    private fun document() = ProjectDocument(engineVersion = "test", savedAtEpochMillis = 1,
        project = ProjectState(source = first, assets = mapOf("b" to second), graph = graph))
    @Test fun allAssetsAndSelectedOutputSurvivePortableRoundTrip() {
        val directory = Files.createTempDirectory("multi-asset").toFile()
        try {
            val zip = ByteArrayOutputStream()
            PortableProject.writeAssets(document(), { ByteArrayInputStream(it.uri.toByteArray()) }, zip)
            val loaded = PortableProject.read(ByteArrayInputStream(zip.toByteArray()), directory, emptySet())
            assertEquals(graph, loaded.project.graph)
            assertEquals(setOf(PRIMARY_ASSET_ID, "b"), loaded.project.allAssets().keys)
            loaded.project.allAssets().forEach { (id, asset) ->
                assertContentEquals(document().project.allAssets().getValue(id).uri.toByteArray(), File(URI(asset.uri)).readBytes())
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun badSecondAssetCannotPublishFirstAsset() {
        val directory = Files.createTempDirectory("bad-second").toFile()
        try {
            val doc = document().copy(project = document().project.copy(assets = mapOf("b" to second.copy(checksum = "incorrect"))))
            val zip = ByteArrayOutputStream()
            PortableProject.writeAssets(doc, { ByteArrayInputStream(it.uri.toByteArray()) }, zip)
            assertFails { PortableProject.read(ByteArrayInputStream(zip.toByteArray()), directory, emptySet()) }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun legacySingleSourceArchiveStillOpens() {
        val directory = Files.createTempDirectory("legacy-asset").toFile()
        try {
            val doc = ProjectDocument(schemaVersion = 3, engineVersion = "old", savedAtEpochMillis = 0,
                project = ProjectState(source = first.copy(uri = "asset:source")))
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(ProjectCodec.encode(doc).toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("source")); zip.write(byteArrayOf(1,2,3)); zip.closeEntry()
            }
            val loaded = PortableProject.read(ByteArrayInputStream(bytes.toByteArray()), directory, emptySet())
            assertContentEquals(byteArrayOf(1,2,3), File(URI(loaded.project.source!!.uri)).readBytes())
        } finally { directory.deleteRecursively() }
    }
}
