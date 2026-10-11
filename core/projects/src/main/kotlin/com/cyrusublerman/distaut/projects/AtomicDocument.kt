package com.cyrusublerman.distaut.projects

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Failure never deletes the last good checkpoint. Unique temporary names prevent collisions. */
object AtomicDocument {
    fun write(destination: File, bytes: ByteArray) {
        val directory = requireNotNull(destination.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File.createTempFile(destination.name, ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
