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

            // 2. Defer heavy Room database compaction & maintenance until startup is completely idle (at most once per 24h)
            kotlinx.coroutines.delay(45000)

            val bootPrefs = getSharedPreferences("offgrid_boot_cache", android.content.Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastMaint = bootPrefs.getLong("last_db_maintenance_ms", 0L)
            if (now - lastMaint > 24L * 60L * 60L * 1000L) {
                bootPrefs.edit().putLong("last_db_maintenance_ms", now).apply()
                AppDatabase.performMaintenance(this)
            }
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

