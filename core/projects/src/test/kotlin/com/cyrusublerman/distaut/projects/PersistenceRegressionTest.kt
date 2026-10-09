package com.cyrusublerman.distaut.projects

import com.cyrusublerman.distaut.model.*
import com.cyrusublerman.distaut.render.PixelBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class PersistenceRegressionTest {
    @Test fun portableProjectRetainsSourceAcrossInstallations() {
        val directory=Files.createTempDirectory("distaut-package").toFile()
        try {
            val bytes="example source bytes".toByteArray()
            val state=ProjectState(source=SourceAsset("file:/unavailable/original",width=4,height=3),effects=listOf(EffectInstance("a","invert")))
            val document=ProjectDocument(engineVersion="test",savedAtEpochMillis=1,project=state)
            val zip=ByteArrayOutputStream()
            PortableProject.write(document,ByteArrayInputStream(bytes),zip)
            val loaded=PortableProject.read(ByteArrayInputStream(zip.toByteArray()),directory,setOf("invert"))
            val file=java.io.File(java.net.URI(loaded.project.source!!.uri))
            assertContentEquals(bytes,file.readBytes())
            assertEquals(state.effects,loaded.project.effects)
            assertTrue(loaded.project.source!!.managedCopy)
            assertNotNull(loaded.project.source!!.checksum)
        } finally { directory.deleteRecursively() }
    }
    @Test fun packageRejectsTraversalAndCorruptSourceAndCleansTemporaryFiles() {
        val directory=Files.createTempDirectory("distaut-bad-package").toFile()
        try {
            val out=ByteArrayOutputStream()
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("../escape")); it.write(1); it.closeEntry() }
            assertFails { PortableProject.read(ByteArrayInputStream(out.toByteArray()),directory,emptySet()) }
            assertTrue(directory.listFiles()!!.isEmpty())
            val state=ProjectState(source=SourceAsset("file:/source",width=1,height=1,checksum="wrong"))
            val zip=ByteArrayOutputStream()
            PortableProject.write(ProjectDocument(engineVersion="test",savedAtEpochMillis=1,project=state),ByteArrayInputStream(byteArrayOf(1)),zip)
            assertFails { PortableProject.read(ByteArrayInputStream(zip.toByteArray()),directory,emptySet()) }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun checkpointsReplaceWithoutLeavingPartialFiles() {
        val directory=Files.createTempDirectory("distaut-atomic").toFile()
        try {
            val file=java.io.File(directory,"state.json")
            AtomicDocument.write(file,"first".toByteArray())
            AtomicDocument.write(file,"second".toByteArray())
            assertEquals("second",file.readText())
            assertEquals(listOf("state.json"),directory.listFiles()!!.map{it.name})
        } finally { directory.deleteRecursively() }
    }
    @Test fun streamingPngRoundTripsColourAndAlpha() {
        val image=PixelBuffer(2,1,byteArrayOf(-1,0,0,-1,0,-1,0,-128))
        val out=ByteArrayOutputStream()
        PngWriter.write(image,out)
        val stream=java.io.DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val signature=ByteArray(8); stream.readFully(signature)
        assertContentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10),signature)
        val compressed=ByteArrayOutputStream()
        while (true) {
            val length=stream.readInt()
            val type=ByteArray(4); stream.readFully(type)
            val bytes=ByteArray(length); stream.readFully(bytes)
            val crc=java.util.zip.CRC32().apply { update(type); update(bytes) }
            assertEquals(crc.value.toInt(),stream.readInt())
            val name=type.toString(Charsets.US_ASCII)
            if (name=="IHDR") {
                val header=java.io.DataInputStream(ByteArrayInputStream(bytes))
                assertEquals(2,header.readInt()); assertEquals(1,header.readInt())
                assertEquals(8,header.readUnsignedByte()); assertEquals(6,header.readUnsignedByte())
            }
            if (name=="IDAT") compressed.write(bytes)
            if (name=="IEND") break
        }
        val rows=java.util.zip.InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).readBytes()
        assertContentEquals(byteArrayOf(0)+image.rgba,rows)
    }
    @Test fun allExifTransformsHaveExpectedCornerDirections() {
        val expected=listOf(1f to 2f,-1f to 2f,-1f to -2f,1f to -2f,2f to 1f,-2f to 1f,-2f to -1f,2f to -1f)
        for(orientation in 1..8) {
            val m=ExifOrientation.matrix(orientation)
            assertEquals(expected[orientation-1],(m[0]+2*m[1]) to (m[3]+2*m[4]))
        }
    }
}
