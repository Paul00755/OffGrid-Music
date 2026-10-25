package com.example.util

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.BuildConfig
import com.example.service.PlaybackManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight crash reporting helper that captures uncaught exception stack traces,
 * thread states, memory status, and playback context, saving them to local files for
 * offline debugging and post-mortem analysis.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val CRASH_DIR_NAME = "crash_reports"
    private const val LATEST_CRASH_FILE = "crash_latest.txt"
    private const val MAX_CRASH_FILES = 10

    private var isInitialized = false
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        if (isInitialized) return
        val appContext = context.applicationContext

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleUncaughtException(appContext, thread, throwable)
            } catch (e: Throwable) {
                Log.e(TAG, "Error in crash reporter", e)
            } finally {
                // Forward to standard system handler or kill process safely
                defaultHandler?.uncaughtException(thread, throwable) ?: run {
                    Process.killProcess(Process.myPid())
                    System.exit(10)
                }
            }
        }
        isInitialized = true
        Log.i(TAG, "CrashReporter successfully initialized")
    }

    private fun handleUncaughtException(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val report = buildCrashReport(context, thread, throwable, timestamp)

        try {
            val dir = getCrashDir(context)
            if (!dir.exists()) {
                dir.mkdirs()
            }

            // Write latest crash file
            val latestFile = File(dir, LATEST_CRASH_FILE)
            latestFile.writeText(report)

            // Write timestamped historical crash file
            val timeFile = File(dir, "crash_${System.currentTimeMillis()}.txt")
            timeFile.writeText(report)

            // Clean up old reports exceeding MAX_CRASH_FILES
            pruneOldReports(dir)

            Log.e(TAG, "Crash report recorded at: ${timeFile.absolutePath}")
            Log.e(TAG, report)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist crash report", e)
        }
    }

    fun logNonFatal(context: Context, tag: String, throwable: Throwable, extraContext: Map<String, String>? = null) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.appendLine("=== NON-FATAL EXCEPTION REPORT ===")
            sb.appendLine("Timestamp: $timestamp")
            sb.appendLine("Tag: $tag")
            sb.appendLine("Message: ${throwable.message}")
            if (!extraContext.isNullOrEmpty()) {
                sb.appendLine("--- Extra Context ---")
                extraContext.forEach { (k, v) -> sb.appendLine("$k: $v") }
            }
            sb.appendLine("--- Stack Trace ---")
            sb.appendLine(getStackTraceString(throwable))

            val dir = getCrashDir(context)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "nonfatal_${System.currentTimeMillis()}.txt")
            file.writeText(sb.toString())
            pruneOldReports(dir)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to save non-fatal log", e)
        }
    }

    private fun buildCrashReport(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        timestamp: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("           CRASH REPORT - POST MORTEM")
        sb.appendLine("==================================================")
        sb.appendLine("Timestamp: $timestamp")
        sb.appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Package: ${context.packageName}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
        sb.appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Fingerprint: ${Build.FINGERPRINT}")
        sb.appendLine()

        sb.appendLine("--- MEMORY STATUS ---")
        val runtime = Runtime.getRuntime()
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
        val totalMemoryMb = runtime.totalMemory() / (1024 * 1024)
        val freeMemoryMb = runtime.freeMemory() / (1024 * 1024)
        val usedMemoryMb = totalMemoryMb - freeMemoryMb
        sb.appendLine("Heap: Used ${usedMemoryMb}MB / Total ${totalMemoryMb}MB (Max: ${maxMemoryMb}MB)")
        sb.appendLine()

        sb.appendLine("--- PLAYBACK CONTEXT ---")
        try {
            val playbackState = PlaybackManager.activeInstance?.playbackState?.value
            if (playbackState != null) {
                sb.appendLine("Is Playing: ${playbackState.isPlaying}")
                sb.appendLine("Current Song ID: ${playbackState.currentSong?.id}")
                sb.appendLine("Current Song Title: ${playbackState.currentSong?.title}")
                sb.appendLine("Current Song Artist: ${playbackState.currentSong?.artist}")
                sb.appendLine("Position: ${playbackState.positionMs}ms / ${playbackState.durationMs}ms")
                sb.appendLine("Queue Size: ${playbackState.queue.size} (Index: ${playbackState.queueIndex})")
                sb.appendLine("Repeat Mode: ${playbackState.repeatMode}")
                sb.appendLine("Shuffle: ${playbackState.isShuffleEnabled}")
            } else {
                sb.appendLine("PlaybackManager instance is null / idle")
            }
        } catch (e: Throwable) {
            sb.appendLine("Error collecting playback context: ${e.message}")
        }
        sb.appendLine()

        sb.appendLine("--- CRASHING THREAD ---")
        @Suppress("DEPRECATION")
        sb.appendLine("Thread: ${thread.name} [ID: ${thread.id}] (State: ${thread.state}, Daemon: ${thread.isDaemon})")
        sb.appendLine()

        sb.appendLine("--- EXCEPTION STACK TRACE ---")
        sb.appendLine(getStackTraceString(throwable))
        sb.appendLine()

        sb.appendLine("--- ACTIVE THREADS DUMP ---")
        try {
            val allStackTraces = Thread.getAllStackTraces()
            allStackTraces.entries.take(8).forEach { (t, stack) ->
                @Suppress("DEPRECATION")
                if (t.id != thread.id) {
                    @Suppress("DEPRECATION")
                    sb.appendLine("Thread '${t.name}' (id: ${t.id}, state: ${t.state}):")
                    stack.take(5).forEach { elem ->
                        sb.appendLine("    at $elem")
                    }
                    sb.appendLine()
                }
            }
        } catch (_: Throwable) {}

        sb.appendLine("==================================================")
        return sb.toString()
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    private fun getCrashDir(context: Context): File {
        return File(context.filesDir, CRASH_DIR_NAME)
    }

    private fun pruneOldReports(dir: File) {
        try {
            val files = dir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".txt") && name != LATEST_CRASH_FILE }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            if (files.size > MAX_CRASH_FILES) {
                files.drop(MAX_CRASH_FILES).forEach { it.delete() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to prune old crash logs", e)
        }
    }

    fun getLatestCrashReport(context: Context): String? {
        return try {
            val file = File(getCrashDir(context), LATEST_CRASH_FILE)
            if (file.exists() && file.length() > 0) file.readText() else null
        } catch (e: Throwable) {
            null
        }
    }

    fun getAllCrashReports(context: Context): List<File> {
        return try {
            getCrashDir(context).listFiles { _, name -> name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun clearCrashReports(context: Context) {
        try {
            getCrashDir(context).deleteRecursively()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to clear crash reports", e)
        }
    }

    private fun getReportsExportDir(context: Context): File {
        val dir = File(context.cacheDir, "reports")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun exportReportsAsTxt(context: Context): File {
        val exportDir = getReportsExportDir(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(exportDir, "offgrid_diagnostics_$timestamp.txt")

        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("        OFFGRID MUSIC - DIAGNOSTIC REPORT")
        sb.appendLine("==================================================")
        sb.appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Android OS: ${Build.VERSION.RELEASE}")
        sb.appendLine("Package: ${context.packageName}")

        val reports = getAllCrashReports(context)
        sb.appendLine("Total Stored Reports: ${reports.size}")
        sb.appendLine("==================================================")
        sb.appendLine()

        if (reports.isEmpty()) {
            sb.appendLine("Status: Engine operating normally. No crashes or non-fatal exceptions recorded.")
        } else {
            reports.forEachIndexed { index, file ->
                sb.appendLine("--- REPORT #${index + 1}: ${file.name} ---")
                sb.appendLine("Last Modified: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))}")
                try {
                    sb.appendLine(file.readText())
                } catch (e: Exception) {
                    sb.appendLine("Error reading file: ${e.message}")
                }
                sb.appendLine()
            }
        }

        exportFile.writeText(sb.toString())
        return exportFile
    }

    fun exportReportsAsJson(context: Context): File {
        val exportDir = getReportsExportDir(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(exportDir, "offgrid_diagnostics_$timestamp.json")

        val root = JSONObject()
        root.put("exportTimestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
        root.put("appVersion", BuildConfig.VERSION_NAME)
        root.put("appVersionCode", BuildConfig.VERSION_CODE)
        root.put("packageName", context.packageName)

        val deviceObj = JSONObject()
        deviceObj.put("manufacturer", Build.MANUFACTURER)
        deviceObj.put("model", Build.MODEL)
        deviceObj.put("product", Build.PRODUCT)
        deviceObj.put("androidRelease", Build.VERSION.RELEASE)
        deviceObj.put("sdkInt", Build.VERSION.SDK_INT)
        deviceObj.put("fingerprint", Build.FINGERPRINT)
        root.put("device", deviceObj)

        val memoryObj = JSONObject()
        val runtime = Runtime.getRuntime()
        memoryObj.put("maxMemoryMb", runtime.maxMemory() / (1024 * 1024))
        memoryObj.put("totalMemoryMb", runtime.totalMemory() / (1024 * 1024))
        memoryObj.put("freeMemoryMb", runtime.freeMemory() / (1024 * 1024))
        root.put("memory", memoryObj)

        val reportsArray = JSONArray()
        val reports = getAllCrashReports(context)
        reports.forEach { file ->
            val reportObj = JSONObject()
            reportObj.put("fileName", file.name)
            reportObj.put("lastModifiedMs", file.lastModified())
            reportObj.put("lastModifiedFormatted", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified())))
            try {
                reportObj.put("rawContent", file.readText())
            } catch (e: Exception) {
                reportObj.put("error", e.message ?: "Failed to read content")
            }
            reportsArray.put(reportObj)
        }
        root.put("reportsCount", reports.size)
        root.put("reports", reportsArray)

        exportFile.writeText(root.toString(2))
        return exportFile
    }

    fun getShareUriForFile(context: Context, file: File): android.net.Uri {
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun shareExportedReport(context: Context, file: File, mimeType: String) {
        try {
            val uri = getShareUriForFile(context, file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "OffGrid Music Diagnostics (${file.name})")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Export Diagnostics (${file.extension.uppercase()})"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share diagnostic report", e)
        }
    }
}
