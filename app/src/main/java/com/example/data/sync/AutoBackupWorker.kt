package com.example.data.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.data.local.AppDatabase
import com.example.data.local.UserPreferencesRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker responsible for automatic cloud backup.
 * 
 * Guarantees:
 * - Operates completely headless without creating any Player or Foreground Service.
 * - Respects network constraints (CONNECTED) so it safely defers when offline.
 * - Performs all database and Firestore operations on Dispatchers.IO.
 * - Skips redundant uploads if local data hash has not changed.
 */
class AutoBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AutoBackupWorker"
        const val UNIQUE_WORK_NAME = "com.example.music.auto_backup"

        /**
         * Schedules or safely updates the periodic background backup work.
         */
        fun scheduleOrUpdate(
            context: Context,
            frequency: BackupFrequency,
            enabled: Boolean
        ) {
            val workManager = WorkManager.getInstance(context)

            if (!enabled) {
                Log.d(TAG, "Automatic backup disabled. Cancelling scheduled periodic work.")
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }

            val intervalDays = when (frequency) {
                BackupFrequency.DAILY -> 1L
                BackupFrequency.WEEKLY -> 7L
                BackupFrequency.MONTHLY -> 30L
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicWorkRequest = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                intervalDays, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15, TimeUnit.MINUTES
                )
                .addTag("auto_cloud_backup")
                .build()

            // ExistingPeriodicWorkPolicy.UPDATE safely updates frequency and constraints
            // without creating duplicate workers or resetting progress if already queued.
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicWorkRequest
            )
            Log.d(TAG, "Scheduled auto backup worker with frequency: ${frequency.displayName} ($intervalDays days)")
        }

        /**
         * Cancels any scheduled auto backup work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Cancelled auto backup periodic work.")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting periodic auto backup worker execution...")

            if (FirebaseApp.getApps(applicationContext).isEmpty()) {
                FirebaseApp.initializeApp(applicationContext)
            }

            val auth = FirebaseAuth.getInstance()
            val uid = auth.currentUser?.uid
            if (uid.isNullOrBlank()) {
                Log.d(TAG, "No user signed in. Skipping automatic cloud backup.")
                return@withContext Result.success()
            }

            val db = AppDatabase.getInstance(applicationContext)
            val userPrefs = UserPreferencesRepository(applicationContext)

            val isEnabled = userPrefs.automaticBackupEnabled.first()
            if (!isEnabled) {
                Log.d(TAG, "Auto backup is disabled in settings. Skipping.")
                return@withContext Result.success()
            }

            val syncRepo = CloudSyncRepository.getInstance(
                context = applicationContext,
                songDao = db.songDao(),
                playlistDao = db.playlistDao(),
                historyDao = db.historyDao(),
                smartPlaylistDao = db.smartPlaylistDao(),
                userPreferences = userPrefs,
                folderDao = db.folderDao(),
                recentSearchDao = db.recentSearchDao()
            )

            val success = syncRepo.performBackupInternal(isAutoBackup = true)
            if (success) {
                Log.i(TAG, "Auto backup completed successfully.")
                Result.success()
            } else {
                Log.w(TAG, "Auto backup did not complete successfully; requesting retry.")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup worker error: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
