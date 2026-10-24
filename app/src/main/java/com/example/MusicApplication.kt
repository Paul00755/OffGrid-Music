package com.example

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.example.data.local.AppDatabase
import com.example.util.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MusicApplication : Application(), ImageLoaderFactory {

    companion object {
        private const val TAG = "MusicApplication"
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)
        com.example.util.FirebaseInitializer.ensureInitialized(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            performStartupDiagnostics()
        }
    }

    /**
     * Verifies essential internal storage directory access and Room database readiness
     * prior to main UI launching to prevent silent crashes and unhandled state corruptions.
     */
    private suspend fun performStartupDiagnostics() {
        try {
            // 1. Verify internal directory access and writable state
            val dirsToVerify = listOf(
                filesDir,
                cacheDir,
                cacheDir.resolve("image_cache"),
                filesDir.resolve("crash_reports"),
                filesDir.resolve("lyrics_cache")
            )
            for (dir in dirsToVerify) {
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    Log.d(TAG, "Directory ${dir.name} created: $created")
                }
                if (!dir.canRead() || !dir.canWrite()) {
                    Log.w(TAG, "Directory permission warning: ${dir.absolutePath}")
                }
            }

            // 2. In-place upgrade safety: auto-restore library only if database is completely empty
            com.example.data.local.DatabaseStorageManager.autoRestoreLibraryIfEmpty(this)

            // 3. Defer heavy Room database compaction, snapshot backup & maintenance until after cold startup
            kotlinx.coroutines.delay(6000)

            // Auto-save snapshot and run maintenance after UI is responsive
            com.example.data.local.DatabaseStorageManager.autoSaveLibrarySnapshot(this)

            val db = AppDatabase.getInstance(this)
            val dbName = db.openHelper.databaseName
            val isOpenable = db.openHelper.readableDatabase.isOpen
            Log.i(TAG, "Startup diagnostics completed successfully. Database: $dbName (Ready: $isOpenable)")

            // 4. Perform background database compacting, metrics pruning and storage de-duplication
            AppDatabase.performMaintenance(this)
        } catch (t: Throwable) {
            Log.e(TAG, "Startup diagnostics encountered an issue", t)
            CrashReporter.logNonFatal(this, TAG, t, mapOf("phase" to "startup_diagnostics"))
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                coil.Coil.imageLoader(this).memoryCache?.clear()
                com.example.ui.components.ArtworkResolutionCache.clearMemory()
                com.example.util.ArtworkColorExtractor.clearCache()
                com.example.util.ArtworkManager.clearMemoryCache()
            }
        } catch (_: Exception) {}
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            coil.Coil.imageLoader(this).memoryCache?.clear()
            com.example.ui.components.ArtworkResolutionCache.clearMemory()
            com.example.util.ArtworkColorExtractor.clearCache()
            com.example.util.ArtworkManager.clearMemoryCache()
        } catch (_: Exception) {}
    }
}

