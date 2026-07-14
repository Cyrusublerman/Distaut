package com.cyrusublerman.distaut.editor

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.cyrusublerman.distaut.model.ProjectState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiagnosticLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
}

data class DiagnosticEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: DiagnosticLevel,
    val component: String,
    val message: String,
    val details: String? = null,
)

object DiagnosticsLog {
    private const val TAG = "Distaut"
    private const val MAX_ENTRIES = 500
    private val lock = Any()
    private var nextId = 1L
    private val sessionId = UUID.randomUUID().toString()

    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    fun debug(component: String, message: String) = append(DiagnosticLevel.DEBUG, component, message)
    fun info(component: String, message: String) = append(DiagnosticLevel.INFO, component, message)
    fun warning(component: String, message: String, error: Throwable? = null) =
        append(DiagnosticLevel.WARNING, component, message, error)
    fun error(component: String, message: String, error: Throwable? = null) =
        append(DiagnosticLevel.ERROR, component, message, error)

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
        info("diagnostics", "Log cleared")
    }

    fun report(context: Context, project: ProjectState): String {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { info ->
            activityManager?.getMemoryInfo(info)
        }
        val runtime = Runtime.getRuntime()
        val source = project.source
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        return buildString {
            appendLine("DISTAUT DIAGNOSTIC REPORT")
            appendLine("session=$sessionId")
            appendLine("generated=${formatter.format(Date())}")
            appendLine("package=${context.packageName}")
            appendLine("versionName=${packageInfo?.versionName ?: "unknown"}")
            @Suppress("DEPRECATION")
            val versionCode = packageInfo?.let {
                if (Build.VERSION.SDK_INT >= 28) {
                    it.longVersionCode
                } else {
                    it.versionCode.toLong()
                }
            } ?: -1L
            appendLine("versionCode=$versionCode")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("product=${Build.PRODUCT}")
            appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("memory.available=${memoryInfo.availMem}")
            appendLine("memory.low=${memoryInfo.lowMemory}")
            appendLine("heap.used=${runtime.totalMemory() - runtime.freeMemory()}")
            appendLine("heap.max=${runtime.maxMemory()}")
            appendLine("files.free=${context.filesDir.freeSpace}")
            appendLine("project.revision=${project.revision}")
            appendLine("project.effects=${project.effects.size}")
            appendLine("project.solo=${project.soloEffectId}")
            appendLine("source.uri=${source?.uri}")
            appendLine("source.name=${source?.displayName}")
            appendLine("source.mime=${source?.mimeType}")
            appendLine("source.dimensions=${source?.width}x${source?.height}")
            appendLine("source.checksum=${source?.checksum}")
            appendLine("source.managedCopy=${source?.managedCopy}")
            appendLine()
            appendLine("EVENTS")
            entries.value.forEach { entry ->
                append(formatter.format(Date(entry.timestampMillis)))
                append(" ")
                append(entry.level.name.padEnd(7))
                append(" [")
                append(entry.component)
                append("] ")
                appendLine(entry.message)
                entry.details?.lineSequence()?.forEach { line ->
                    appendLine("    $line")
                }
            }
        }
    }

    private fun append(
        level: DiagnosticLevel,
        component: String,
        message: String,
        error: Throwable? = null,
    ) {
        val details = error?.stackTraceToString()
        val entry = synchronized(lock) {
            DiagnosticEntry(
                id = nextId++,
                timestampMillis = System.currentTimeMillis(),
                level = level,
                component = component,
                message = message,
                details = details,
            ).also { next ->
                _entries.value = (_entries.value + next).takeLast(MAX_ENTRIES)
            }
        }

        val line = "[${entry.component}] ${entry.message}"
        when (level) {
            DiagnosticLevel.DEBUG -> Log.d(TAG, line)
            DiagnosticLevel.INFO -> Log.i(TAG, line)
            DiagnosticLevel.WARNING -> Log.w(TAG, line, error)
            DiagnosticLevel.ERROR -> Log.e(TAG, line, error)
        }
    }
}
