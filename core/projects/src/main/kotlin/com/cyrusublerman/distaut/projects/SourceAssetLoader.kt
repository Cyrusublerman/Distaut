package com.cyrusublerman.distaut.projects

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.cyrusublerman.distaut.model.SourceAsset
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LoadedSource(val asset: SourceAsset, val bitmap: Bitmap)

class SourceAssetLoader(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun importSource(uri: Uri, previewMaximumDimension: Int = 2560): LoadedSource = withContext(Dispatchers.IO) {
        val persisted = persistReadPermission(uri)
        val metadata = queryMetadata(uri)
        val orientation = readOrientation(uri)
        val bounds = decodeBounds(uri)
        val dimensions = orientedDimensions(bounds.first, bounds.second, orientation)
        val bitmap = decode(uri, previewMaximumDimension, orientation)
        val checksum = checksum(uri)
        val durableUri = if (persisted) uri else copyToManagedStorage(uri, checksum, metadata.first)
        LoadedSource(
            SourceAsset(
                uri = durableUri.toString(),
                displayName = metadata.first,
                mimeType = metadata.second,
                width = dimensions.first,
                height = dimensions.second,
                checksum = checksum,
                persistedPermission = persisted,
                managedCopy = !persisted,
            ),
            bitmap,
        )
    }

    suspend fun reopen(asset: SourceAsset, previewMaximumDimension: Int = 2560): Bitmap = withContext(Dispatchers.IO) {
        val uri = Uri.parse(asset.uri)
        decode(uri, previewMaximumDimension, readOrientation(uri))
    }

    suspend fun loadFull(asset: SourceAsset): Bitmap = withContext(Dispatchers.IO) {
        val uri = Uri.parse(asset.uri)
        decode(uri, null, readOrientation(uri))
    }

    private fun decode(uri: Uri, maximumDimension: Int?, orientation: Int): Bitmap {
        val bounds = decodeBounds(uri)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.first, bounds.second, maximumDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Unable to decode source image")
        return applyOrientation(bitmap, orientation)
    }

    private fun decodeBounds(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Unable to read source image")
        require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image dimensions" }
        return options.outWidth to options.outHeight
    }

    private fun calculateSampleSize(width: Int, height: Int, maximumDimension: Int?): Int {
        if (maximumDimension == null) return 1
        require(maximumDimension > 0)
        var sample = 1
        while (maxOf(width / sample, height / sample) > maximumDimension && sample <= 64) sample *= 2
        return sample
    }

    private fun readOrientation(uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }

    private fun orientedDimensions(width: Int, height: Int, orientation: Int): Pair<Int, Int> = when (orientation) {
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270 -> height to width
        else -> width to height
    }

    private fun queryMetadata(uri: Uri): Pair<String?, String?> {
        var displayName: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) displayName = cursor.getString(index)
        }
        return displayName to resolver.getType(uri)
    }

    private fun checksum(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: throw IOException("Unable to checksum source")
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun copyToManagedStorage(uri: Uri, checksum: String, displayName: String?): Uri {
        val directory = java.io.File(context.filesDir, "sources").apply { mkdirs() }
        val extension = displayName?.substringAfterLast('.', "")?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }?.lowercase()
        val destination = java.io.File(directory, checksum + extension?.let { ".$it" }.orEmpty())
        if (!destination.isFile) {
            val temporary = java.io.File(directory, "${destination.name}.tmp")
            resolver.openInputStream(uri)?.use { source -> temporary.outputStream().use(source::copyTo) }
                ?: throw IOException("Unable to copy source image")
            if (!temporary.renameTo(destination)) {
                temporary.delete(); throw IOException("Unable to retain source image")
            }
        }
        return Uri.fromFile(destination)
    }

    private fun persistReadPermission(uri: Uri): Boolean = runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true
    }.getOrDefault(false)
}
