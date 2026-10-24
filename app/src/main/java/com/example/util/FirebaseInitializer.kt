package com.example.util

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Centralized, fail-safe Firebase initialization helper.
 *
 * Guarantees:
 * - Initializes FirebaseApp safely, with programmatic FirebaseOptions fallback if standard
 *   resource lookup (google-services.json string generation) fails or is missing.
 * - Prevents permanent caching of null FirebaseAuth/FirebaseFirestore instances.
 * - Thread-safe synchronization so multiple components (AuthManager, CloudSyncRepository,
 *   AutoBackupWorker) can call it concurrently without crashing or creating duplicate instances.
 */
object FirebaseInitializer {

    private const val TAG = "FirebaseInitializer"

    // Primary project credentials from google-services.json
    const val DEFAULT_WEB_CLIENT_ID = "844076337559-mvpmas9lvh40glt9vqd0qn5oe846999b.apps.googleusercontent.com"
    const val FIREBASE_APP_ID = "1:844076337559:android:98caa3bcda454f0e681cd2"
    const val FIREBASE_API_KEY = "AIzaSyDB9OhAoI6ijjTTfuxa5mbuRzF0xL8EoTU"
    const val FIREBASE_PROJECT_ID = "offgrid-music"
    const val FIREBASE_STORAGE_BUCKET = "offgrid-music.firebasestorage.app"
    const val FIREBASE_GCM_SENDER_ID = "844076337559"

    @Volatile
    private var isInitialized = false

    /**
     * Ensures FirebaseApp is properly initialized for the current process.
     * Tries standard auto-initialization from resources first, and falls back to
     * programmatic FirebaseOptions if resources are not yet generated or available.
     */
    @Synchronized
    fun ensureInitialized(context: Context): FirebaseApp? {
        val appContext = context.applicationContext

        // 1. If an instance already exists, return it
        val existingApps = FirebaseApp.getApps(appContext)
        if (existingApps.isNotEmpty()) {
            isInitialized = true
            return FirebaseApp.getInstance()
        }

        // 2. Attempt standard resource-based initialization
        try {
            val app = FirebaseApp.initializeApp(appContext)
            if (app != null) {
                isInitialized = true
                Log.i(TAG, "FirebaseApp successfully initialized from resources")
                return app
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Standard Firebase initialization failed: ${t.message}. Attempting explicit fallback.")
        }

        // 3. Fallback to programmatic options
        return try {
            val options = FirebaseOptions.Builder()
                .setApplicationId(FIREBASE_APP_ID)
                .setApiKey(FIREBASE_API_KEY)
                .setProjectId(FIREBASE_PROJECT_ID)
                .setStorageBucket(FIREBASE_STORAGE_BUCKET)
                .setGcmSenderId(FIREBASE_GCM_SENDER_ID)
                .build()

            val app = FirebaseApp.initializeApp(appContext, options)
            isInitialized = true
            Log.i(TAG, "FirebaseApp successfully initialized via explicit FirebaseOptions fallback")
            app
        } catch (t: Throwable) {
            Log.e(TAG, "Explicit FirebaseApp fallback initialization error: ${t.message}", t)
            null
        }
    }

    /**
     * Safely retrieves the FirebaseAuth instance, initializing FirebaseApp if needed.
     */
    fun getAuth(context: Context): FirebaseAuth? {
        return try {
            val app = ensureInitialized(context)
            if (app != null) {
                FirebaseAuth.getInstance(app)
            } else {
                FirebaseAuth.getInstance()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire FirebaseAuth instance: ${t.message}")
            null
        }
    }

    /**
     * Safely retrieves the FirebaseFirestore instance, initializing FirebaseApp if needed.
     */
    fun getFirestore(context: Context): FirebaseFirestore? {
        return try {
            val app = ensureInitialized(context)
            if (app != null) {
                FirebaseFirestore.getInstance(app)
            } else {
                FirebaseFirestore.getInstance()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire FirebaseFirestore instance: ${t.message}")
            null
        }
    }
}
