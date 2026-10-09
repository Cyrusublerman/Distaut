package com.cyrusublerman.distaut.projects

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.cyrusublerman.distaut.model.SourceAsset
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlin.math.sqrt

data class LoadedSource(val asset: SourceAsset, val bitmap: Bitmap)

class SourceAssetLoader(
    private val context: Context,
    private val diagnostic: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun importSource(
        uri: Uri,
        previewMaximumDimension: Int = 2560,
    ): LoadedSource = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        diagnostic("Import selected: scheme=${uri.scheme} authority=${uri.authority}", null)

        try {
            val metadata = queryMetadata(uri)
            diagnostic(
                "Metadata: name=${metadata.displayName ?: "unknown"} " +
                    "mime=${metadata.mimeType ?: "unknown"} size=${metadata.sizeBytes ?: -1}",
                null,
            )

            val coroutine = currentCoroutineContext()
            val retained = retainSource(uri, metadata) { coroutine.ensureActive() }
            diagnostic(
                "Retained source: ${retained.file.name} bytes=${retained.sizeBytes} " +
                    "sha256=${retained.checksum.take(12)}…",
                null,
            )

            val decoded = decode(retained.uri, previewMaximumDimension)
            diagnostic(
                "Decoded preview: ${decoded.sourceWidth}x${decoded.sourceHeight} -> " +
                    "${decoded.bitmap.width}x${decoded.bitmap.height}",
                null,
            )

            LoadedSource(
                SourceAsset(
                    uri = retained.uri.toString(),
                    displayName = metadata.displayName,
                    mimeType = metadata.mimeType,
                    width = decoded.sourceWidth,
                    height = decoded.sourceHeight,
                    checksum = retained.checksum,
                    persistedPermission = false,
                    managedCopy = true,
                ),
                decoded.bitmap,
            ).also {
                diagnostic(
                    "Import complete in ${elapsedMillis(started)} ms",
                    null,
                )
            }
        } catch (error: Throwable) {
            diagnostic(
                "Import failed after ${elapsedMillis(started)} ms: " +
                    "${error::class.java.simpleName}: ${error.message}",
                error,
            )
            throw error
        }
    }

    suspend fun reopen(
        asset: SourceAsset,
        previewMaximumDimension: Int = 2560,
    ): Bitmap = withContext(Dispatchers.IO) {
        val uri = Uri.parse(asset.uri)
        diagnostic("Reopening source: scheme=${uri.scheme} name=${asset.displayName}", null)
        decode(uri, previewMaximumDimension).bitmap
    }

    suspend fun loadFull(asset: SourceAsset): Bitmap = withContext(Dispatchers.IO) {
        val uri = Uri.parse(asset.uri)
        diagnostic("Loading full source: ${asset.width}x${asset.height}", null)
        checkFullResolutionBudget(asset.width, asset.height)
        decode(uri, null).bitmap
    }

    suspend fun probe(asset: SourceAsset): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(asset.uri)
        val firstBytes = openStream(uri).use { input ->
            ByteArray(32).let { buffer ->
                val count = input.read(buffer)
                if (count <= 0) "" else buffer.take(count).joinToString(" ") {
                    "%02x".format(it.toInt() and 0xff)
                }
            }
        }
        "source readable; scheme=${uri.scheme}; firstBytes=$firstBytes"
    }

    private data class Metadata(
        val displayName: String?,
        val mimeType: String?,
        val sizeBytes: Long?,
    )

    private data class RetainedSource(
        val uri: Uri,
        val file: File,
        val checksum: String,
        val sizeBytes: Long,
    )

    private data class DecodedImage(
        val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    private fun retainSource(uri: Uri, metadata: Metadata, checkCancelled: () -> Unit): RetainedSource {
        val directory = File(context.filesDir, "sources").apply {
            if (!exists() && !mkdirs()) throw IOException("Unable to create source directory")
        }
        val temporary = File.createTempFile("import-", ".tmp", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L

        try {
            openStream(uri).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                    output.fd.sync()
                }
            }

            if (total <= 0L) throw IOException("Selected image contained no readable bytes")
            val checksum = digest.digest().joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            val extension = chooseExtension(metadata)
            val destination = File(directory, checksum + extension?.let { ".$it" }.orEmpty())

            if (destination.isFile) {
                temporary.delete()
            } else {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }

            return RetainedSource(
                uri = Uri.fromFile(destination),
                file = destination,
                checksum = checksum,
                sizeBytes = total,
            )
        } catch (error: Throwable) {
            temporary.delete()
            if (error is CancellationException) throw error
            throw IOException("Unable to retain selected image", error)
        }
    }

    private fun chooseExtension(metadata: Metadata): String? {
        val fromName = metadata.displayName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        if (fromName != null) return fromName

        return metadata.mimeType
            ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
    }

    private fun decode(uri: Uri, maximumDimension: Int?): DecodedImage {
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                return decodeWithImageDecoder(uri, maximumDimension)
            }.onFailure {
                if (it is IllegalArgumentException) throw it
                diagnostic(
                    "ImageDecoder failed; falling back to BitmapFactory: ${it.message}",
                    it,
                )
            }
        }
        return decodeWithBitmapFactory(uri, maximumDimension)
    }

    @androidx.annotation.RequiresApi(28)
    private fun decodeWithImageDecoder(
        uri: Uri,
        maximumDimension: Int?,
    ): DecodedImage {
        var sourceWidth = 0
        var sourceHeight = 0
        val source = if (uri.scheme == ContentResolver.SCHEME_FILE) {
            ImageDecoder.createSource(File(requireNotNull(uri.path)))
        } else {
            ImageDecoder.createSource(resolver, uri)
        }
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            sourceWidth = info.size.width
            sourceHeight = info.size.height
            if (maximumDimension == null) checkFullResolutionBudget(sourceWidth, sourceHeight)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            decoder.setOnPartialImageListener { false }
            val sampleSize = calculateSampleSize(sourceWidth, sourceHeight, maximumDimension)
            if (sampleSize > 1) decoder.setTargetSampleSize(sampleSize)
        }
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            sourceWidth = bitmap.width
            sourceHeight = bitmap.height
        }
        return DecodedImage(bitmap, sourceWidth, sourceHeight)
    }

    private fun decodeWithBitmapFactory(
        uri: Uri,
        maximumDimension: Int?,
    ): DecodedImage {
        val bounds = decodeBounds(uri)
        if (maximumDimension == null) checkFullResolutionBudget(bounds.first, bounds.second)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.first, bounds.second, maximumDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = openStream(uri).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException(
            "BitmapFactory could not decode the selected image. " +
                "The format may not be supported on Android ${Build.VERSION.RELEASE}.",
        )
        val orientation = runCatching { openStream(uri).use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val values = ExifOrientation.matrix(orientation)
        val matrix = Matrix().apply { setValues(values) }
        val oriented = if (orientation in 2..8) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) else bitmap
        if (oriented !== bitmap) bitmap.recycle()
        val swap = orientation in 5..8
        return DecodedImage(oriented, if (swap) bounds.second else bounds.first, if (swap) bounds.first else bounds.second)
    }

    private fun decodeBounds(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw IOException("Unable to read image dimensions")
        }
        return options.outWidth to options.outHeight
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        maximumDimension: Int?,
    ): Int {
        if (maximumDimension == null) return 1
        require(maximumDimension > 0)
        val runtime = Runtime.getRuntime()
        val available = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())).coerceAtLeast(0L)
        // Seven RGBA-sized allocations cover decode/orientation, source, intermediates and display.
        val pixelBudget = (available * 3 / 4 / 28).coerceAtLeast(1L)
        val scale = minOf(1.0, maximumDimension.toDouble() / maxOf(width, height),
            sqrt(pixelBudget.toDouble() / (width.toLong() * height)))
        var sample = 1
        while (1.0 / sample > scale && sample < (1 shl 29)) {
            sample *= 2
        }
        return sample
    }

    private fun queryMetadata(uri: Uri): Metadata {
        var displayName: String? = null
        var sizeBytes: Long? = null

        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        }.onFailure {
            diagnostic("Metadata query failed; continuing without provider metadata", it)
        }

        val mimeType = runCatching { resolver.getType(uri) }
            .onFailure { diagnostic("MIME type query failed", it) }
            .getOrNull()

        return Metadata(displayName, mimeType, sizeBytes)
    }

    private fun openStream(uri: Uri): InputStream {
        return if (uri.scheme == ContentResolver.SCHEME_FILE) {
            FileInputStream(File(requireNotNull(uri.path)))
        } else {
            resolver.openInputStream(uri)
                ?: throw IOException("Content provider returned no input stream")
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000L
}

/** Conservative bound for full-resolution bitmap, conversion and renderer scratch. */
fun checkFullResolutionBudget(width: Int, height: Int) {
    val runtime = Runtime.getRuntime()
    val used = runtime.totalMemory() - runtime.freeMemory()
    val available = (runtime.maxMemory() - used).coerceAtLeast(0L)
    val required = width.toLong() * height * 4 * 7
    require(required <= available * 3 / 4) {
        "Full-resolution processing needs about ${required / 1048576} MiB; choose a smaller export size"
    }
}
