package com.cyrusublerman.distaut.projects

import java.io.File

/** Only invoked by the explicit storage-cleanup action. Never follows links out of the source directory. */
object SourceStorage {
    fun removeUnused(directory: File, protectedPaths: Set<String>): Long {
        if (!directory.isDirectory) return 0
        val root = directory.canonicalFile
        val protected = protectedPaths.map { File(it).canonicalPath }.toSet()
        var reclaimed = 0L
        for (file in directory.listFiles().orEmpty()) {
            if (!file.isFile || file.canonicalFile.parentFile != root || file.canonicalPath in protected) continue
            val bytes = file.length()
            if (file.delete()) reclaimed += bytes
        }
        return reclaimed
    }
}
