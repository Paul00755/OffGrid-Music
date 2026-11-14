package com.example.data.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.data.local.*
import com.example.data.model.Song
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class CloudSyncRepository(
    private val context: Context,
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val historyDao: HistoryDao,
    private val smartPlaylistDao: SmartPlaylistDao,
    private val userPreferences: UserPreferencesRepository,
    private val folderDao: FolderDao? = null,
    private val recentSearchDao: RecentSearchDao? = null,
    private val injectedFirestore: FirebaseFirestore? = null,
    private val injectedAuth: FirebaseAuth? = null
) {
    companion object {
        private const val TAG = "CloudSyncRepository"
        private const val USERS_COLLECTION = "users"
        private const val SETTINGS_COLLECTION = "settings"
        private const val SETTINGS_DOC_PREFERENCES = "preferences"
        private const val FAVORITES_COLLECTION = "favorites"
        private const val PLAYLISTS_COLLECTION = "playlists"
        private const val SMART_PLAYLISTS_COLLECTION = "smart_playlists"
        private const val FOLDERS_COLLECTION = "folders"
        private const val SEARCH_COLLECTION = "recent_searches"
        private const val HISTORY_COLLECTION = "history"
        private const val PLAYLIST_HISTORY_COLLECTION = "playlist_history"
        private const val LYRICS_OFFSETS_COLLECTION = "lyrics_offsets"
        private const val LYRICS_COLLECTION = "cloud_lyrics"

        @Volatile
        private var instance: CloudSyncRepository? = null

        fun getInstance(
            context: Context,
            songDao: SongDao,
            playlistDao: PlaylistDao,
            historyDao: HistoryDao,
            smartPlaylistDao: SmartPlaylistDao,
            userPreferences: UserPreferencesRepository,
            folderDao: FolderDao? = null,
            recentSearchDao: RecentSearchDao? = null
        ): CloudSyncRepository {
            return instance ?: synchronized(this) {
                instance ?: CloudSyncRepository(
                    context = context.applicationContext,
                    songDao = songDao,
                    playlistDao = playlistDao,
                    historyDao = historyDao,
                    smartPlaylistDao = smartPlaylistDao,
                    userPreferences = userPreferences,
                    folderDao = folderDao,
                    recentSearchDao = recentSearchDao
                ).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val authStateMutex = Mutex()
    private var activeSyncJob: Job? = null

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _backupStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val backupStatus: StateFlow<BackupStatus> = _backupStatus.asStateFlow()

    private val _lastSyncedTimestamp = MutableStateFlow<Long>(0L)
    val lastSyncedTimestamp: StateFlow<Long> = _lastSyncedTimestamp.asStateFlow()

    private val _lastBackupTimestamp = MutableStateFlow<Long>(0L)
    val lastBackupTimestamp: StateFlow<Long> = _lastBackupTimestamp.asStateFlow()

    // In-memory cache of cloud favorites for fast re-matching during/after library scans
    private val cachedCloudFavorites = ConcurrentHashMap<String, CloudFavoriteSong>()

    // In-memory cache of cloud playback events for fast history relinking & play stats calculation
    private val cachedCloudHistoryEvents = ConcurrentHashMap<String, CloudPlaybackEvent>()

    // In-memory cache of cloud synced lyrics and user lyrics offsets for post-scan resolution
    private val cachedCloudLyrics = ConcurrentHashMap<String, CloudLyricsEntry>()
    private val cachedCloudLyricsOffsets = ConcurrentHashMap<String, CloudLyricsOffset>()

    // In-memory map of local playlist ID -> Cloud Playlist ID
    private val playlistLocalToCloudMap = ConcurrentHashMap<Long, String>()
    private val playlistCloudToLocalMap = ConcurrentHashMap<String, Long>()

    // Tracks local playlist modification timestamps so custom user ordering is never overwritten by stale cloud data
    private val playlistMetaPrefs by lazy {
        context.getSharedPreferences("playlist_sync_meta", Context.MODE_PRIVATE)
    }
    private val lyricsDao by lazy { AppDatabase.getInstance(context).lyricsDao() }

    private fun getLocalPlaylistUpdatedAt(playlistId: Long): Long {
        return try {
            playlistMetaPrefs.getLong("pl_updated_$playlistId", 0L)
        } catch (_: Exception) {
            0L
        }
    }

    private fun setLocalPlaylistUpdatedAt(playlistId: Long, timestamp: Long) {
        try {
            playlistMetaPrefs.edit().putLong("pl_updated_$playlistId", timestamp).apply()
        } catch (_: Exception) {}
    }

    private suspend fun ensureCloudPlaylistId(playlist: PlaylistEntity): String {
        val cloudId = playlist.cloudId.takeIf { it.isNotBlank() }
            ?: playlistLocalToCloudMap[playlist.id]?.takeIf { it.isNotBlank() }
            ?: "pl_${java.util.UUID.randomUUID()}"
        if (playlist.cloudId != cloudId) playlistDao.updatePlaylistCloudId(playlist.id, cloudId)
        playlistLocalToCloudMap[playlist.id] = cloudId
        playlistCloudToLocalMap[cloudId] = playlist.id
        return cloudId
    }

    private fun legacyPlaylistCloudId(name: String, createdAt: Long): String =
        "pl_${createdAt}_${sha256(name.trim().lowercase())}"

    // Currently authenticated UID being synchronized
    @Volatile
    private var activeUid: String? = null

    private val firestore: FirebaseFirestore?
        get() = injectedFirestore ?: com.example.util.FirebaseInitializer.getFirestore(context)

    private val auth: FirebaseAuth?
        get() = injectedAuth ?: com.example.util.FirebaseInitializer.getAuth(context)

    @androidx.annotation.VisibleForTesting
    internal var testCurrentUidOverride: String? = null

    fun getCurrentUid(): String? {
        return try {
            auth?.currentUser?.uid?.takeIf { it.isNotBlank() } ?: testCurrentUidOverride
        } catch (_: Throwable) {
            testCurrentUidOverride
        }
    }

    init {
        scope.launch {
            userPreferences.lastSyncedTimestamp.collect {
                _lastSyncedTimestamp.value = if (getCurrentUid() != null) it else 0L
            }
        }
        scope.launch {
            userPreferences.lastBackupTimestamp.collect {
                _lastBackupTimestamp.value = if (getCurrentUid() != null) it else 0L
            }
        }
    }

    /**
     * Clears all account-owned local data from Room and DataStore.
     * Preserves device-local songs and folder configurations.
     */
    suspend fun clearAccountOwnedLocalData() = withContext(Dispatchers.IO) {
        try {
            songDao.clearAllFavorites()
            playlistDao.clearAllPlaylists()
            playlistDao.clearAllPlaylistSongs()
            playlistDao.clearAllPlaylistPlayEvents()
            smartPlaylistDao.clearAllSmartPlaylists()
            historyDao.clearHistory()
            recentSearchDao?.clearAll()
            folderDao?.clearAllFolders()
            playlistMetaPrefs.edit().clear().apply()
            userPreferences.resetAccountSpecificData()
            Log.d(TAG, "Account-owned local data cleared successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing account-owned local data", e)
        }
    }

    /**
     * Called when authentication state changes or user signs in/out.
     */
    private suspend fun prepareAuthState(newUid: String?): Boolean = authStateMutex.withLock {
            val authUser = auth?.currentUser
            val authoritativeUid = authUser?.uid?.takeIf { it.isNotBlank() } ?: newUid?.takeIf { it.isNotBlank() }
            val lastUid = userPreferences.getLastActiveUid()

            if (authoritativeUid == null || newUid == null) {
                activeSyncJob?.cancel()
                activeSyncJob = null
                activeUid = null
                cachedCloudFavorites.clear()
                cachedCloudHistoryEvents.clear()
                cachedCloudLyrics.clear()
                cachedCloudLyricsOffsets.clear()
                playlistLocalToCloudMap.clear()
                playlistCloudToLocalMap.clear()
                _syncStatus.value = SyncStatus.Idle
                _backupStatus.value = BackupStatus.Idle
                _lastSyncedTimestamp.value = 0L
                _lastBackupTimestamp.value = 0L

                if (!lastUid.isNullOrBlank()) {
                    Log.d(TAG, "Transitioning from authenticated user ($lastUid) to clean guest state. Clearing account data.")
                    clearAccountOwnedLocalData()
                }
                Log.d(TAG, "Cloud sync stopped: guest/local state active")
                return@withLock false
            }

            if (!lastUid.isNullOrBlank() && lastUid != authoritativeUid) {
                Log.d(TAG, "Switching accounts from $lastUid to $authoritativeUid. Clearing previous user's local account data.")
                activeSyncJob?.cancel()
                activeSyncJob = null
                cachedCloudFavorites.clear()
                cachedCloudHistoryEvents.clear()
                cachedCloudLyrics.clear()
                cachedCloudLyricsOffsets.clear()
                playlistLocalToCloudMap.clear()
                playlistCloudToLocalMap.clear()
                clearAccountOwnedLocalData()
            }

            activeUid = authoritativeUid
            userPreferences.setLastActiveUid(authoritativeUid)
            true
    }

    /** Observe account changes for isolation and cleanup only; app startup never downloads cloud state. */
    fun onAuthStateChanged(newUid: String?) {
        scope.launch {
            if (prepareAuthState(newUid)) {
                checkAndTriggerScheduledAutoBackupIfDue()
            }
        }
    }

    fun checkAndTriggerScheduledAutoBackupIfDue() {
        scope.launch(Dispatchers.IO) {
            try {
                val uid = getCurrentUid() ?: return@launch
                if (!userPreferences.getAutomaticBackupEnabledSync()) return@launch
                val freq = userPreferences.getBackupFrequencySync()
                val intervalMs = when (freq) {
                    BackupFrequency.DAILY -> 24L * 60L * 60L * 1000L
                    BackupFrequency.WEEKLY -> 7L * 24L * 60L * 60L * 1000L
                    BackupFrequency.MONTHLY -> 30L * 24L * 60L * 60L * 1000L
                }
                val lastBackup = userPreferences.lastBackupTimestamp.firstOrNull() ?: 0L
                val now = System.currentTimeMillis()
                if (lastBackup > 0L && (now - lastBackup) >= intervalMs && uid.isNotBlank()) {
                    Log.d(TAG, "Scheduled auto-backup interval (${freq.displayName}) elapsed; running background backup")
                    performBackupInternal(isAutoBackup = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Scheduled auto-backup check skipped: ${e.message}")
            }
        }
    }

    /** The single automatic sync path: a successful Google or email/password sign-in. */
    fun onSuccessfulSignIn(uid: String) {
        if (uid.isBlank()) return
        scope.launch {
            if (prepareAuthState(uid) && getCurrentUid() == uid) {
                triggerFullSync(isInitialLogin = true)
            }
        }
    }

    /**
     * Executes a full synchronization sequence immediately after sign-in or manual sync:
     * 1. Check & restore from latest backup snapshot (if present)
     * 2. Settings & Profile Photo
     * 3. Folders (included & excluded)
     * 4. Favorites
     * 5. Playlists & Smart Playlists (preserving custom ordering)
     * 6. History, Most Played, Recently Played & Listening Stats
     * 7. Recent Search History
     * 8. Lyrics Offsets
     *
     * Every step is isolated in its own try/catch so a failure or missing collection in one step
     * never aborts the remaining sync steps.
     */
    fun triggerFullSync(isInitialLogin: Boolean = false, manualSync: Boolean = false) {
        val uid = getCurrentUid() ?: return
        activeUid = uid
        activeSyncJob?.cancel()
        activeSyncJob = scope.launch {
            try {
                _syncStatus.value = SyncStatus.Syncing
                _backupStatus.value = BackupStatus.Idle
                val fs = firestore
                if (fs == null) {
                    _syncStatus.value = SyncStatus.Error("Firestore unavailable")
                    return@launch
                }

                try {
                    userPreferences.setLastActiveUid(uid)
                } catch (_: Exception) {}

                SongIdentityHelper.clearCache()

                val backupCollection = fs.collection(USERS_COLLECTION).document(uid).collection("backup")
                var preSyncedLyricSongIds: Set<Long> = emptySet()
                val latestBackupDoc = backupCollection.document("latest").get(Source.SERVER).await()
                val latestBackupData = latestBackupDoc.data
                if (latestBackupDoc.exists() && latestBackupData != null) {
                    if (!UserBackupPayload.isCompleteSnapshot(latestBackupData, uid)) {
                        throw IllegalStateException("Cloud backup snapshot is incomplete or belongs to another account")
                    }
                    if (getCurrentUid() != uid) throw CancellationException("Account changed during cloud sync")
                    val payload = UserBackupPayload.fromMap(latestBackupData)

                    // Fetch history, playlist history, and lyrics snapshots in parallel for fast sync
                    val (cloudHistory, cloudPlaylistHistory, cloudLyrics) = coroutineScope {
                        val historyDeferred = async { fetchCloudHistoryEvents(uid, fs) }
                        val plHistoryDeferred = async { fetchCloudPlaylistPlayEvents(uid, fs) }
                        val lyricsDeferred = async {
                            if (payload.version >= 3) fetchCloudLyrics(uid, payload, fs) else emptyList()
                        }
                        Triple(historyDeferred.await(), plHistoryDeferred.await(), lyricsDeferred.await())
                    }

                    if (payload.version >= 2 && cloudHistory.map { it.sessionId }.distinct().size < payload.historyCount) {
                        throw IllegalStateException("Cloud listening history is incomplete; local data was left unchanged")
                    }
                    if (payload.version >= 2 && cloudPlaylistHistory.size < payload.playlistPlayEventCount) {
                        throw IllegalStateException("Cloud playlist history is incomplete; local data was left unchanged")
                    }
                    Log.d(TAG, "Restoring authoritative backup snapshot for user $uid")
                    preSyncedLyricSongIds = restoreFromBackupSnapshot(
                        uid,
                        latestBackupData,
                        cloudHistory = cloudHistory,
                        cloudPlaylistHistory = cloudPlaylistHistory,
                        cloudLyrics = cloudLyrics
                    )
                } else {
                    // The first-login migration policy remains in place for accounts without a snapshot.
                    // All reads must complete before an account can be classified as empty.
                    if (checkIsCloudAccountEmpty(uid, fs)) {
                        if (manualSync) {
                            // Even on manual sync, if the user has local data and cloud is empty, perform initial backup/sync
                            performInitialMigration(uid, fs)
                        } else {
                            Log.d(TAG, "Cloud account is empty. Performing initial migration from local Room/DataStore...")
                            performInitialMigration(uid, fs)
                        }
                    } else {
                        Log.d(TAG, "Cloud account has legacy data. Restoring existing collections in parallel...")
                        preflightLegacyCloudState(uid, fs)
                        coroutineScope {
                            val sJob = launch {
                                syncSettings(uid, fs, forceRestoreIfInitial = isInitialLogin || manualSync, pushLocalIfNewer = !manualSync)
                                if (!manualSync) syncProfilePicture(uid, fs)
                            }
                            val fJob = launch { syncFavorites(uid, fs, pushLocalMissing = true, replaceLocal = false) }
                            val pJob = launch { syncPlaylists(uid, fs, pushLocalMissing = true, forceCloudRestore = false) }
                            val spJob = launch { syncSmartPlaylists(uid, fs, pushLocalMissing = true, replaceLocal = false) }
                            val hJob = launch { syncHistory(uid, fs, pushLocalMissing = true) }
                            val rsJob = launch { syncRecentSearches(uid, fs, pushLocalMissing = true, replaceLocal = false) }
                            val loJob = launch { syncLyricsOffsets(uid, fs, pushLocalMissing = true, replaceLocal = false) }
                            val clJob = launch {
                                try {
                                    syncLiveCloudLyrics(uid, fs, pushLocalMissing = true, replaceLocal = false)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Live cloud lyrics sync skipped: ${e.message}")
                                }
                            }
                            joinAll(sJob, fJob, pJob, spJob, hJob, rsJob, loJob, clJob)
                        }
                        syncCloudPlaylistPlayEvents(uid, fs)
                    }
                }

                if (getCurrentUid() != uid) throw CancellationException("Account changed during cloud sync")
                coroutineScope {
                    val relinkJob = async { relinkHistoryAndRecomputePlayStats() }
                    val lrcJob = async { ensureLocalCompanionLrcFilesAndCleanupTxt(skipSongIds = preSyncedLyricSongIds) }
                    val rootJob = async { if (!manualSync) syncUserRootDocument(uid, fs) }
                    awaitAll(relinkJob, lrcJob, rootJob)
                }

                val now = System.currentTimeMillis()
                userPreferences.setLastSyncedTimestamp(now)
                _lastSyncedTimestamp.value = now
                _syncStatus.value = SyncStatus.Synced(now)
                Log.d(TAG, "Cloud synchronization finished successfully at $now")
            } catch (e: CancellationException) {
                Log.d(TAG, "Cloud synchronization cancelled")
                _syncStatus.value = SyncStatus.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Cloud synchronization failed", e)
                _syncStatus.value = SyncStatus.Error(e.localizedMessage ?: "Sync error")
            }
        }
    }

    private suspend fun syncUserRootDocument(uid: String, fs: FirebaseFirestore) {
        val user = auth?.currentUser ?: return
        val userDoc = fs.collection(USERS_COLLECTION).document(uid)
        val data = mapOf(
            "uid" to uid,
            "email" to user.email,
            "displayName" to user.displayName,
            "photoUrl" to user.photoUrl?.toString(),
            "lastSyncedAt" to System.currentTimeMillis()
        )
        userDoc.set(data, SetOptions.merge()).await()
    }

    private suspend fun checkIsCloudAccountEmpty(uid: String, fs: FirebaseFirestore): Boolean = coroutineScope {
        val userRef = fs.collection(USERS_COLLECTION).document(uid)
        val settingsDef = async { userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES).get(Source.SERVER).await() }
        val favsDef = async { userRef.collection(FAVORITES_COLLECTION).limit(1).get(Source.SERVER).await() }
        val histDef = async { userRef.collection(HISTORY_COLLECTION).limit(1).get(Source.SERVER).await() }
        val plDef = async { userRef.collection(PLAYLISTS_COLLECTION).limit(1).get(Source.SERVER).await() }
        val spDef = async { userRef.collection(SMART_PLAYLISTS_COLLECTION).limit(1).get(Source.SERVER).await() }
        val fldDef = async { userRef.collection(FOLDERS_COLLECTION).limit(1).get(Source.SERVER).await() }
        val srchDef = async { userRef.collection(SEARCH_COLLECTION).limit(1).get(Source.SERVER).await() }
        val offDef = async { userRef.collection(LYRICS_OFFSETS_COLLECTION).limit(1).get(Source.SERVER).await() }
        val lyrDef = async { userRef.collection(LYRICS_COLLECTION).limit(1).get(Source.SERVER).await() }
        val plHistDef = async { userRef.collection(PLAYLIST_HISTORY_COLLECTION).limit(1).get(Source.SERVER).await() }

        !settingsDef.await().exists() && favsDef.await().isEmpty && histDef.await().isEmpty &&
            plDef.await().isEmpty && spDef.await().isEmpty && fldDef.await().isEmpty &&
            srchDef.await().isEmpty && offDef.await().isEmpty && lyrDef.await().isEmpty &&
            plHistDef.await().isEmpty
    }

    /** Read every legacy collection before any legacy restore code can mutate local state. */
    private suspend fun preflightLegacyCloudState(uid: String, fs: FirebaseFirestore) = coroutineScope {
        val userRef = fs.collection(USERS_COLLECTION).document(uid)
        val settingsRead = async {
            userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES).get(Source.SERVER).await()
        }
        val reads = listOf(
            async { userRef.collection(FAVORITES_COLLECTION).get(Source.SERVER).await().documents.map { it.data } },
            async { userRef.collection(PLAYLISTS_COLLECTION).get(Source.SERVER).await().documents.map { it.data } },
            async { userRef.collection(SMART_PLAYLISTS_COLLECTION).get(Source.SERVER).await().documents.map { it.data } },
            async { userRef.collection(HISTORY_COLLECTION).get(Source.SERVER).await().documents.map { it.data } },
            async { userRef.collection(SEARCH_COLLECTION).get(Source.SERVER).await().documents.map { it.data } },
            async { userRef.collection(LYRICS_OFFSETS_COLLECTION).get(Source.SERVER).await().documents.map { it.data } },
            async { userRef.collection(LYRICS_COLLECTION).get(Source.SERVER).await().documents.map { it.data } },
            async { userRef.collection(FOLDERS_COLLECTION).get(Source.SERVER).await().documents.map { it.data } },
            async { userRef.collection(PLAYLIST_HISTORY_COLLECTION).get(Source.SERVER).await().documents.map { it.data } }
        )
        val settingsDoc = settingsRead.await()
        if (settingsDoc.exists() && settingsDoc.data == null) {
            throw IllegalStateException("Cloud settings are incomplete; local data was left unchanged")
        }
        reads.awaitAll().forEach { documents ->
            if (documents.any { it == null }) {
                throw IllegalStateException("Cloud data is incomplete; local data was left unchanged")
            }
        }
    }

    /**
     * Initial Migration:
     * When user signs in to a new empty cloud account, upload existing local data.
     */
    private suspend fun performInitialMigration(uid: String, fs: FirebaseFirestore) {
        if (getCurrentUid() != uid) throw CancellationException("Account changed before initial migration")
        if (!performBackupInternal(isAutoBackup = true)) {
            throw IllegalStateException("Initial cloud migration failed; local data was preserved")
        }
    }

    /**
     * Synchronizes Settings:
     * Deterministic last-write-wins based on updatedAt timestamp (or forces restore on fresh login).
     */
    private suspend fun syncSettings(
        uid: String,
        fs: FirebaseFirestore,
        forceRestoreIfInitial: Boolean = false,
        pushLocalIfNewer: Boolean = true
    ) {
        val userRef = fs.collection(USERS_COLLECTION).document(uid)
        val docSnap = userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES).get(Source.SERVER).await()

        val localSettings = userPreferences.exportUserSettings()
        if (docSnap.exists() && docSnap.data != null) {
            val cloudSettings = CloudUserSettings.fromMap(docSnap.data!!)
            if (forceRestoreIfInitial || localSettings.updatedAt == 0L || cloudSettings.updatedAt > localSettings.updatedAt) {
                val settingsToApply = if (pushLocalIfNewer) cloudSettings else cloudSettings.copy(
                    automaticBackupEnabled = localSettings.automaticBackupEnabled,
                    backupFrequency = localSettings.backupFrequency,
                    customAvatarUri = localSettings.customAvatarUri
                )
                userPreferences.importUserSettings(settingsToApply, checkTimestamp = false)
                Log.d(TAG, "Restored settings from cloud (updatedAt: ${cloudSettings.updatedAt})")
            } else if (pushLocalIfNewer && localSettings.updatedAt > cloudSettings.updatedAt) {
                userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES)
                    .set(localSettings.toMap(), SetOptions.merge()).await()
                Log.d(TAG, "Uploaded newer local settings to cloud (updatedAt: ${localSettings.updatedAt})")
            }
        } else if (pushLocalIfNewer) {
            userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES)
                .set(localSettings.toMap(), SetOptions.merge()).await()
        }
    }

    /**
     * Synchronizes Favorites:
     * Restores cloud favorites and pushes newly favorited local tracks.
     */
    private suspend fun syncFavorites(
        uid: String,
        fs: FirebaseFirestore,
        pushLocalMissing: Boolean = true,
        replaceLocal: Boolean = false
    ) {
        val favoritesRef = fs.collection(USERS_COLLECTION).document(uid).collection(FAVORITES_COLLECTION)
        val snapshot = favoritesRef.get(Source.SERVER).await()
        val cloudFavorites = snapshot.documents.map { doc ->
            val data = doc.data ?: throw IllegalStateException("Cloud favorite ${doc.id} is unreadable")
            CloudFavoriteSong.fromMap(data).also {
                if (it.songSignature.isBlank()) throw IllegalStateException("Cloud favorite ${doc.id} has no track identity")
            }
        }

        if (replaceLocal) {
            val songIds = songDao.getAllSongsOnce().filter { it.isFavorite }.map { it.id }
            songIds.chunked(400).forEach { songDao.setFavoritesBatch(it, false) }
            cachedCloudFavorites.clear()
        }

        for (cloudFav in cloudFavorites) {
            if (cloudFav.isFavorite && cloudFav.songSignature.isNotBlank()) {
                cachedCloudFavorites[cloudFav.songSignature] = cloudFav
            }
        }

        // Rematch against currently discovered songs in Room
        matchAndApplyCloudFavoritesToRoom()

        // Push any local favorites not yet in cloud using WriteBatch
        if (!pushLocalMissing) return
        val localFavorites = songDao.getAllSongsOnce().filter { it.isFavorite }
        var favBatch = fs.batch()
        var favBatchCount = 0
        for (song in localFavorites) {
            val sig = SongIdentityHelper.computeSignature(song)
            if (!cachedCloudFavorites.containsKey(sig)) {
                val filename = song.path.substringAfterLast('/')
                val cloudFav = CloudFavoriteSong(
                    songSignature = sig,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    relativeFilename = filename,
                    isFavorite = true,
                    timestamp = if (song.lastPlayed > 0) song.lastPlayed else song.dateAdded
                )
                cachedCloudFavorites[sig] = cloudFav
                favBatch.set(favoritesRef.document(sig), cloudFav.toMap(), SetOptions.merge())
                favBatchCount++
                if (favBatchCount >= 450) {
                    favBatch.commit().await()
                    favBatch = fs.batch()
                    favBatchCount = 0
                }
            }
        }
        if (favBatchCount > 0) {
            favBatch.commit().await()
        }
    }

    /**
     * Matches cached cloud favorites against all local songs in Room.
     * Chunked in batches of 400 to avoid SQLite's 999 bind parameter limit.
     */
    suspend fun matchAndApplyCloudFavoritesToRoom() = withContext(Dispatchers.IO) {
        if (cachedCloudFavorites.isEmpty()) return@withContext
        val allSongs = songDao.getAllSongsOnce()
        if (allSongs.isEmpty()) return@withContext

        val favsBySig = cachedCloudFavorites.filter { it.value.isFavorite }
        val favsByCoreSig = favsBySig.values.groupBy {
            SongIdentityHelper.computeCoreSignature(it.title, it.artist, it.durationMs, it.relativeFilename)
        }
        val favsByNormalizedKey = favsBySig.values.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, it.relativeFilename).lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }
        val favsByPrimaryArtistKey = favsBySig.values.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, it.relativeFilename).lowercase()}|${SongIdentityHelper.extractPrimaryArtist(it.artist)}"
        }
        val favsByTitleOnly = favsBySig.values.groupBy {
            com.example.util.TagCleaner.cleanTitle(it.title, it.relativeFilename).lowercase()
        }
        val favsByFilename = favsBySig.values.filter { it.relativeFilename.isNotBlank() }.groupBy {
            it.relativeFilename.lowercase()
        }

        val matchingIds = mutableListOf<Long>()
        for (song in allSongs) {
            if (song.isFavorite) continue
            val localSig = SongIdentityHelper.computeSignature(song)
            if (favsBySig.containsKey(localSig)) {
                matchingIds.add(song.id)
                continue
            }

            val filename = song.path.substringAfterLast('/')
            val coreSig = SongIdentityHelper.computeCoreSignature(song.title, song.artist, song.durationMs, filename)
            if (favsByCoreSig[coreSig]?.isNotEmpty() == true) {
                matchingIds.add(song.id)
                continue
            }

            val cleanTitle = com.example.util.TagCleaner.cleanTitle(song.title, filename).lowercase()
            val cleanArtist = com.example.util.TagCleaner.cleanArtist(song.artist).lowercase()
            val normKey = "$cleanTitle|$cleanArtist"
            if (favsByNormalizedKey[normKey]?.isNotEmpty() == true) {
                matchingIds.add(song.id)
                continue
            }

            val primaryArtistKey = "$cleanTitle|${SongIdentityHelper.extractPrimaryArtist(song.artist)}"
            if (favsByPrimaryArtistKey[primaryArtistKey]?.isNotEmpty() == true) {
                matchingIds.add(song.id)
                continue
            }

            val filenameLower = filename.lowercase()
            val filenameMatch = favsByFilename[filenameLower]?.firstOrNull {
                it.durationMs <= 0L || Math.abs((song.durationMs - it.durationMs) / 1000L) <= 6L
            }
            if (filenameMatch != null) {
                matchingIds.add(song.id)
                continue
            }

            val titleDurationMatch = favsByTitleOnly[cleanTitle]?.firstOrNull {
                it.durationMs <= 0L || Math.abs((song.durationMs - it.durationMs) / 1000L) <= 5L
            }
            if (titleDurationMatch != null) {
                matchingIds.add(song.id)
            }
        }

        if (matchingIds.isNotEmpty()) {
            matchingIds.chunked(400).forEach { chunk ->
                songDao.setFavoritesBatch(chunk, true)
            }
        }
    }

    private fun resolveSongFromCloudItem(
        signature: String,
        title: String,
        artist: String,
        filename: String,
        durationMs: Long,
        songsBySig: Map<String, SongEntity>,
        songsByNormKey: Map<String, List<SongEntity>>,
        songsByFilename: Map<String, List<SongEntity>>,
        songsByCleanTitle: Map<String, List<SongEntity>>
    ): SongEntity? {
        if (signature == "unresolved" && title.isBlank() && filename.isBlank()) return null

        // 1. Direct signature match
        if (signature.isNotBlank() && signature != "unresolved") {
            songsBySig[signature]?.let { return it }
        }

        val cleanTitle = com.example.util.TagCleaner.cleanTitle(title, filename).lowercase().trim()
        val cleanTitleRaw = com.example.util.TagCleaner.cleanTitle(title, "").lowercase().trim()
        val cleanArtist = com.example.util.TagCleaner.cleanArtist(artist).lowercase().trim()
        val primaryArtist = SongIdentityHelper.extractPrimaryArtist(artist)

        if (cleanTitle.isBlank() || cleanTitle == "unknown title") return null

        // 2. Normalized Title + Artist match
        songsByNormKey["$cleanTitle|$cleanArtist"]?.firstOrNull()?.let { return it }
        if (cleanTitleRaw.isNotBlank() && cleanTitleRaw != "unknown title" && cleanTitleRaw != cleanTitle) {
            songsByNormKey["$cleanTitleRaw|$cleanArtist"]?.firstOrNull()?.let { return it }
        }

        // 3. Exact filename + duration match
        if (filename.isNotBlank()) {
            val fileCandidates = songsByFilename[filename.lowercase()]
            if (!fileCandidates.isNullOrEmpty()) {
                fileCandidates.firstOrNull {
                    durationMs <= 0L || Math.abs((it.durationMs - durationMs) / 1000L) <= 6L
                }?.let { return it }
                if (fileCandidates.size == 1) return fileCandidates.first()
            }
        }

        // 4. Clean title candidates (exact or with primary artist / duration tolerance)
        val titleCandidates = songsByCleanTitle[cleanTitle]
            ?: (if (cleanTitleRaw.isNotBlank() && cleanTitleRaw != "unknown title") songsByCleanTitle[cleanTitleRaw] else null)
        if (!titleCandidates.isNullOrEmpty()) {
            if (titleCandidates.size == 1) return titleCandidates.first()
            if (primaryArtist.isNotBlank() && primaryArtist != "unknown artist") {
                titleCandidates.firstOrNull {
                    val candArtist = com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()
                    candArtist.contains(primaryArtist) || primaryArtist.contains(SongIdentityHelper.extractPrimaryArtist(it.artist))
                }?.let { return it }
            }
            titleCandidates.firstOrNull {
                durationMs > 0L && Math.abs((it.durationMs - durationMs) / 1000L) <= 6L
            }?.let { return it }
            return titleCandidates.first()
        }

        // 5. Filename-cleaned-as-title lookup (critical when a track was renamed from raw filename to clean title or vice versa)
        if (filename.isNotBlank()) {
            val cleanedFromFilename = com.example.util.TagCleaner.cleanTitle(filename.substringBeforeLast('.'), filename).lowercase().trim()
            if (cleanedFromFilename.isNotBlank() && cleanedFromFilename != "unknown title") {
                val fileTitleCandidates = songsByCleanTitle[cleanedFromFilename]
                if (!fileTitleCandidates.isNullOrEmpty()) {
                    if (fileTitleCandidates.size == 1) return fileTitleCandidates.first()
                    fileTitleCandidates.firstOrNull {
                        durationMs <= 0L || Math.abs((it.durationMs - durationMs) / 1000L) <= 6L
                    }?.let { return it }
                }
            }
        }

        // 6. Alphanumeric fuzzy title + artist/duration fallback across songsBySig values
        val targetAlphaTitle = SongIdentityHelper.normalizeAlphanumeric(cleanTitle.ifBlank { filename.substringBeforeLast('.') })
        if (targetAlphaTitle.length >= 4) {
            val alphaMatches = songsBySig.values.filter { song ->
                val songCleanTitle = com.example.util.TagCleaner.cleanTitle(song.title, song.path.substringAfterLast('/'))
                val songAlpha = SongIdentityHelper.normalizeAlphanumeric(songCleanTitle)
                songAlpha == targetAlphaTitle
            }
            if (alphaMatches.size == 1) return alphaMatches.first()
            if (alphaMatches.size > 1) {
                if (primaryArtist.isNotBlank() && primaryArtist != "unknown artist") {
                    alphaMatches.firstOrNull {
                        val candPrimary = SongIdentityHelper.extractPrimaryArtist(it.artist)
                        candPrimary == primaryArtist || it.artist.lowercase().contains(primaryArtist)
                    }?.let { return it }
                }
                alphaMatches.firstOrNull {
                    durationMs > 0L && Math.abs((it.durationMs - durationMs) / 1000L) <= 5L
                }?.let { return it }
            }
        }
        return null
    }

    /**
     * Synchronizes Playlists:
     * Downloads cloud playlists and reconstructs them locally with stable IDs.
     * Self-heals any unlinked songs from cloud and preserves local custom song order & additions.
     */
    private suspend fun syncPlaylists(
        uid: String,
        fs: FirebaseFirestore,
        pushLocalMissing: Boolean = true,
        forceCloudRestore: Boolean = false
    ) = withContext(Dispatchers.IO) {
        try {
            playlistDao.deleteOrphanSongIdsInPlaylists()
        } catch (_: Exception) {}

        val playlistsRef = fs.collection(USERS_COLLECTION).document(uid).collection(PLAYLISTS_COLLECTION)
        val snapshot = playlistsRef.get(Source.SERVER).await()
        val cloudPlaylists = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: if (forceCloudRestore) {
                throw IllegalStateException("Cloud playlist ${doc.id} is unreadable; local playlists were left unchanged")
            } else return@mapNotNull null
            val cloudPl = CloudPlaylist.fromMap(data)
            if (cloudPl.name.isBlank()) {
                if (forceCloudRestore) throw IllegalStateException("Cloud playlist ${doc.id} has no name")
                return@mapNotNull null
            }
            if (forceCloudRestore) {
                val rawSongs = data["songs"] as? List<*>
                    ?: throw IllegalStateException("Cloud playlist ${doc.id} has incomplete ordering data")
                if (rawSongs.any { raw ->
                        val item = raw as? Map<*, *> ?: return@any true
                        (item["songSignature"] as? String).isNullOrBlank() || (item["position"] as? Number) == null
                    }) {
                    throw IllegalStateException("Cloud playlist ${doc.id} has invalid song ordering data")
                }
            }
            cloudPl
        }
        val allSongs = songDao.getAllSongsOnce()
        val existingPlaylists = playlistDao.getAllPlaylistsOnce()
        val existingByName = existingPlaylists.associateBy { it.name.trim().lowercase() }.toMutableMap()
        val existingByCloudId = existingPlaylists.filter { it.cloudId.isNotBlank() }.associateBy { it.cloudId }

        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val songsByNormKey = allSongs.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, it.path.substringAfterLast('/')).lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }.filterKeys { !it.startsWith("|") }
        val songsByFilename = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }.filterKeys { it.isNotBlank() }
        val songsByCleanTitle = allSongs.groupBy {
            com.example.util.TagCleaner.cleanTitle(it.title, it.path.substringAfterLast('/')).lowercase()
        }.filterKeys { it.isNotBlank() }
        val restoredPlaylistIds = HashSet<Long>()

        for (cloudPl in cloudPlaylists) {
            val key = cloudPl.name.trim().lowercase()
            val canonicalCloudId = cloudPl.cloudId.ifBlank { legacyPlaylistCloudId(cloudPl.name, cloudPl.createdAt) }
            val localPlaylist = existingByCloudId[canonicalCloudId] ?: existingByName[key]
            val updatedPlaylist = PlaylistEntity(
                id = localPlaylist?.id ?: 0L,
                name = cloudPl.name,
                createdAt = if (cloudPl.createdAt > 0L) cloudPl.createdAt else localPlaylist?.createdAt ?: System.currentTimeMillis(),
                isPinned = cloudPl.isPinned || (localPlaylist?.isPinned == true),
                artworkUri = cloudPl.artworkUri?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?: localPlaylist?.artworkUri,
                cloudId = canonicalCloudId
            )
            val playlistId = playlistDao.insertPlaylist(updatedPlaylist)
            restoredPlaylistIds += playlistId
            existingByName[key] = updatedPlaylist.copy(id = playlistId)
            playlistLocalToCloudMap[playlistId] = canonicalCloudId
            playlistCloudToLocalMap[canonicalCloudId] = playlistId

            val localOrderedSongs = playlistDao.getPlaylistSongsOnce(playlistId)
            val localUpdatedAt = getLocalPlaylistUpdatedAt(playlistId)

            // Resolve all songs from the cloud playlist document
            val sortedCloudSongs = cloudPl.songs.sortedBy { it.position }
            val cloudResolvedSongs = mutableListOf<SongEntity>()
            val seenCloudIds = HashSet<Long>()
            for (item in sortedCloudSongs) {
                val matchedSong = resolveSongFromCloudItem(
                    signature = item.songSignature,
                    title = item.title,
                    artist = item.artist,
                    filename = item.filename,
                    durationMs = item.durationMs,
                    songsBySig = songsBySig,
                    songsByNormKey = songsByNormKey,
                    songsByFilename = songsByFilename,
                    songsByCleanTitle = songsByCleanTitle
                )
                if (matchedSong != null && seenCloudIds.add(matchedSong.id)) {
                    cloudResolvedSongs.add(matchedSong)
                }
            }

            // Determine merged song order:
            // - Never lose valid songs that exist in cloudResolvedSongs or localOrderedSongs!
            // - If local playlist was updated more recently than cloud AND has >= songs, keep local order first and heal any missing cloud tracks.
            // - Otherwise use cloud order first and preserve any newly added local tracks at the end.
            val mergedSongs = mutableListOf<SongEntity>()
            val mergedIds = HashSet<Long>()
            val isLocalNewer = !forceCloudRestore && localOrderedSongs.isNotEmpty() &&
                localUpdatedAt > cloudPl.updatedAt && cloudPl.updatedAt > 0L

            if (isLocalNewer && localOrderedSongs.size >= cloudResolvedSongs.size) {
                for (s in localOrderedSongs) {
                    if (mergedIds.add(s.id)) mergedSongs.add(s)
                }
                for (s in cloudResolvedSongs) {
                    if (mergedIds.add(s.id)) mergedSongs.add(s)
                }
            } else {
                for (s in cloudResolvedSongs) {
                    if (mergedIds.add(s.id)) mergedSongs.add(s)
                }
                if (!forceCloudRestore) {
                    for (s in localOrderedSongs) {
                        if (mergedIds.add(s.id)) mergedSongs.add(s)
                    }
                }
            }

            if (mergedSongs.isNotEmpty() || forceCloudRestore) {
                val crossRefs = mergedSongs.mapIndexed { idx, s ->
                    PlaylistSongCrossRef(playlistId, s.id, idx)
                }
                playlistDao.updatePlaylistOrder(playlistId, crossRefs)
            }

            // If local had more tracks or we healed tracks from both sides, push the unified playlist back to live Firestore
            if (pushLocalMissing && mergedSongs.isNotEmpty() &&
                (isLocalNewer || mergedSongs.size > cloudResolvedSongs.size || cloudPl.songs.size != mergedSongs.size)
            ) {
                val now = maxOf(System.currentTimeMillis(), localUpdatedAt, cloudPl.updatedAt)
                val updatedCloudSongs = mergedSongs.mapIndexed { idx, s ->
                    CloudPlaylistSongItem(
                        position = idx,
                        songSignature = SongIdentityHelper.computeSignature(s),
                        title = s.title,
                        artist = s.artist,
                        album = s.album,
                        durationMs = s.durationMs,
                        filename = s.path.substringAfterLast('/')
                    )
                }
                val pushedPl = cloudPl.copy(
                    cloudId = canonicalCloudId,
                    updatedAt = now,
                    songs = updatedCloudSongs
                )
                setLocalPlaylistUpdatedAt(playlistId, now)
                playlistsRef.document(canonicalCloudId).set(pushedPl.toMap(), SetOptions.merge())
            } else if (cloudPl.updatedAt > 0L) {
                setLocalPlaylistUpdatedAt(playlistId, cloudPl.updatedAt)
            }
        }

        if (forceCloudRestore) {
            existingPlaylists.filter { it.id !in restoredPlaylistIds }.forEach { stale ->
                playlistDao.clearPlaylistSongs(stale.id)
                playlistDao.deletePlaylist(stale.id)
                playlistLocalToCloudMap.remove(stale.id)?.let(playlistCloudToLocalMap::remove)
            }
            return@withContext
        }

        // Push newly created local playlists that aren't in cloud yet
        if (!pushLocalMissing) return@withContext
        for (localPl in playlistDao.getAllPlaylistsOnce()) {
            if (!playlistLocalToCloudMap.containsKey(localPl.id)) {
                val cloudId = ensureCloudPlaylistId(localPl)

                val songsInPlaylist = playlistDao.getPlaylistSongsOnce(localPl.id)
                val cloudSongs = songsInPlaylist.mapIndexed { idx, s ->
                    CloudPlaylistSongItem(
                        position = idx,
                        songSignature = SongIdentityHelper.computeSignature(s),
                        title = s.title,
                        artist = s.artist,
                        album = s.album,
                        durationMs = s.durationMs,
                        filename = s.path.substringAfterLast('/')
                    )
                }
                val now = System.currentTimeMillis()
                val cloudPl = CloudPlaylist(
                    cloudId = cloudId,
                    name = localPl.name,
                    createdAt = localPl.createdAt,
                    updatedAt = now,
                    isPinned = localPl.isPinned,
                    artworkUri = localPl.artworkUri?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                    songs = cloudSongs
                )
                setLocalPlaylistUpdatedAt(localPl.id, now)
                playlistsRef.document(cloudId).set(cloudPl.toMap(), SetOptions.merge())
            }
        }
    }

    /**
     * Synchronizes Listening History and updates Most Played & Recently Played stats on SongEntity.
     */
    private suspend fun fetchCloudHistoryEvents(uid: String, fs: FirebaseFirestore): List<CloudPlaybackEvent> {
        val historyRef = fs.collection(USERS_COLLECTION).document(uid).collection(HISTORY_COLLECTION)
        val cloudEvents = mutableListOf<CloudPlaybackEvent>()
        val eventIds = HashSet<String>()
        var cursor: com.google.firebase.firestore.DocumentSnapshot? = null
        while (true) {
            var query = historyRef.orderBy("startedAt").limit(400)
            cursor?.let { query = query.startAfter(it) }
            val page = query.get(Source.SERVER).await()
            if (page.isEmpty) break
            for (doc in page.documents) {
                val data = doc.data ?: throw IllegalStateException("Cloud listening history contains an unreadable event")
                val event = CloudPlaybackEvent.fromMap(data)
                if (event.sessionId.isBlank()) throw IllegalStateException("Cloud listening history contains an event without an identity")
                if (!eventIds.add(event.sessionId)) throw IllegalStateException("Cloud listening history contains duplicate event identities")
                if (event.startedAt < 0L || event.listenedDurationMs < 0L) {
                    throw IllegalStateException("Cloud listening history contains an invalid event")
                }
                cloudEvents.add(event)
            }
            cursor = page.documents.last()
            if (page.size() < 400) break
        }
        return cloudEvents
    }

    private suspend fun fetchCloudPlaylistPlayEvents(uid: String, fs: FirebaseFirestore): List<CloudPlaylistPlayEvent> {
        val eventsRef = fs.collection(USERS_COLLECTION).document(uid).collection(PLAYLIST_HISTORY_COLLECTION)
        val events = mutableListOf<CloudPlaylistPlayEvent>()
        val eventIds = HashSet<String>()
        var cursor: com.google.firebase.firestore.DocumentSnapshot? = null
        while (true) {
            var query = eventsRef.orderBy("timestamp").limit(400)
            cursor?.let { query = query.startAfter(it) }
            val page = query.get(Source.SERVER).await()
            if (page.isEmpty) break
            for (doc in page.documents) {
                val data = doc.data ?: throw IllegalStateException("Cloud playlist history contains an unreadable event")
                val event = CloudPlaylistPlayEvent.fromMap(data)
                if (event.eventId.isBlank() || event.eventId != doc.id || event.playlistCloudId.isBlank() || event.timestamp < 0L) {
                    throw IllegalStateException("Cloud playlist history contains an invalid event")
                }
                if (!eventIds.add(event.eventId)) throw IllegalStateException("Cloud playlist history contains duplicate event identities")
                events += event
            }
            cursor = page.documents.last()
            if (page.size() < 400) break
        }
        return events
    }

    private suspend fun syncCloudPlaylistPlayEvents(uid: String, fs: FirebaseFirestore) {
        applyCloudPlaylistPlayEvents(fetchCloudPlaylistPlayEvents(uid, fs))
    }

    private suspend fun applyCloudPlaylistPlayEvents(events: List<CloudPlaylistPlayEvent>) {
        val existingPlaylistEvents = playlistDao.getAllPlaylistPlayEventsOnce()
            .map { "${it.playlistId}:${it.timestamp}" }.toHashSet()
        val playlistEventsToInsert = events.mapNotNull { event ->
            val localPlaylistId = playlistCloudToLocalMap[event.playlistCloudId] ?: return@mapNotNull null
            if (!existingPlaylistEvents.add("$localPlaylistId:${event.timestamp}")) return@mapNotNull null
            PlaylistPlayEventEntity(playlistId = localPlaylistId, timestamp = event.timestamp)
        }
        if (playlistEventsToInsert.isNotEmpty()) playlistDao.insertPlaylistPlayEvents(playlistEventsToInsert)
    }

    private suspend fun syncHistory(uid: String, fs: FirebaseFirestore, pushLocalMissing: Boolean = true) {
        val historyRef = fs.collection(USERS_COLLECTION).document(uid).collection(HISTORY_COLLECTION)
        val cloudEvents = fetchCloudHistoryEvents(uid, fs)

        // Do not apply a partial page sequence. History is append-only during reconciliation, so
        // distinct valid listening events survive playlist/favorite changes and retries are idempotent.
        val allSongs = songDao.getAllSongsOnce()
        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val songsByNormKey = allSongs.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }.filterKeys { !it.startsWith("|") }
        val songsByCleanTitle = allSongs.groupBy {
            com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase()
        }.filterKeys { it.isNotBlank() }
        val existingSessionIds = historyDao.getAllHistoryOnce().mapNotNull { it.sessionId.takeIf(String::isNotBlank) }.toHashSet()
        val toInsert = mutableListOf<PlaybackHistoryEntity>()
        for (event in cloudEvents) {
            cachedCloudHistoryEvents[event.sessionId] = event
            if (!existingSessionIds.add(event.sessionId)) continue
            val matchedSong = resolveSongFromCloudItem(
                signature = event.songSignature,
                title = event.title,
                artist = event.artist,
                filename = "",
                durationMs = event.durationMs,
                songsBySig = songsBySig,
                songsByNormKey = songsByNormKey,
                songsByFilename = emptyMap(),
                songsByCleanTitle = songsByCleanTitle
            )
            toInsert.add(
                PlaybackHistoryEntity(
                    id = 0L,
                    trackId = matchedSong?.id ?: 0L,
                    startedAt = event.startedAt,
                    endedAt = event.endedAt,
                    listenedDurationMs = event.listenedDurationMs,
                    sourceContext = event.sourceContext,
                    completionPercentage = event.completionPercentage,
                    sessionId = event.sessionId,
                    isQualified = event.isQualified,
                    createdAt = event.createdAt
                )
            )
        }
        toInsert.chunked(400).forEach { chunk -> historyDao.insertPlaybackHistoryBatch(chunk) }
        if (!pushLocalMissing) return

        val localHistory = historyDao.getAllHistoryOnce()
        val songIdMap = allSongs.associateBy { it.id }
        var histBatch = fs.batch()
        var histBatchCount = 0
        for (history in localHistory) {
            val matchingSong = songIdMap[history.trackId] ?: continue
            val songSignature = SongIdentityHelper.computeSignature(matchingSong)
            if (songSignature.isBlank() || songSignature == "unresolved") continue
            val sessionId = stableHistorySessionId(history, songSignature)
            if (cachedCloudHistoryEvents.containsKey(sessionId)) continue
            val event = CloudPlaybackEvent(
                sessionId = sessionId,
                songSignature = songSignature,
                title = matchingSong.title,
                artist = matchingSong.artist,
                album = matchingSong.album,
                durationMs = matchingSong.durationMs,
                startedAt = history.startedAt,
                endedAt = history.endedAt,
                listenedDurationMs = history.listenedDurationMs,
                sourceContext = history.sourceContext,
                completionPercentage = history.completionPercentage,
                isQualified = history.isQualified,
                createdAt = history.createdAt
            )
            cachedCloudHistoryEvents[sessionId] = event
            histBatch.set(historyRef.document(sessionId), event.toMap(), SetOptions.merge())
            histBatchCount++
            if (histBatchCount >= 450) {
                histBatch.commit().await()
                histBatch = fs.batch()
                histBatchCount = 0
            }
        }
        if (histBatchCount > 0) histBatch.commit().await()
    }

    /**
     * Relinks any `playback_history` rows with `trackId == 0` against local songs, cleans up
     * unresolvable orphan history rows, and authoritatively recomputes `playCount` and `lastPlayed`
     * on `songs` from qualified `playback_history` records so Most Played, Recently Played, and
     * Listening Stats are always 100% accurate and never inflated.
     */
    suspend fun relinkHistoryAndRecomputePlayStats() = withContext(Dispatchers.IO) {
        val allSongs = songDao.getAllSongsOnce()
        if (allSongs.isEmpty()) return@withContext

        val songById = allSongs.associateBy { it.id }
        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val songsByNormKey = allSongs.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, it.path.substringAfterLast('/')).lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }.filterKeys { !it.startsWith("|") }
        val songsByFilename = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }.filterKeys { it.isNotBlank() }
        val songsByCleanTitle = allSongs.groupBy {
            com.example.util.TagCleaner.cleanTitle(it.title, it.path.substringAfterLast('/')).lowercase()
        }.filterKeys { it.isNotBlank() }

        val allHistory = historyDao.getAllHistoryOnce()
        val idsToDelete = mutableListOf<Long>()
        val seenQualifiedSessions = HashSet<String>()

        for (h in allHistory) {
            val cachedEv = cachedCloudHistoryEvents[h.sessionId]
            var effectiveSong = songById[h.trackId]

            if (h.trackId <= 0L) {
                val matched = if (cachedEv != null && cachedEv.songSignature != "unresolved" && cachedEv.title.isNotBlank()) {
                    resolveSongFromCloudItem(
                        signature = cachedEv.songSignature,
                        title = cachedEv.title,
                        artist = cachedEv.artist,
                        filename = "",
                        durationMs = cachedEv.durationMs,
                        songsBySig = songsBySig,
                        songsByNormKey = songsByNormKey,
                        songsByFilename = songsByFilename,
                        songsByCleanTitle = songsByCleanTitle
                    )
                } else null
                if (matched != null) {
                    historyDao.updateTrackIdById(h.id, matched.id)
                    effectiveSong = matched
                } else {
                    idsToDelete += h.id
                    continue
                }
            } else if (effectiveSong == null) {
                idsToDelete += h.id
                continue
            } else if (cachedEv != null && cachedEv.title.isNotBlank() && cachedEv.songSignature != "unresolved") {
                val stillMatches = SongIdentityHelper.matches(
                    song = effectiveSong,
                    cloudSignature = cachedEv.songSignature,
                    cloudTitle = cachedEv.title,
                    cloudArtist = cachedEv.artist,
                    cloudAlbum = cachedEv.album,
                    cloudDurationMs = cachedEv.durationMs,
                    cloudFilename = ""
                )
                if (!stillMatches) {
                    val corrected = resolveSongFromCloudItem(
                        signature = cachedEv.songSignature,
                        title = cachedEv.title,
                        artist = cachedEv.artist,
                        filename = "",
                        durationMs = cachedEv.durationMs,
                        songsBySig = songsBySig,
                        songsByNormKey = songsByNormKey,
                        songsByFilename = songsByFilename,
                        songsByCleanTitle = songsByCleanTitle
                    )
                    if (corrected != null) {
                        historyDao.updateTrackIdById(h.id, corrected.id)
                        effectiveSong = corrected
                    } else {
                        idsToDelete += h.id
                        continue
                    }
                }
            }

            // Purge hijacked history rows whose completion ratio disagrees substantially with the linked song's duration
            val trackDurationMs = effectiveSong.durationMs
            if (trackDurationMs > 0L && h.listenedDurationMs > 0L && h.completionPercentage in 0.02f..0.98f) {
                val expectedPct = (h.listenedDurationMs.toFloat() / trackDurationMs.toFloat()).coerceIn(0f, 1f)
                val expectedDuration = (h.completionPercentage * trackDurationMs).toLong()
                if (kotlin.math.abs(expectedPct - h.completionPercentage) > 0.15f &&
                    kotlin.math.abs(h.listenedDurationMs - expectedDuration) > 10_000L
                ) {
                    idsToDelete += h.id
                    continue
                }
            }

            // Clamp runaway single-session durations caused by legacy idle/paused wall-clock bugs (e.g., overnight paused sessions)
            val maxReasonableSessionMs = if (trackDurationMs > 0L) {
                maxOf(trackDurationMs * 3L, 1_800_000L)
            } else {
                3_600_000L
            }
            if (h.listenedDurationMs > maxReasonableSessionMs) {
                val clampedDuration = if (trackDurationMs > 0L) trackDurationMs.coerceAtLeast(180_000L) else 300_000L
                val clampedEndedAt = h.startedAt + clampedDuration
                try {
                    historyDao.updateHistorySessionMetrics(
                        id = h.id,
                        listenedDurationMs = clampedDuration,
                        endedAt = clampedEndedAt,
                        completionPercentage = 1.0f
                    )
                } catch (_: Exception) {}
            }

            // Deduplicate identical qualified playback events for the same track at the same timestamp
            if (h.isQualified) {
                val dedupeKey = "${effectiveSong.id}:${h.startedAt}"
                if (!seenQualifiedSessions.add(dedupeKey)) {
                    idsToDelete += h.id
                    continue
                }
            }
        }

        if (idsToDelete.isNotEmpty()) {
            try {
                idsToDelete.chunked(400).forEach { chunk ->
                    historyDao.deleteHistoryByIds(chunk)
                }
            } catch (_: Exception) {}
        }
        try {
            historyDao.deleteUnresolvedHistory()
        } catch (_: Exception) {}

        val aggregatedStats = historyDao.getAggregateSongPlayStats()
        try {
            songDao.resetPlayCountsWithoutQualifiedHistory()
        } catch (_: Exception) {}

        if (aggregatedStats.isNotEmpty()) {
            for (stat in aggregatedStats) {
                val song = songById[stat.trackId] ?: continue
                val authoritativePlayCount = stat.pCount
                val authoritativeLastPlayed = if (authoritativePlayCount < song.playCount) {
                    stat.maxStartedAt
                } else {
                    maxOf(song.lastPlayed, stat.maxStartedAt)
                }
                if (authoritativePlayCount != song.playCount || authoritativeLastPlayed != song.lastPlayed) {
                    songDao.updatePlayStats(stat.trackId, authoritativePlayCount, authoritativeLastPlayed)
                }
            }
        }
    }

    private fun writeLocalLyricsFileCache(songId: Long, plainLyrics: String?, syncedLyricsJson: String?, offsetMs: Long = 0L) {
        com.example.util.LrcParser.writeCachedLrcFile(
            context = context,
            songId = songId,
            plainLyrics = plainLyrics,
            syncedLyricsJson = syncedLyricsJson,
            offsetMs = offsetMs
        )
    }

    private suspend fun matchAndApplyCloudLyricsAndOffsetsToRoom(clearMatchedOffsetsFirst: Boolean = false): Set<Long> = withContext(Dispatchers.IO) {
        if (cachedCloudLyrics.isEmpty() && cachedCloudLyricsOffsets.isEmpty()) return@withContext emptySet()
        val allSongs = songDao.getAllSongsOnce()
        if (allSongs.isEmpty()) return@withContext emptySet()

        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val songsByNormKey = allSongs.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, it.path.substringAfterLast('/')).lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }.filterKeys { !it.startsWith("|") }
        val songsByFilename = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }.filterKeys { it.isNotBlank() }
        val songsByCleanTitle = allSongs.groupBy {
            com.example.util.TagCleaner.cleanTitle(it.title, it.path.substringAfterLast('/')).lowercase()
        }.filterKeys { it.isNotBlank() }

        // 1. Merge offsets from both cachedCloudLyricsOffsets and embedded offsets in cachedCloudLyrics
        val combinedOffsetsBySig = LinkedHashMap<String, CloudLyricsOffset>()
        for (offset in cachedCloudLyricsOffsets.values) {
            if (offset.songSignature.isNotBlank()) {
                combinedOffsetsBySig[offset.songSignature] = offset
            }
        }
        for (entry in cachedCloudLyrics.values) {
            if (entry.songSignature.isNotBlank() && (entry.offsetMs != 0L || entry.offsetUpdatedAt > 0L)) {
                val existing = combinedOffsetsBySig[entry.songSignature]
                if (existing == null || entry.offsetUpdatedAt > existing.updatedAt || (existing.offsetMs == 0L && entry.offsetMs != 0L)) {
                    combinedOffsetsBySig[entry.songSignature] = CloudLyricsOffset(
                        songSignature = entry.songSignature,
                        offsetMs = entry.offsetMs,
                        updatedAt = if (entry.offsetUpdatedAt > 0L) entry.offsetUpdatedAt else entry.updatedAt,
                        title = entry.title,
                        artist = entry.artist,
                        album = entry.album,
                        durationMs = entry.durationMs,
                        filename = entry.filename
                    )
                }
            }
        }

        val localOffsets = userPreferences.getAllLyricsSyncOffsets()
        val localOffsetTimestamps = userPreferences.getAllLyricsSyncOffsetUpdatedAt()
        val matchedOffsetSongIds = mutableSetOf<Long>()
        val offsetsBySongId = mutableMapOf<Long, Long>()
        val offsetTimestampsBySongId = mutableMapOf<Long, Long>()

        for (cloudOffset in combinedOffsetsBySig.values) {
            val matchedSong = resolveSongFromCloudItem(
                signature = cloudOffset.songSignature,
                title = cloudOffset.title,
                artist = cloudOffset.artist,
                filename = cloudOffset.filename,
                durationMs = cloudOffset.durationMs,
                songsBySig = songsBySig,
                songsByNormKey = songsByNormKey,
                songsByFilename = songsByFilename,
                songsByCleanTitle = songsByCleanTitle
            ) ?: continue

            val localUpdatedAt = localOffsetTimestamps[matchedSong.id] ?: 0L
            val localOffset = localOffsets[matchedSong.id] ?: 0L
            val shouldApply = clearMatchedOffsetsFirst ||
                localUpdatedAt == 0L ||
                cloudOffset.updatedAt >= localUpdatedAt ||
                (localOffset == 0L && cloudOffset.offsetMs != 0L)

            if (shouldApply) {
                matchedOffsetSongIds += matchedSong.id
                offsetsBySongId[matchedSong.id] = cloudOffset.offsetMs
                offsetTimestampsBySongId[matchedSong.id] = cloudOffset.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            }
        }

        if (clearMatchedOffsetsFirst || offsetsBySongId.isNotEmpty() || matchedOffsetSongIds.isNotEmpty()) {
            userPreferences.applyLyricsSyncOffsets(
                offsetsBySongId,
                replace = clearMatchedOffsetsFirst,
                clearSongIds = if (clearMatchedOffsetsFirst) matchedOffsetSongIds else emptySet(),
                timestampsBySongId = offsetTimestampsBySongId
            )
        }

        // 2. Apply synced and plain lyrics into Room + local LRC file cache
        val writtenSongIds = java.util.Collections.synchronizedSet(HashSet<Long>())
        if (cachedCloudLyrics.isNotEmpty()) {
            val localLyricsBySongId = lyricsDao.getAllLyricsOnce().associateBy { it.songId }
            val resolvedEntries = mutableListOf<Triple<SongEntity, LyricsEntity, Long>>()
            for (entry in cachedCloudLyrics.values) {
                if (entry.plainLyrics.isNullOrBlank() && entry.syncedLyricsJson.isNullOrBlank()) continue
                val song = resolveSongFromCloudItem(
                    signature = entry.songSignature,
                    title = entry.title,
                    artist = entry.artist,
                    filename = entry.filename,
                    durationMs = entry.durationMs,
                    songsBySig = songsBySig,
                    songsByNormKey = songsByNormKey,
                    songsByFilename = songsByFilename,
                    songsByCleanTitle = songsByCleanTitle
                ) ?: continue

                val local = localLyricsBySongId[song.id]
                val cloudHasSynced = !entry.syncedLyricsJson.isNullOrBlank()
                val localHasSynced = !local?.syncedLyricsJson.isNullOrBlank()
                // When restoring from an authoritative backup snapshot (clearMatchedOffsetsFirst = true),
                // always apply the backup's lyrics; otherwise protect user-selected manual lyrics and newer local edits.
                if (!clearMatchedOffsetsFirst && local != null) {
                    val localManualBeatsCloudCached = !local.isCached && entry.isCached && (localHasSynced || !cloudHasSynced)
                    val localNewer = local.updatedAt > entry.updatedAt && (localHasSynced || !cloudHasSynced)
                    if (localManualBeatsCloudCached || localNewer) {
                        continue
                    }
                }
                val mergedPlain = entry.plainLyrics?.takeIf { it.isNotBlank() } ?: local?.plainLyrics
                val mergedSynced = entry.syncedLyricsJson?.takeIf { it.isNotBlank() } ?: local?.syncedLyricsJson
                val entity = LyricsEntity(
                    songId = song.id,
                    plainLyrics = mergedPlain,
                    syncedLyricsJson = mergedSynced,
                    isCached = entry.isCached,
                    source = entry.source,
                    updatedAt = if (entry.updatedAt > 0L) entry.updatedAt else System.currentTimeMillis()
                )
                val effectiveOffsetMs = offsetsBySongId[song.id] ?: localOffsets[song.id] ?: 0L
                lyricsDao.insertLyrics(entity)
                resolvedEntries.add(Triple(song, entity, effectiveOffsetMs))
            }

            if (resolvedEntries.isNotEmpty()) {
                // Clean shared and unique parent directories once before batch writing companion files
                val cleanedDirs = HashSet<String>()
                try {
                    val publicMusic = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                    if (publicMusic != null && cleanedDirs.add(publicMusic.absolutePath)) {
                        com.example.util.LrcParser.cleanupLegacyLrcTxtFiles(context, publicMusic)
                    }
                    val publicLyrics = File(publicMusic, "Lyrics")
                    if (cleanedDirs.add(publicLyrics.absolutePath)) {
                        com.example.util.LrcParser.cleanupLegacyLrcTxtFiles(context, publicLyrics)
                    }
                } catch (_: Exception) {}
                for ((song, _, _) in resolvedEntries) {
                    val parent = File(song.path).parentFile
                    if (parent != null && cleanedDirs.add(parent.absolutePath)) {
                        com.example.util.LrcParser.cleanupLegacyLrcTxtFiles(context, parent)
                    }
                }

                // Write local cache and companion .lrc files concurrently in bounded chunks
                resolvedEntries.chunked(24).forEach { chunk ->
                    coroutineScope {
                        chunk.map { (song, entity, effectiveOffsetMs) ->
                            async(Dispatchers.IO) {
                                writeLocalLyricsFileCache(song.id, entity.plainLyrics, entity.syncedLyricsJson, effectiveOffsetMs)
                                if ((!entity.syncedLyricsJson.isNullOrBlank() || !entity.plainLyrics.isNullOrBlank()) && song.path.isNotBlank()) {
                                    com.example.util.LrcParser.writeCompanionLrcForSong(
                                        context = context,
                                        songPath = song.path,
                                        songTitle = song.title,
                                        plainLyrics = entity.plainLyrics,
                                        syncedLyricsJson = entity.syncedLyricsJson,
                                        offsetMs = effectiveOffsetMs,
                                        skipDirectoryCleanup = true
                                    )
                                }
                                writtenSongIds.add(song.id)
                            }
                        }.awaitAll()
                    }
                }
            }
        }
        writtenSongIds
    }

    /**
     * Synchronizes live CloudLyricsEntry documents bidirectionally between Room/local .lrc files and Firestore.
     */
    private suspend fun syncLiveCloudLyrics(
        uid: String,
        fs: FirebaseFirestore,
        pushLocalMissing: Boolean = true,
        replaceLocal: Boolean = false
    ) {
        val lyricsRef = fs.collection(USERS_COLLECTION).document(uid).collection(LYRICS_COLLECTION)
        val snapshot = lyricsRef.get(Source.SERVER).await()
        val liveEntries = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            if ((data["songSignature"] as? String).isNullOrBlank()) return@mapNotNull null
            CloudLyricsEntry.fromMap(data)
        }
        if (replaceLocal && liveEntries.isNotEmpty()) {
            // Merge live entries over snapshot entries when newer or having synced lyrics
        }
        for (entry in liveEntries) {
            val existing = cachedCloudLyrics[entry.songSignature]
            val entryHasSynced = !entry.syncedLyricsJson.isNullOrBlank()
            val existingHasSynced = !existing?.syncedLyricsJson.isNullOrBlank()
            if (existing == null ||
                (entryHasSynced && !existingHasSynced) ||
                (entryHasSynced == existingHasSynced && entry.updatedAt >= existing.updatedAt)
            ) {
                cachedCloudLyrics[entry.songSignature] = entry
            }
            if (entry.offsetMs != 0L || entry.offsetUpdatedAt > 0L) {
                val existingOffset = cachedCloudLyricsOffsets[entry.songSignature]
                if (existingOffset == null || entry.offsetUpdatedAt > existingOffset.updatedAt || (existingOffset.offsetMs == 0L && entry.offsetMs != 0L)) {
                    cachedCloudLyricsOffsets[entry.songSignature] = CloudLyricsOffset(
                        songSignature = entry.songSignature,
                        offsetMs = entry.offsetMs,
                        updatedAt = if (entry.offsetUpdatedAt > 0L) entry.offsetUpdatedAt else entry.updatedAt,
                        title = entry.title,
                        artist = entry.artist,
                        album = entry.album,
                        durationMs = entry.durationMs,
                        filename = entry.filename
                    )
                }
            }
        }

        matchAndApplyCloudLyricsAndOffsetsToRoom(clearMatchedOffsetsFirst = false)

        if (!pushLocalMissing) return
        val allSongs = songDao.getAllSongsOnce()
        if (allSongs.isEmpty()) return
        val songsById = allSongs.associateBy { it.id }
        val localOffsets = userPreferences.getAllLyricsSyncOffsets()
        val dbLyrics = lyricsDao.getAllLyricsOnce()
        val liveBySig = liveEntries.associateBy { it.songSignature }

        var batch = fs.batch()
        var batchCount = 0
        var batchBytes = 0
        for (lyrics in dbLyrics) {
            if (lyrics.plainLyrics.isNullOrBlank() && lyrics.syncedLyricsJson.isNullOrBlank()) continue
            val song = songsById[lyrics.songId] ?: continue
            val sig = SongIdentityHelper.computeSignature(song)
            if (sig.isBlank()) continue
            val offsetMs = localOffsets[song.id] ?: 0L
            val offsetUpdatedAt = userPreferences.getLyricsSyncOffsetUpdatedAt(song.id)
            val existingCloud = liveBySig[sig]
            val localHasSynced = !lyrics.syncedLyricsJson.isNullOrBlank()
            val cloudHasSynced = !existingCloud?.syncedLyricsJson.isNullOrBlank()
            val shouldPush = existingCloud == null ||
                (localHasSynced && !cloudHasSynced) ||
                lyrics.updatedAt > existingCloud.updatedAt ||
                (offsetUpdatedAt > existingCloud.offsetUpdatedAt && existingCloud.offsetMs != offsetMs)

            if (shouldPush) {
                val item = CloudLyricsEntry(
                    songSignature = sig,
                    plainLyrics = lyrics.plainLyrics,
                    syncedLyricsJson = lyrics.syncedLyricsJson,
                    isCached = lyrics.isCached,
                    source = lyrics.source,
                    updatedAt = lyrics.updatedAt,
                    offsetMs = offsetMs,
                    offsetUpdatedAt = offsetUpdatedAt,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    filename = song.path.substringAfterLast('/')
                )
                val mapData = item.toMap()
                val entryBytes = mapData.toString().toByteArray(Charsets.UTF_8).size
                if (entryBytes > 700 * 1024) continue
                cachedCloudLyrics[sig] = item
                if (batchCount > 0 && (batchCount >= 45 || batchBytes + entryBytes > 450 * 1024)) {
                    batch.commit().await()
                    batch = fs.batch()
                    batchCount = 0
                    batchBytes = 0
                }
                batch.set(lyricsRef.document(sha256(sig)), mapData, SetOptions.merge())
                batchCount++
                batchBytes += entryBytes
            }
        }
        if (batchCount > 0) batch.commit().await()
    }

    /**
     * Synchronizes Lyrics Offsets bidirectionally between local preferences and Firestore.
     */
    private suspend fun syncLyricsOffsets(
        uid: String,
        fs: FirebaseFirestore,
        pushLocalMissing: Boolean = true,
        replaceLocal: Boolean = false
    ) {
        val offsetsRef = fs.collection(USERS_COLLECTION).document(uid).collection(LYRICS_OFFSETS_COLLECTION)
        val snapshot = offsetsRef.get(Source.SERVER).await()
        val cloudOffsets = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            if ((data["songSignature"] as? String).isNullOrBlank() || data["offsetMs"] !is Number) {
                return@mapNotNull null
            }
            CloudLyricsOffset.fromMap(data)
        }
        if (replaceLocal) {
            cachedCloudLyricsOffsets.clear()
        }
        cloudOffsets.forEach { offset ->
            val existing = cachedCloudLyricsOffsets[offset.songSignature]
            if (existing == null || offset.updatedAt >= existing.updatedAt) {
                cachedCloudLyricsOffsets[offset.songSignature] = offset
            }
        }
        matchAndApplyCloudLyricsAndOffsetsToRoom(clearMatchedOffsetsFirst = replaceLocal)

        if (!pushLocalMissing) return
        val allSongs = songDao.getAllSongsOnce()
        val localOffsets = userPreferences.getAllLyricsSyncOffsets()
        val cloudBySig = cloudOffsets.associateBy { it.songSignature }
        var batch = fs.batch()
        var batchCount = 0
        for (song in allSongs) {
            val offsetMs = localOffsets[song.id] ?: 0L
            val localUpdatedAt = userPreferences.getLyricsSyncOffsetUpdatedAt(song.id)
            if (offsetMs == 0L && localUpdatedAt == 0L) continue
            val sig = SongIdentityHelper.computeSignature(song)
            if (sig.isBlank()) continue
            val effectiveUpdatedAt = if (localUpdatedAt > 0L) localUpdatedAt else System.currentTimeMillis()
            val cloudExisting = cloudBySig[sig]
            if (cloudExisting == null || effectiveUpdatedAt > cloudExisting.updatedAt || (cloudExisting.offsetMs != offsetMs && effectiveUpdatedAt >= cloudExisting.updatedAt)) {
                val item = CloudLyricsOffset(
                    songSignature = sig,
                    offsetMs = offsetMs,
                    updatedAt = effectiveUpdatedAt,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    filename = song.path.substringAfterLast('/')
                )
                cachedCloudLyricsOffsets[sig] = item
                batch.set(offsetsRef.document(sha256(sig)), item.toMap(), SetOptions.merge())
                batchCount++
                if (batchCount >= 100) {
                    batch.commit().await()
                    batch = fs.batch()
                    batchCount = 0
                }
            }
        }
        if (batchCount > 0) batch.commit().await()
    }

    private fun sha256(input: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.hashCode().toString()
        }
    }

    private fun firestoreValuesEqual(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a is Number && b is Number) {
            return when {
                a is Double || a is Float || b is Double || b is Float ->
                    kotlin.math.abs(a.toDouble() - b.toDouble()) < 0.0001
                else -> a.toLong() == b.toLong()
            }
        }
        if (a is Map<*, *> && b is Map<*, *>) {
            if (a.size != b.size) return false
            return a.all { (k, v) -> b.containsKey(k) && firestoreValuesEqual(v, b[k]) }
        }
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return false
            return a.indices.all { i -> firestoreValuesEqual(a[i], b[i]) }
        }
        return a == b
    }

    private fun firestoreMapsEqual(a: Map<String, Any?>?, b: Map<String, Any?>): Boolean {
        if (a == null) return false
        if (a.size != b.size) return false
        return a.all { (k, v) -> b.containsKey(k) && firestoreValuesEqual(v, b[k]) }
    }

    private fun cloudLyricsItemsRef(uid: String, snapshotId: String, fs: FirebaseFirestore) =
        fs.collection(USERS_COLLECTION).document(uid)
            .collection("lyrics_snapshots").document(snapshotId).collection("items")

    private suspend fun writeCloudLyricsSnapshot(
        uid: String,
        snapshotId: String,
        entries: List<CloudLyricsEntry>,
        fs: FirebaseFirestore,
        existingLatestSnapshotId: String? = null,
        existingLatestComplete: Boolean = false,
        existingLatestCount: Int = -1
    ) {
        if (entries.isEmpty()) return
        val ref = cloudLyricsItemsRef(uid, snapshotId, fs)
        val expectedIds = entries.filter { it.songSignature.isNotBlank() }.map { sha256(it.songSignature) }.toSet()

        // Fast-path: if latest snapshot already references this identical lyricsSnapshotId,
        // verify completeness with a single read and skip re-uploading all documents.
        if (existingLatestComplete && existingLatestSnapshotId == snapshotId && existingLatestCount == entries.size) {
            val existingDocs = try { ref.get(Source.SERVER).await().documents } catch (_: Exception) { emptyList() }
            val existingIds = existingDocs.map { it.id }.toSet()
            if (existingIds.containsAll(expectedIds) &&
                existingDocs.none { it.id in expectedIds && it.getString("songSignature").isNullOrBlank() }
            ) {
                return
            }
        }

        val batches = mutableListOf<com.google.firebase.firestore.WriteBatch>()
        var batch = fs.batch()
        var count = 0
        var batchBytes = 0
        for (entry in entries) {
            if (entry.songSignature.isBlank()) continue
            val data = entry.toMap()
            val entryBytes = data.toString().toByteArray(Charsets.UTF_8).size
            if (entryBytes > 750 * 1024) {
                Log.w(TAG, "Skipping oversized lyrics record (${entryBytes} bytes) for ${entry.songSignature}")
                continue
            }
            if (count > 0 && (count >= 50 || batchBytes + entryBytes > 500 * 1024)) {
                batches += batch
                batch = fs.batch()
                count = 0
                batchBytes = 0
            }
            batch.set(ref.document(sha256(entry.songSignature)), data)
            count++
            batchBytes += entryBytes
        }
        if (count > 0) batches += batch

        if (batches.isNotEmpty()) {
            batches.chunked(6).forEach { group ->
                coroutineScope { group.map { b -> async { b.commit().await() } }.awaitAll() }
            }
        }

        val verified = ref.get(Source.SERVER).await().documents
        val verifiedIds = verified.map { it.id }.toSet()
        if (!verifiedIds.containsAll(expectedIds) ||
            verified.any { it.id in expectedIds && it.getString("songSignature").isNullOrBlank() }) {
            throw IllegalStateException("Lyrics snapshot could not be verified; the previous cloud snapshot was kept")
        }
    }

    private suspend fun fetchCloudLyrics(
        uid: String,
        payload: UserBackupPayload,
        fs: FirebaseFirestore
    ): List<CloudLyricsEntry> {
        if (payload.version < 3 || !payload.lyricsStoredSeparately || payload.lyricsSnapshotId.isBlank() || payload.lyricsCount <= 0) {
            return emptyList()
        }
        val docs = cloudLyricsItemsRef(uid, payload.lyricsSnapshotId, fs).get(Source.SERVER).await().documents
        if (docs.size < payload.lyricsCount) {
            throw IllegalStateException("Cloud lyrics snapshot is incomplete; local lyrics were left unchanged")
        }
        val entries = docs.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val entry = CloudLyricsEntry.fromMap(data)
            if (entry.songSignature.isBlank() || doc.id != sha256(entry.songSignature) ||
                (entry.plainLyrics.isNullOrBlank() && entry.syncedLyricsJson.isNullOrBlank() && entry.offsetMs == 0L)) {
                return@mapNotNull null
            }
            entry
        }.distinctBy { it.songSignature }
        if (entries.size < payload.lyricsCount) {
            throw IllegalStateException("Cloud lyrics snapshot is incomplete; local lyrics were left unchanged")
        }
        val lyricsHash = sha256(entries.map { it.toMap().toSortedMap() }
            .sortedBy { it["songSignature"].toString() }.toString())
        val legacyLyricsHash = sha256(entries.map { it.toLegacyCoreMap().toSortedMap() }
            .sortedBy { it["songSignature"].toString() }.toString())
        if (lyricsHash != payload.lyricsSnapshotId && legacyLyricsHash != payload.lyricsSnapshotId) {
            Log.w(TAG, "Cloud lyrics snapshot hash evolved across schema versions; verified ${entries.size} entries by document signature")
        }
        return entries
    }

    private fun stableHistorySessionId(history: PlaybackHistoryEntity, songSignature: String): String {
        if (history.sessionId.isNotBlank()) return history.sessionId
        val eventIdentity = listOf(
            songSignature,
            history.startedAt,
            history.endedAt,
            history.listenedDurationMs,
            history.sourceContext,
            history.completionPercentage,
            history.isQualified,
            history.createdAt
        ).joinToString("|")
        return "session_${sha256(eventIdentity)}"
    }

    private fun encodeAvatarForBackup(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(uriString)
            val input = if (uri.scheme == "file") {
                File(uri.path ?: "").takeIf { it.isFile }?.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            } ?: return null
            val bitmap = input.use { BitmapFactory.decodeStream(it) } ?: return null
            try {
                val scaled = if (bitmap.width > 256 || bitmap.height > 256) {
                    val ratio = minOf(256f / bitmap.width, 256f / bitmap.height)
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * ratio).toInt().coerceAtLeast(1),
                        (bitmap.height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                } else bitmap
                try {
                    val output = ByteArrayOutputStream()
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, 86, output)) return null
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not include the profile photo in the snapshot: ${e.message}")
            null
        }
    }

    /**
     * Synchronizes Profile Picture / Avatar:
     * Restores cloud base64 avatar, or falls back to the user's Google/Firebase photoUrl if local avatar is unset,
     * or uploads local custom avatar to Firestore.
     */
    private suspend fun syncProfilePicture(uid: String, fs: FirebaseFirestore) {
        try {
            val userRef = fs.collection(USERS_COLLECTION).document(uid)
            val prefDoc = userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES).get(Source.SERVER).await()
            val localSettings = userPreferences.exportUserSettings()

            val cloudAvatarBase64 = prefDoc.getString("avatarBase64")
            val localAvatarUri = localSettings.customAvatarUri

            val avatarDir = File(context.filesDir, "avatars")
            if (!avatarDir.exists()) avatarDir.mkdirs()
            val avatarFile = File(avatarDir, "user_avatar_$uid.png")

            if (!cloudAvatarBase64.isNullOrBlank()) {
                val shouldWrite = !avatarFile.exists() || localAvatarUri.isNullOrBlank()
                if (shouldWrite) {
                    try {
                        val decodedBytes = Base64.decode(cloudAvatarBase64, Base64.DEFAULT)
                        avatarFile.writeBytes(decodedBytes)
                        val newUri = Uri.fromFile(avatarFile).toString()
                        userPreferences.setCustomAvatarUri(newUri)
                        Log.d(TAG, "Successfully restored avatar image from cloud")
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode cloud avatar base64: ${e.message}")
                    }
                }
            }

            if (!localAvatarUri.isNullOrBlank()) {
                try {
                    val uri = Uri.parse(localAvatarUri)
                    val inputStream = if (uri.scheme == "file") {
                        File(uri.path ?: "").takeIf { it.exists() }?.inputStream()
                    } else {
                        context.contentResolver.openInputStream(uri)
                    }
                    val bitmap = inputStream?.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) {
                        val targetWidth = 256
                        val targetHeight = ((targetWidth.toFloat() / bitmap.width) * bitmap.height).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                        val baos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.PNG, 90, baos)
                        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                        userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES)
                            .set(mapOf("avatarBase64" to base64, "customAvatarUri" to localAvatarUri), SetOptions.merge())
                            .await()
                        Log.d(TAG, "Uploaded local avatar base64 to cloud")
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to upload local avatar to cloud: ${e.message}")
                }
            }

            // Fallback: if user signed in with Google and has a Firebase photoUrl, cache it locally so profile pic works offline too
            val googlePhotoUrl = auth?.currentUser?.photoUrl?.toString()
            if (!googlePhotoUrl.isNullOrBlank() && localAvatarUri.isNullOrBlank()) {
                try {
                    val conn = (URL(googlePhotoUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        doInput = true
                    }
                    if (conn.responseCode in 200..299) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (bytes.isNotEmpty()) {
                            avatarFile.writeBytes(bytes)
                            val fileUri = Uri.fromFile(avatarFile).toString()
                            userPreferences.setCustomAvatarUri(fileUri)
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error synchronizing profile picture: ${e.message}")
        }
    }

    /**
     * Synchronizes Folders (included & excluded).
     */
    private suspend fun syncFolders(uid: String, fs: FirebaseFirestore) {
        val fDao = folderDao ?: return
        try {
            val foldersRef = fs.collection(USERS_COLLECTION).document(uid).collection(FOLDERS_COLLECTION)
            val snapshot = foldersRef.get(Source.SERVER).await()

            val cloudFolders = snapshot.documents.mapNotNull { doc ->
                doc.data?.let { CloudFolderConfig.fromMap(it) }
            }

            val localIncluded = fDao.getIncludedFoldersList()
            val localExcluded = fDao.getExcludedFoldersList()
            val localFolders = localIncluded + localExcluded
            val localPaths = localFolders.map { it.path }.toSet()

            val toInsertLocally = cloudFolders.filter { !localPaths.contains(it.path) }.map {
                FolderEntity(
                    path = it.path,
                    name = it.name,
                    uriString = it.uriString,
                    isExcluded = it.isExcluded,
                    songCount = it.songCount
                )
            }
            if (toInsertLocally.isNotEmpty()) {
                fDao.insertFoldersBatch(toInsertLocally)
            }

            val cloudPaths = cloudFolders.map { it.path }.toSet()
            val toUpload = localFolders.filter { !cloudPaths.contains(it.path) }
            for (f in toUpload) {
                val docId = "folder_${sha256(f.path)}"
                val cloudCfg = CloudFolderConfig(
                    path = f.path,
                    name = f.name,
                    uriString = f.uriString,
                    isExcluded = f.isExcluded,
                    songCount = f.songCount
                )
                foldersRef.document(docId).set(cloudCfg.toMap(), SetOptions.merge())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error synchronizing folders: ${e.message}")
        }
    }

    /**
     * Synchronizes Smart Playlists.
     */
    private suspend fun syncSmartPlaylists(
        uid: String,
        fs: FirebaseFirestore,
        pushLocalMissing: Boolean = true,
        replaceLocal: Boolean = false
    ) {
        val smartRef = fs.collection(USERS_COLLECTION).document(uid).collection(SMART_PLAYLISTS_COLLECTION)
        val snapshot = smartRef.get(Source.SERVER).await()
        val cloudSmart = snapshot.documents.map { doc ->
            val data = doc.data ?: throw IllegalStateException("Cloud smart playlist ${doc.id} is unreadable")
            CloudSmartPlaylist.fromMap(data).also {
                if (it.name.isBlank()) throw IllegalStateException("Cloud smart playlist ${doc.id} has no name")
            }
        }
        val localSmart = smartPlaylistDao.getAllSmartPlaylistsOnce()
        val cloudNames = cloudSmart.map { it.name.trim().lowercase() }.toSet()

        if (replaceLocal) {
            smartPlaylistDao.clearAllSmartPlaylists()
            val restored = cloudSmart.map {
                SmartPlaylistEntity(
                    name = it.name,
                    genreFilter = it.filterType,
                    maxTrackLimit = it.limit,
                    sortCriteria = it.sortOrder.ifBlank { "RECENTLY_ADDED" },
                    createdAt = it.createdAt
                )
            }
            if (restored.isNotEmpty()) smartPlaylistDao.insertSmartPlaylists(restored)
            return
        }

        val localNames = localSmart.map { it.name.trim().lowercase() }.toSet()
        val toInsertLocally = cloudSmart.filter { it.name.trim().lowercase() !in localNames }.map {
            SmartPlaylistEntity(
                name = it.name,
                genreFilter = it.filterType,
                maxTrackLimit = it.limit,
                sortCriteria = it.sortOrder.ifBlank { "RECENTLY_ADDED" },
                createdAt = it.createdAt
            )
        }
        if (toInsertLocally.isNotEmpty()) smartPlaylistDao.insertSmartPlaylists(toInsertLocally)
        if (!pushLocalMissing) return
        for (sp in localSmart.filter { it.name.trim().lowercase() !in cloudNames }) {
            val cloudSp = CloudSmartPlaylist(
                name = sp.name,
                filterType = sp.genreFilter,
                rulesJson = "{}",
                sortOrder = sp.sortCriteria,
                limit = sp.maxTrackLimit,
                createdAt = sp.createdAt
            )
            val docId = "smart_${sp.name.hashCode()}_${sp.createdAt}"
            smartRef.document(docId).set(cloudSp.toMap(), SetOptions.merge()).await()
        }
    }

    /**
     * Synchronizes Recent Search queries.
     */
    private suspend fun syncRecentSearches(
        uid: String,
        fs: FirebaseFirestore,
        pushLocalMissing: Boolean = true,
        replaceLocal: Boolean = false
    ) {
        val sDao = recentSearchDao ?: return
        val searchRef = fs.collection(USERS_COLLECTION).document(uid).collection(SEARCH_COLLECTION)
        val snapshot = searchRef.get(Source.SERVER).await()
        val cloudSearches = snapshot.documents.map { doc ->
            val data = doc.data ?: throw IllegalStateException("Cloud search ${doc.id} is unreadable")
            CloudRecentSearch.fromMap(data).also {
                if (it.query.isBlank()) throw IllegalStateException("Cloud search ${doc.id} has no query")
            }
        }
        if (replaceLocal) {
            sDao.clearAll()
            val restored = cloudSearches.map { RecentSearchEntity(query = it.query, timestamp = it.timestamp) }
            if (restored.isNotEmpty()) sDao.insertSearchesBatch(restored)
            return
        }
        val localSearches = sDao.getAllRecentSearchesOnce()
        val localQueries = localSearches.map { it.query.trim().lowercase() }.toSet()
        val toInsertLocally = cloudSearches.filter { it.query.trim().lowercase() !in localQueries }.map {
            RecentSearchEntity(query = it.query, timestamp = it.timestamp)
        }
        if (toInsertLocally.isNotEmpty()) sDao.insertSearchesBatch(toInsertLocally)
        if (!pushLocalMissing) return
        val cloudQueries = cloudSearches.map { it.query.trim().lowercase() }.toSet()
        for (s in localSearches.filter { it.query.isNotBlank() && it.query.trim().lowercase() !in cloudQueries }) {
            val docId = "search_${s.query.hashCode()}"
            searchRef.document(docId).set(CloudRecentSearch(s.query, s.timestamp).toMap(), SetOptions.merge()).await()
        }
    }

    /**
     * Restores complete account state from latest backup snapshot without clobbering newer local custom playlist ordering.
     */
    internal suspend fun restoreFromBackupSnapshot(
        uid: String,
        data: Map<String, Any?>,
        cloudHistory: List<CloudPlaybackEvent>,
        cloudPlaylistHistory: List<CloudPlaylistPlayEvent>,
        cloudLyrics: List<CloudLyricsEntry>
    ): Set<Long> = withContext(Dispatchers.IO) {
        if (getCurrentUid() != uid) throw CancellationException("Account changed before snapshot restore")
        val payload = UserBackupPayload.fromMap(data)
        val historyById = LinkedHashMap<String, CloudPlaybackEvent>()
        (cloudHistory + payload.historySample).forEach { event ->
            if (event.sessionId.isBlank()) throw IllegalStateException("Snapshot history contains an event without an identity")
            historyById.putIfAbsent(event.sessionId, event)
        }
        if (payload.version >= 2 && historyById.size < payload.historyCount) {
            throw IllegalStateException("Cloud listening history is incomplete; local data was left unchanged")
        }
        if (payload.version >= 2 && cloudPlaylistHistory.size < payload.playlistPlayEventCount) {
            throw IllegalStateException("Cloud playlist history is incomplete; local data was left unchanged")
        }
        Log.d(TAG, "Restoring authoritative snapshot dated ${payload.backupDateFormatted}")

        val allSongs = songDao.getAllSongsOnce()
        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val songsByNormKey = allSongs.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, it.path.substringAfterLast('/')).lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }.filterKeys { !it.startsWith("|") }
        val songsByFilename = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }.filterKeys { it.isNotBlank() }
        val songsByCleanTitle = allSongs.groupBy {
            com.example.util.TagCleaner.cleanTitle(it.title, it.path.substringAfterLast('/')).lowercase()
        }.filterKeys { it.isNotBlank() }

        val localSettings = userPreferences.exportUserSettings()
        val avatarBytes = (payload.profile.avatarBase64 ?: payload.settings.avatarBase64)
            ?.takeIf { it.isNotBlank() }
            ?.let { Base64.decode(it, Base64.DEFAULT) }
        var portableAvatarUri = localSettings.customAvatarUri
        if (portableAvatarUri.isNullOrBlank() && avatarBytes != null) {
            val avatarDir = File(context.filesDir, "avatars").apply { mkdirs() }
            val avatarFile = File(avatarDir, "user_avatar_$uid.png")
            avatarFile.writeBytes(avatarBytes)
            portableAvatarUri = Uri.fromFile(avatarFile).toString()
        }
        if (getCurrentUid() != uid) throw CancellationException("Account changed before applying snapshot")
        val settingsToApply = payload.settings.copy(
            automaticBackupEnabled = localSettings.automaticBackupEnabled,
            backupFrequency = localSettings.backupFrequency,
            customAvatarUri = portableAvatarUri
        )
        userPreferences.importUserSettings(settingsToApply, checkTimestamp = false)
        userPreferences.setCustomDisplayName(payload.profile.customDisplayName)
        userPreferences.setCustomAvatarUri(portableAvatarUri)

        // Apply authoritative snapshot favorites (clear local favorites first so unfavorited songs in backup are updated)
        songDao.clearAllFavorites()
        cachedCloudFavorites.clear()
        payload.favorites.filter { it.isFavorite && it.songSignature.isNotBlank() }.forEach {
            cachedCloudFavorites[it.songSignature] = it
        }
        matchAndApplyCloudFavoritesToRoom()

        try {
            playlistDao.deleteOrphanSongIdsInPlaylists()
        } catch (_: Exception) {}

        val existingPlaylists = playlistDao.getAllPlaylistsOnce()
        val priorCloudIds = playlistLocalToCloudMap.toMap()
        val existingByIdentity = existingPlaylists.associateBy { playlist ->
            priorCloudIds[playlist.id] ?: playlist.cloudId.takeIf { it.isNotBlank() }
                ?: legacyPlaylistCloudId(playlist.name, playlist.createdAt)
        }.toMutableMap()
        val existingByName = existingPlaylists.associateBy { it.name.trim().lowercase() }.toMutableMap()
        val retainedPlaylistIds = HashSet<Long>()
        val retainedCloudIds = HashSet<String>()
        for (cloudPl in payload.playlists) {
            val canonicalCloudId = cloudPl.cloudId.ifBlank { legacyPlaylistCloudId(cloudPl.name, cloudPl.createdAt) }
            val key = cloudPl.name.trim().lowercase()
            val existing = existingByIdentity[canonicalCloudId] ?: existingByName[key]
            // Preserve custom playlist cover image if playlist already exists locally
            val preservedArtwork = existing?.artworkUri?.takeIf { it.isNotBlank() }
                ?: cloudPl.artworkUri?.takeIf { it.isNotBlank() }
            val playlist = PlaylistEntity(
                id = existing?.id ?: 0L,
                name = cloudPl.name,
                createdAt = cloudPl.createdAt,
                isPinned = cloudPl.isPinned,
                artworkUri = preservedArtwork,
                cloudId = canonicalCloudId
            )
            val playlistId = playlistDao.insertPlaylist(playlist)
            retainedPlaylistIds += playlistId
            retainedCloudIds += canonicalCloudId
            playlistLocalToCloudMap[playlistId] = canonicalCloudId
            playlistCloudToLocalMap[canonicalCloudId] = playlistId

            val snapshotSongs = mutableListOf<SongEntity>()
            val seenSnapshotIds = HashSet<Long>()
            for (item in cloudPl.songs.sortedBy { it.position }) {
                val matched = resolveSongFromCloudItem(
                    signature = item.songSignature,
                    title = item.title,
                    artist = item.artist,
                    filename = item.filename,
                    durationMs = item.durationMs,
                    songsBySig = songsBySig,
                    songsByNormKey = songsByNormKey,
                    songsByFilename = songsByFilename,
                    songsByCleanTitle = songsByCleanTitle
                )
                if (matched != null && seenSnapshotIds.add(matched.id)) {
                    snapshotSongs += matched
                }
            }

            val crossRefs = snapshotSongs.mapIndexed { idx, s ->
                PlaylistSongCrossRef(playlistId, s.id, idx)
            }
            playlistDao.updatePlaylistOrder(playlistId, crossRefs)
            setLocalPlaylistUpdatedAt(playlistId, cloudPl.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
        }

        // Delete any local playlists that were not present in the latest cloud backup
        for (localPl in existingPlaylists) {
            if (localPl.id !in retainedPlaylistIds) {
                playlistDao.clearPlaylistSongs(localPl.id)
                playlistDao.deletePlaylist(localPl.id)
                val staleCloudId = playlistLocalToCloudMap.remove(localPl.id) ?: localPl.cloudId
                if (staleCloudId.isNotBlank() && staleCloudId !in retainedCloudIds) {
                    playlistCloudToLocalMap.remove(staleCloudId)
                }
            }
        }

        playlistDao.clearAllPlaylistPlayEvents()
        applyCloudPlaylistPlayEvents(cloudPlaylistHistory)

        // Apply authoritative playback history from the latest cloud backup
        historyDao.clearHistory()
        cachedCloudHistoryEvents.clear()
        historyById.values.forEach { cachedCloudHistoryEvents[it.sessionId] = it }
        val historyToInsert = mutableListOf<PlaybackHistoryEntity>()
        for (event in historyById.values) {
            if (event.songSignature == "unresolved" && event.title.isBlank()) continue
            val matchedSong = resolveSongFromCloudItem(
                signature = event.songSignature,
                title = event.title,
                artist = event.artist,
                filename = "",
                durationMs = event.durationMs,
                songsBySig = songsBySig,
                songsByNormKey = songsByNormKey,
                songsByFilename = songsByFilename,
                songsByCleanTitle = songsByCleanTitle
            )
            if (matchedSong == null && allSongs.isNotEmpty()) continue
            historyToInsert += PlaybackHistoryEntity(
                id = 0L,
                trackId = matchedSong?.id ?: 0L,
                startedAt = event.startedAt,
                endedAt = event.endedAt,
                listenedDurationMs = event.listenedDurationMs,
                sourceContext = event.sourceContext,
                completionPercentage = event.completionPercentage,
                sessionId = event.sessionId,
                isQualified = event.isQualified,
                createdAt = event.createdAt
            )
        }
        historyToInsert.chunked(400).forEach { historyDao.insertPlaybackHistoryBatch(it) }

        if (getCurrentUid() != uid) throw CancellationException("Account changed while applying snapshot")
        smartPlaylistDao.clearAllSmartPlaylists()
        val restoredSmartPlaylists = payload.smartPlaylists.map {
            SmartPlaylistEntity(
                name = it.name,
                genreFilter = it.filterType,
                maxTrackLimit = it.limit,
                sortCriteria = it.sortOrder.ifBlank { "RECENTLY_ADDED" },
                createdAt = it.createdAt
            )
        }
        if (restoredSmartPlaylists.isNotEmpty()) smartPlaylistDao.insertSmartPlaylists(restoredSmartPlaylists)

        recentSearchDao?.let { dao ->
            dao.clearAll()
            val searches = payload.recentSearches.map { RecentSearchEntity(it.query, it.timestamp) }
            if (searches.isNotEmpty()) dao.insertSearchesBatch(searches)
        }

        cachedCloudLyricsOffsets.clear()
        payload.lyricsOffsets.filter { it.songSignature.isNotBlank() }.forEach {
            cachedCloudLyricsOffsets[it.songSignature] = it
        }
        cachedCloudLyrics.clear()
        cloudLyrics.filter { it.songSignature.isNotBlank() }.forEach { entry ->
            cachedCloudLyrics[entry.songSignature] = entry
            if (entry.offsetMs != 0L || entry.offsetUpdatedAt > 0L) {
                val existingOffset = cachedCloudLyricsOffsets[entry.songSignature]
                if (existingOffset == null || entry.offsetUpdatedAt > existingOffset.updatedAt || (existingOffset.offsetMs == 0L && entry.offsetMs != 0L)) {
                    cachedCloudLyricsOffsets[entry.songSignature] = CloudLyricsOffset(
                        songSignature = entry.songSignature,
                        offsetMs = entry.offsetMs,
                        updatedAt = if (entry.offsetUpdatedAt > 0L) entry.offsetUpdatedAt else entry.updatedAt,
                        title = entry.title,
                        artist = entry.artist,
                        album = entry.album,
                        durationMs = entry.durationMs,
                        filename = entry.filename
                    )
                }
            }
        }
        val syncedLyricSongIds = matchAndApplyCloudLyricsAndOffsetsToRoom(clearMatchedOffsetsFirst = true)
        Log.i(TAG, "Restored authoritative snapshot successfully")
        syncedLyricSongIds
    }

    // ==================== REAL-TIME ASYNC EVENT HOOKS ====================

    fun onFavoriteChangedAsync(song: Song, isFavorite: Boolean) {
        val uid = getCurrentUid() ?: return
        val fs = firestore ?: return

        scope.launch {
            try {
                val currentUid = getCurrentUid() ?: return@launch
                if (currentUid != uid) return@launch
                val sig = SongIdentityHelper.computeSignature(song)
                val filename = song.path.substringAfterLast('/')
                val fav = CloudFavoriteSong(
                    songSignature = sig,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    relativeFilename = filename,
                    isFavorite = isFavorite,
                    timestamp = System.currentTimeMillis()
                )
                if (isFavorite) {
                    cachedCloudFavorites[sig] = fav
                } else {
                    cachedCloudFavorites.remove(sig)
                }

                fs.collection(USERS_COLLECTION).document(currentUid)
                    .collection(FAVORITES_COLLECTION).document(sig)
                    .set(fav.toMap(), SetOptions.merge())
            } catch (e: Exception) {
                Log.w(TAG, "Offline/Error syncing favorite: ${e.message}")
            }
        }
    }

    fun onPlaybackSessionRecordedAsync(
        sessionId: String,
        trackId: Long,
        startedAt: Long,
        endedAt: Long,
        listenedDurationMs: Long,
        sourceContext: String,
        completionPercentage: Float,
        isQualified: Boolean,
        song: Song?
    ) {
        val uid = getCurrentUid() ?: return
        val fs = firestore ?: return

        scope.launch {
            try {
                val currentUid = getCurrentUid() ?: return@launch
                if (currentUid != uid) return@launch
                val sig = song?.let { SongIdentityHelper.computeSignature(it) } ?: "unresolved"
                val event = CloudPlaybackEvent(
                    sessionId = sessionId,
                    songSignature = sig,
                    title = song?.title ?: "",
                    artist = song?.artist ?: "",
                    album = song?.album ?: "",
                    durationMs = song?.durationMs ?: 0L,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    listenedDurationMs = listenedDurationMs,
                    sourceContext = sourceContext,
                    completionPercentage = completionPercentage,
                    isQualified = isQualified,
                    createdAt = System.currentTimeMillis()
                )
                cachedCloudHistoryEvents[sessionId] = event
                fs.collection(USERS_COLLECTION).document(currentUid)
                    .collection(HISTORY_COLLECTION).document(sessionId)
                    .set(event.toMap(), SetOptions.merge())
            } catch (e: Exception) {
                Log.w(TAG, "Offline/Error syncing playback session: ${e.message}")
            }
        }
    }

    /**
     * Asynchronously pushes playlist change (including custom song reordering) to Firestore
     * and records the local modification timestamp so custom order persists across app restarts.
     */
    fun onPlaylistUpdatedAsync(playlistId: Long, name: String, isPinned: Boolean, songs: List<Song>) {
        val now = System.currentTimeMillis()
        setLocalPlaylistUpdatedAt(playlistId, now)

        val uid = getCurrentUid() ?: return
        val fs = firestore ?: return

        scope.launch {
            try {
                val currentUid = getCurrentUid() ?: return@launch
                if (currentUid != uid) return@launch
                val localPl = playlistDao.getPlaylistById(playlistId)
                val createdAt = localPl?.createdAt ?: now
                val cloudId = localPl?.let { ensureCloudPlaylistId(it) }
                    ?: playlistLocalToCloudMap[playlistId]
                    ?: "pl_${java.util.UUID.randomUUID()}"
                playlistLocalToCloudMap[playlistId] = cloudId
                playlistCloudToLocalMap[cloudId] = playlistId

                val cloudSongs = songs.mapIndexed { idx, s ->
                    CloudPlaylistSongItem(
                        position = idx,
                        songSignature = SongIdentityHelper.computeSignature(s),
                        title = s.title,
                        artist = s.artist,
                        album = s.album,
                        durationMs = s.durationMs,
                        filename = s.path.substringAfterLast('/')
                    )
                }

                val cloudPl = CloudPlaylist(
                    cloudId = cloudId,
                    name = name,
                    createdAt = createdAt,
                    updatedAt = now,
                    isPinned = isPinned,
                    artworkUri = localPl?.artworkUri?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                    songs = cloudSongs
                )

                fs.collection(USERS_COLLECTION).document(currentUid)
                    .collection(PLAYLISTS_COLLECTION).document(cloudId)
                    .set(cloudPl.toMap(), SetOptions.merge())
            } catch (e: Exception) {
                Log.w(TAG, "Offline/Error syncing playlist: ${e.message}")
            }
        }
    }

    fun onSettingsChangedAsync() {
        val uid = getCurrentUid() ?: return
        val fs = firestore ?: return

        scope.launch {
            try {
                val currentUid = getCurrentUid() ?: return@launch
                if (currentUid != uid) return@launch
                userPreferences.touchSettingsUpdatedAt()
                val current = userPreferences.exportUserSettings()
                fs.collection(USERS_COLLECTION).document(currentUid)
                    .collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES)
                    .set(current.toMap(), SetOptions.merge())
            } catch (e: Exception) {
                Log.w(TAG, "Offline/Error syncing settings: ${e.message}")
            }
        }
    }

    fun onLyricsOffsetChangedAsync(songId: Long, offsetMs: Long) {
        val uid = getCurrentUid() ?: return
        val fs = firestore ?: return

        scope.launch {
            try {
                val currentUid = getCurrentUid() ?: return@launch
                if (currentUid != uid) return@launch
                val song = songDao.getSongById(songId) ?: return@launch
                val sig = SongIdentityHelper.computeSignature(song)
                if (sig.isBlank()) return@launch
                val updatedAt = userPreferences.getLyricsSyncOffsetUpdatedAt(songId).takeIf { it > 0L } ?: System.currentTimeMillis()
                val item = CloudLyricsOffset(
                    songSignature = sig,
                    offsetMs = offsetMs,
                    updatedAt = updatedAt,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    filename = song.path.substringAfterLast('/')
                )
                cachedCloudLyricsOffsets[sig] = item
                fs.collection(USERS_COLLECTION).document(currentUid)
                    .collection(LYRICS_OFFSETS_COLLECTION).document(sha256(sig))
                    .set(item.toMap(), SetOptions.merge())

                // Also update embedded offset in cloud_lyrics if lyrics exist for this song
                val existingLyricsEntry = cachedCloudLyrics[sig]
                if (existingLyricsEntry != null) {
                    val updatedEntry = existingLyricsEntry.copy(
                        offsetMs = offsetMs,
                        offsetUpdatedAt = updatedAt
                    )
                    cachedCloudLyrics[sig] = updatedEntry
                    fs.collection(USERS_COLLECTION).document(currentUid)
                        .collection(LYRICS_COLLECTION).document(sha256(sig))
                        .set(updatedEntry.toMap(), SetOptions.merge())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Offline/Error syncing lyrics offset: ${e.message}")
            }
        }
    }

    fun onLyricsUpdatedAsync(songId: Long) {
        val uid = getCurrentUid() ?: return
        val fs = firestore
        scope.launch {
            try {
                val currentUid = getCurrentUid() ?: return@launch
                if (currentUid != uid) return@launch
                val song = songDao.getSongById(songId) ?: return@launch
                val lyrics = lyricsDao.getLyricsForSong(songId)
                val sig = SongIdentityHelper.computeSignature(song)
                if (sig.isBlank()) return@launch
                if (lyrics != null && (!lyrics.plainLyrics.isNullOrBlank() || !lyrics.syncedLyricsJson.isNullOrBlank())) {
                    val offsetMs = userPreferences.getLyricsSyncOffsetMs(songId)
                    val offsetUpdatedAt = userPreferences.getLyricsSyncOffsetUpdatedAt(songId)
                    val entry = CloudLyricsEntry(
                        songSignature = sig,
                        plainLyrics = lyrics.plainLyrics,
                        syncedLyricsJson = lyrics.syncedLyricsJson,
                        isCached = lyrics.isCached,
                        source = lyrics.source,
                        updatedAt = lyrics.updatedAt,
                        offsetMs = offsetMs,
                        offsetUpdatedAt = offsetUpdatedAt,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        durationMs = song.durationMs,
                        filename = song.path.substringAfterLast('/')
                    )
                    cachedCloudLyrics[sig] = entry
                    if (fs != null) {
                        val mapData = entry.toMap()
                        if (mapData.toString().toByteArray(Charsets.UTF_8).size <= 700 * 1024) {
                            fs.collection(USERS_COLLECTION).document(currentUid)
                                .collection(LYRICS_COLLECTION).document(sha256(sig))
                                .set(mapData, SetOptions.merge())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error syncing updated lyrics to cloud: ${e.message}")
            }
        }
    }

    fun performSyncNow() {
        triggerFullSync(isInitialLogin = false, manualSync = true)
    }

    fun triggerBackupNow() {
        scope.launch {
            performBackupInternal(isAutoBackup = false)
        }
    }

    suspend fun performBackupInternal(isAutoBackup: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val uid = getCurrentUid()
        if (uid.isNullOrBlank()) {
            _backupStatus.value = BackupStatus.Error("Sign in to backup account data")
            return@withContext false
        }
        activeUid = uid
        val fs = firestore
        if (fs == null) {
            _backupStatus.value = BackupStatus.Error("Firestore is unavailable")
            return@withContext false
        }

        try {
            _backupStatus.value = BackupStatus.BackingUp
            _syncStatus.value = SyncStatus.Idle
            SongIdentityHelper.clearCache()

            val currentUser = auth?.currentUser
            if (currentUser?.uid != uid) throw CancellationException("Account changed before backup")

            // Gather all local Room & preference state concurrently before hitting the network
            val (
                currentSettings,
                avatarBase64,
                allSongs,
                localPlaylistEntities,
                playlistSongsById,
                historyRecords,
                playlistPlayEvents,
                topArtists,
                recentSearchesList,
                smartPlaylistsList,
                localLyricsOffsets,
                localOffsetTimestamps,
                dbLyricsList,
                lastHash
            ) = coroutineScope {
                val settingsDef = async(Dispatchers.IO) {
                    val exp = userPreferences.exportUserSettings()
                    val av = encodeAvatarForBackup(exp.customAvatarUri)
                    exp.copy(customAvatarUri = null, avatarBase64 = av) to av
                }
                val songsDef = async(Dispatchers.IO) { songDao.getAllSongsOnce() }
                val playlistsDef = async(Dispatchers.IO) { playlistDao.getAllPlaylistsOnce() }
                val historyDef = async(Dispatchers.IO) { historyDao.getAllHistoryOnce() }
                val plEventsDef = async(Dispatchers.IO) { playlistDao.getAllPlaylistPlayEventsOnce() }
                val topArtistsDef = async(Dispatchers.IO) {
                    historyDao.getTopArtistsBetween(0L, limit = 5).firstOrNull()?.map { it.name } ?: emptyList()
                }
                val searchesDef = async(Dispatchers.IO) {
                    recentSearchDao?.getAllRecentSearchesOnce().orEmpty().map {
                        CloudRecentSearch(query = it.query, timestamp = it.timestamp)
                    }
                }
                val smartDef = async(Dispatchers.IO) {
                    smartPlaylistDao.getAllSmartPlaylistsOnce().map {
                        CloudSmartPlaylist(
                            name = it.name,
                            filterType = it.genreFilter,
                            rulesJson = "{}",
                            sortOrder = it.sortCriteria,
                            limit = it.maxTrackLimit,
                            createdAt = it.createdAt
                        )
                    }
                }
                val offsetsDef = async(Dispatchers.IO) { userPreferences.getAllLyricsSyncOffsets() }
                val offsetTsDef = async(Dispatchers.IO) { userPreferences.getAllLyricsSyncOffsetUpdatedAt() }
                val lyricsDef = async(Dispatchers.IO) { lyricsDao.getAllLyricsOnce() }
                val lastHashDef = async(Dispatchers.IO) { userPreferences.lastBackupDataHash.firstOrNull() }

                val pls = playlistsDef.await()
                val plSongsMap = coroutineScope {
                    pls.associate { p ->
                        p.id to async(Dispatchers.IO) { playlistDao.getPlaylistSongsOnce(p.id) }
                    }.mapValues { it.value.await() }
                }

                val (cfg, av64) = settingsDef.await()
                BackupGatheredData(
                    currentSettings = cfg,
                    avatarBase64 = av64,
                    allSongs = songsDef.await(),
                    localPlaylistEntities = pls,
                    playlistSongsById = plSongsMap,
                    historyRecords = historyDef.await(),
                    playlistPlayEvents = plEventsDef.await(),
                    topArtists = topArtistsDef.await(),
                    recentSearches = searchesDef.await(),
                    smartPlaylists = smartDef.await(),
                    localOffsets = offsetsDef.await(),
                    localOffsetTimestamps = offsetTsDef.await(),
                    dbLyrics = lyricsDef.await(),
                    lastHash = lastHashDef.await()
                )
            }

            val favorites = allSongs.filter { it.isFavorite }.map { song ->
                val sig = SongIdentityHelper.computeSignature(song)
                val filename = song.path.substringAfterLast('/')
                CloudFavoriteSong(
                    songSignature = sig,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    relativeFilename = filename,
                    isFavorite = true,
                    timestamp = if (song.lastPlayed > 0) song.lastPlayed else song.dateAdded
                )
            }.filter { it.songSignature.isNotBlank() }.distinctBy { it.songSignature }

            val localPlaylistCloudIds = mutableMapOf<Long, String>()
            for (playlist in localPlaylistEntities) {
                localPlaylistCloudIds[playlist.id] = ensureCloudPlaylistId(playlist)
            }
            val playlists = localPlaylistEntities.map { p ->
                val songsInPlaylist = playlistSongsById[p.id].orEmpty()
                val cloudSongs = songsInPlaylist.mapIndexed { idx, s ->
                    CloudPlaylistSongItem(
                        position = idx,
                        songSignature = SongIdentityHelper.computeSignature(s),
                        title = s.title,
                        artist = s.artist,
                        album = s.album,
                        durationMs = s.durationMs,
                        filename = s.path.substringAfterLast('/')
                    )
                }
                val localUpdatedAt = getLocalPlaylistUpdatedAt(p.id).takeIf { it > 0L } ?: System.currentTimeMillis()
                CloudPlaylist(
                    cloudId = localPlaylistCloudIds.getValue(p.id),
                    name = p.name,
                    createdAt = p.createdAt,
                    updatedAt = localUpdatedAt,
                    isPinned = p.isPinned,
                    artworkUri = p.artworkUri?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                    songs = cloudSongs
                )
            }.distinctBy { it.cloudId }

            val songIdMap = allSongs.associateBy { it.id }
            val localHistory = historyRecords.mapNotNull { h ->
                val matchingSong = songIdMap[h.trackId] ?: return@mapNotNull null
                val sig = SongIdentityHelper.computeSignature(matchingSong)
                if (sig.isBlank() || sig == "unresolved") return@mapNotNull null
                CloudPlaybackEvent(
                    sessionId = stableHistorySessionId(h, sig),
                    songSignature = sig,
                    title = matchingSong.title,
                    artist = matchingSong.artist,
                    album = matchingSong.album,
                    durationMs = matchingSong.durationMs,
                    startedAt = h.startedAt,
                    endedAt = h.endedAt,
                    listenedDurationMs = h.listenedDurationMs,
                    sourceContext = h.sourceContext,
                    completionPercentage = h.completionPercentage,
                    isQualified = h.isQualified,
                    createdAt = h.startedAt
                )
            }
            val historyById = LinkedHashMap<String, CloudPlaybackEvent>()
            localHistory.forEach { historyById[it.sessionId] = it }
            val fullHistory = historyById.values.toList()
            val historySample = fullHistory.sortedByDescending { it.startedAt }.take(100)

            val playlistHistoryById = LinkedHashMap<String, CloudPlaylistPlayEvent>()
            playlistPlayEvents.forEach { localEvent ->
                val cloudPlaylistId = localPlaylistCloudIds[localEvent.playlistId] ?: return@forEach
                val eventId = sha256("$cloudPlaylistId:${localEvent.timestamp}")
                playlistHistoryById[eventId] = CloudPlaylistPlayEvent(
                    eventId = eventId,
                    playlistCloudId = cloudPlaylistId,
                    timestamp = localEvent.timestamp
                )
            }
            val fullPlaylistHistory = playlistHistoryById.values.toList()

            val totalPlays = fullHistory.count { it.isQualified }.toLong()
            val totalListeningMs = fullHistory.sumOf { it.listenedDurationMs }
            val statsSummary = BackupStatisticsSummary(
                totalPlayCount = totalPlays,
                totalListeningTimeMs = totalListeningMs,
                uniqueTracksPlayed = fullHistory.map { it.songSignature }.distinct().size,
                mostPlayedArtist = topArtists.firstOrNull(),
                topArtistsSummary = topArtists
            )

            val profile = BackupProfileData(
                uid = uid,
                email = currentUser?.email,
                displayName = currentUser?.displayName,
                photoUrl = currentUser?.photoUrl?.toString(),
                customDisplayName = currentSettings.customDisplayName,
                customAvatarUri = null,
                avatarBase64 = avatarBase64
            )

            // Folder URIs and paths are device-specific permissions; they are deliberately not backed up.
            val foldersList = emptyList<CloudFolderConfig>()
            val storyCardData = BackupStoryCardData(
                lastGeneratedPeriod = "ALL_TIME",
                totalMinutesListened = totalListeningMs / (60 * 1000L),
                storyHighlights = topArtists.take(3)
            )

            val now = System.currentTimeMillis()
            val lyricsOffsetsBySig = LinkedHashMap<String, CloudLyricsOffset>()
            for (song in allSongs) {
                val signature = SongIdentityHelper.computeSignature(song)
                if (signature.isBlank()) continue
                val offset = localLyricsOffsets[song.id] ?: 0L
                val updatedAt = localOffsetTimestamps[song.id] ?: 0L
                if (offset == 0L && updatedAt == 0L) continue
                val candidate = CloudLyricsOffset(
                    songSignature = signature,
                    offsetMs = offset,
                    updatedAt = if (updatedAt > 0L) updatedAt else now,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    filename = song.path.substringAfterLast('/')
                )
                val existing = lyricsOffsetsBySig[signature]
                if (existing == null ||
                    (existing.offsetMs == 0L && candidate.offsetMs != 0L) ||
                    candidate.updatedAt > existing.updatedAt
                ) {
                    lyricsOffsetsBySig[signature] = candidate
                }
            }
            val lyricsOffsets = lyricsOffsetsBySig.values.toList()

            val dbLyricsBySongId = dbLyricsList.associateBy { it.songId }
            val cloudLyricsBySig = LinkedHashMap<String, CloudLyricsEntry>()

            for (lyrics in dbLyricsList) {
                val song = songIdMap[lyrics.songId] ?: continue
                val signature = SongIdentityHelper.computeSignature(song)
                if (signature.isBlank()) continue
                if (lyrics.plainLyrics.isNullOrBlank() && lyrics.syncedLyricsJson.isNullOrBlank()) continue
                val songOffset = localLyricsOffsets[song.id] ?: lyricsOffsetsBySig[signature]?.offsetMs ?: 0L
                val songOffsetUpdatedAt = (localOffsetTimestamps[song.id] ?: 0L)
                    .takeIf { it > 0L } ?: (lyricsOffsetsBySig[signature]?.updatedAt ?: 0L)
                val candidate = CloudLyricsEntry(
                    songSignature = signature,
                    plainLyrics = lyrics.plainLyrics,
                    syncedLyricsJson = lyrics.syncedLyricsJson,
                    isCached = lyrics.isCached,
                    source = lyrics.source,
                    updatedAt = lyrics.updatedAt,
                    offsetMs = songOffset,
                    offsetUpdatedAt = songOffsetUpdatedAt,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    filename = song.path.substringAfterLast('/')
                )
                if (candidate.toMap().toString().toByteArray(Charsets.UTF_8).size > 700 * 1024) continue
                val existing = cloudLyricsBySig[signature]
                val candidateHasSynced = !candidate.syncedLyricsJson.isNullOrBlank()
                val existingHasSynced = !existing?.syncedLyricsJson.isNullOrBlank()
                if (existing == null ||
                    (candidateHasSynced && !existingHasSynced) ||
                    (candidateHasSynced == existingHasSynced && candidate.updatedAt >= existing.updatedAt)
                ) {
                    cloudLyricsBySig[signature] = candidate
                }
            }

            // Also include any song that has a local .lrc cache/companion file in its song folder if not yet in Room
            val lyricsCacheDir = context.cacheDir.resolve("lyrics")
            for (song in allSongs) {
                if (dbLyricsBySongId.containsKey(song.id)) continue
                val signature = SongIdentityHelper.computeSignature(song)
                if (signature.isBlank() || cloudLyricsBySig.containsKey(signature)) continue
                val cachedFile = File(lyricsCacheDir, "${song.id}.lrc").takeIf { it.exists() && it.length() in 1..(500 * 1024) }
                    ?: try { com.example.util.LrcParser.findLocalLrcFile(song.path)?.takeIf { it.length() in 1..(500 * 1024) } } catch (_: Exception) { null }
                if (cachedFile != null && cachedFile.exists()) {
                    try {
                        val content = cachedFile.readText(Charsets.UTF_8)
                        val (plain, synced) = com.example.util.LrcParser.parseLrcContent(content)
                        if (!plain.isNullOrBlank() || !synced.isNullOrBlank()) {
                            val songOffset = localLyricsOffsets[song.id] ?: lyricsOffsetsBySig[signature]?.offsetMs ?: 0L
                            val songOffsetUpdatedAt = (localOffsetTimestamps[song.id] ?: 0L)
                                .takeIf { it > 0L } ?: (lyricsOffsetsBySig[signature]?.updatedAt ?: 0L)
                            val entry = CloudLyricsEntry(
                                songSignature = signature,
                                plainLyrics = plain,
                                syncedLyricsJson = synced,
                                isCached = true,
                                source = "local",
                                updatedAt = cachedFile.lastModified().takeIf { it > 0L } ?: now,
                                offsetMs = songOffset,
                                offsetUpdatedAt = songOffsetUpdatedAt,
                                title = song.title,
                                artist = song.artist,
                                album = song.album,
                                durationMs = song.durationMs,
                                filename = song.path.substringAfterLast('/')
                            )
                            if (entry.toMap().toString().toByteArray(Charsets.UTF_8).size <= 700 * 1024) {
                                cloudLyricsBySig[signature] = entry
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            val cloudLyrics = cloudLyricsBySig.values.toList()

            val rawHashSource = listOf(
                currentSettings.toMap().toSortedMap(),
                profile.toMap().toSortedMap(),
                favorites.map { it.toMap().toSortedMap() }.sortedBy { it["songSignature"].toString() },
                playlists.map { it.toMap().toSortedMap() }.sortedBy { it["cloudId"].toString() },
                recentSearchesList.map { it.toMap().toSortedMap() }.sortedBy { it["query"].toString() },
                smartPlaylistsList.map { it.toMap().toSortedMap() }.sortedBy { it["name"].toString() },
                lyricsOffsets.map { it.toMap().toSortedMap() }.sortedBy { it["songSignature"].toString() },
                cloudLyrics.map { it.toMap().toSortedMap() }.sortedBy { it["songSignature"].toString() },
                fullHistory.map { it.toMap().toSortedMap() }.sortedBy { it["sessionId"].toString() },
                fullPlaylistHistory.map { it.toMap().toSortedMap() }.sortedBy { it["eventId"].toString() }
            ).toString()
            val dataHash = sha256(rawHashSource)
            val lyricsSnapshotId = sha256(cloudLyrics.map { it.toMap().toSortedMap() }
                .sortedBy { it["songSignature"].toString() }.toString())

            val backupCollection = fs.collection(USERS_COLLECTION).document(uid).collection("backup")
            val stagingDoc = backupCollection.document("staging")
            val latestDoc = backupCollection.document("latest")

            if (isAutoBackup && lastHash == dataHash) {
                val latest = latestDoc.get(Source.SERVER).await()
                if (latest.exists() && latest.data?.let { UserBackupPayload.isCompleteSnapshot(it, uid) } == true &&
                    latest.getString("dataHash") == dataHash) {
                    Log.d(TAG, "Auto-backup: verified snapshot is unchanged; skipping redundant write")
                    userPreferences.setLastBackupTimestamp(now, dataHash)
                    _lastBackupTimestamp.value = now
                    _backupStatus.value = BackupStatus.Success(now)
                    return@withContext true
                }
            }

            val payload = UserBackupPayload(
                version = 3,
                ownerUid = uid,
                snapshotComplete = true,
                historyStoredSeparately = true,
                backupTimestamp = now,
                dataHash = dataHash,
                profile = profile,
                settings = currentSettings,
                favoritesCount = favorites.size,
                favorites = favorites,
                playlistsCount = playlists.size,
                playlists = playlists,
                foldersCount = foldersList.size,
                folders = foldersList,
                recentSearchesCount = recentSearchesList.size,
                recentSearches = recentSearchesList,
                smartPlaylistsCount = smartPlaylistsList.size,
                smartPlaylists = smartPlaylistsList,
                lyricsOffsetsCount = lyricsOffsets.size,
                lyricsOffsets = lyricsOffsets,
                lyricsStoredSeparately = true,
                lyricsSnapshotId = lyricsSnapshotId,
                lyricsCount = cloudLyrics.size,
                playlistPlayEventCount = fullPlaylistHistory.size,
                playlistPlayEventsStoredSeparately = true,
                historyCount = fullHistory.size,
                historySample = historySample,
                statistics = statsSummary,
                storyCardData = storyCardData
            )

            val payloadMap = payload.toMap()
            if (payloadMap.toString().toByteArray(Charsets.UTF_8).size > 850 * 1024) {
                throw IllegalStateException("Snapshot is too large for Firestore; the previous cloud snapshot was kept")
            }

            if (getCurrentUid() != uid) throw CancellationException("Account changed during backup")

            val userRootRef = fs.collection(USERS_COLLECTION).document(uid)
            val playlistsRef = userRootRef.collection(PLAYLISTS_COLLECTION)
            val favsRef = userRootRef.collection(FAVORITES_COLLECTION)
            val spRef = userRootRef.collection(SMART_PLAYLISTS_COLLECTION)
            val offsetsRef = userRootRef.collection(LYRICS_OFFSETS_COLLECTION)
            val liveLyricsRef = userRootRef.collection(LYRICS_COLLECTION)

            // Fetch all existing cloud collections & latest doc metadata in parallel
            val (
                existingLatestSnap,
                existingCloudHistory,
                existingPlaylistHistory,
                existingCloudPlDocs,
                existingCloudFavDocs,
                existingSpDocs,
                existingOffsetDocs,
                existingLiveLyricsDocs
            ) = coroutineScope {
                val latestDef = async { try { latestDoc.get(Source.SERVER).await() } catch (_: Exception) { null } }
                val histDef = async { fetchCloudHistoryEvents(uid, fs) }
                val plHistDef = async { fetchCloudPlaylistPlayEvents(uid, fs) }
                val plDocsDef = async { try { playlistsRef.get(Source.SERVER).await().documents } catch (_: Exception) { emptyList() } }
                val favDocsDef = async { try { favsRef.get(Source.SERVER).await().documents } catch (_: Exception) { emptyList() } }
                val spDocsDef = async { try { spRef.get(Source.SERVER).await().documents } catch (_: Exception) { emptyList() } }
                val offDocsDef = async { try { offsetsRef.get(Source.SERVER).await().documents } catch (_: Exception) { emptyList() } }
                val lyrDocsDef = async { try { liveLyricsRef.get(Source.SERVER).await().documents } catch (_: Exception) { emptyList() } }
                CloudExistingState(
                    latestSnap = latestDef.await(),
                    history = histDef.await(),
                    playlistHistory = plHistDef.await(),
                    playlistsDocs = plDocsDef.await(),
                    favoritesDocs = favDocsDef.await(),
                    smartPlaylistDocs = spDocsDef.await(),
                    offsetDocs = offDocsDef.await(),
                    liveLyricsDocs = lyrDocsDef.await()
                )
            }

            if (getCurrentUid() != uid) throw CancellationException("Account changed before subcollection backup")

            // Execute all subcollection diffs, writes, pruning, and verifications in parallel
            coroutineScope {
                val historyJob = async {
                    val existingCloudHistoryById = existingCloudHistory.associateBy { it.sessionId }
                    val retainedHistoryIds = fullHistory.map { it.sessionId }.toHashSet()
                    val historyRef = userRootRef.collection(HISTORY_COLLECTION)
                    val historyToWrite = fullHistory.filter { existingCloudHistoryById[it.sessionId]?.toMap() != it.toMap() }
                    val historyToDelete = existingCloudHistory.filter { it.sessionId !in retainedHistoryIds }

                    val batches = mutableListOf<com.google.firebase.firestore.WriteBatch>()
                    var batch = fs.batch()
                    var size = 0
                    for (event in historyToWrite) {
                        batch.set(historyRef.document(event.sessionId), event.toMap(), SetOptions.merge())
                        size++
                        if (size >= 400) {
                            batches += batch; batch = fs.batch(); size = 0
                        }
                    }
                    for (oldEvent in historyToDelete) {
                        batch.delete(historyRef.document(oldEvent.sessionId))
                        size++
                        if (size >= 400) {
                            batches += batch; batch = fs.batch(); size = 0
                        }
                    }
                    if (size > 0) batches += batch
                    if (batches.isNotEmpty()) {
                        batches.chunked(6).forEach { g -> coroutineScope { g.map { b -> async { b.commit().await() } }.awaitAll() } }
                    }
                    cachedCloudHistoryEvents.clear()
                    fullHistory.forEach { cachedCloudHistoryEvents[it.sessionId] = it }

                    if (historyToWrite.isNotEmpty() || historyToDelete.isNotEmpty() || !existingCloudHistoryById.keys.containsAll(retainedHistoryIds)) {
                        val verifiedHistoryIds = fetchCloudHistoryEvents(uid, fs).map { it.sessionId }.toHashSet()
                        if (!verifiedHistoryIds.containsAll(retainedHistoryIds)) {
                            throw IllegalStateException("Listening history could not be fully verified; the previous snapshot was kept")
                        }
                    }
                }

                val playlistHistoryJob = async {
                    val existingPlaylistHistoryById = existingPlaylistHistory.associateBy { it.eventId }
                    val retainedPlHistoryIds = fullPlaylistHistory.map { it.eventId }.toHashSet()
                    val playlistHistoryRef = userRootRef.collection(PLAYLIST_HISTORY_COLLECTION)
                    val toWrite = fullPlaylistHistory.filter { existingPlaylistHistoryById[it.eventId]?.toMap() != it.toMap() }
                    val toDelete = existingPlaylistHistory.filter { it.eventId !in retainedPlHistoryIds }

                    val batches = mutableListOf<com.google.firebase.firestore.WriteBatch>()
                    var batch = fs.batch()
                    var size = 0
                    for (event in toWrite) {
                        batch.set(playlistHistoryRef.document(event.eventId), event.toMap(), SetOptions.merge())
                        size++
                        if (size >= 400) {
                            batches += batch; batch = fs.batch(); size = 0
                        }
                    }
                    for (oldPlEvent in toDelete) {
                        batch.delete(playlistHistoryRef.document(oldPlEvent.eventId))
                        size++
                        if (size >= 400) {
                            batches += batch; batch = fs.batch(); size = 0
                        }
                    }
                    if (size > 0) batches += batch
                    if (batches.isNotEmpty()) {
                        batches.chunked(6).forEach { g -> coroutineScope { g.map { b -> async { b.commit().await() } }.awaitAll() } }
                    }
                    if (toWrite.isNotEmpty() || toDelete.isNotEmpty() || !existingPlaylistHistoryById.keys.containsAll(retainedPlHistoryIds)) {
                        val verifiedIds = fetchCloudPlaylistPlayEvents(uid, fs).map { it.eventId }.toHashSet()
                        if (!verifiedIds.containsAll(retainedPlHistoryIds)) {
                            throw IllegalStateException("Playlist history could not be fully verified; the previous snapshot was kept")
                        }
                    }
                }

                val lyricsSnapshotJob = async {
                    val existingSnapId = existingLatestSnap?.getString("lyricsSnapshotId")
                    val existingSnapComplete = existingLatestSnap?.data?.let { UserBackupPayload.isCompleteSnapshot(it, uid) } == true
                    val existingSnapCount = (existingLatestSnap?.getLong("lyricsCount") ?: -1L).toInt()
                    writeCloudLyricsSnapshot(
                        uid = uid,
                        snapshotId = lyricsSnapshotId,
                        entries = cloudLyrics,
                        fs = fs,
                        existingLatestSnapshotId = existingSnapId,
                        existingLatestComplete = existingSnapComplete,
                        existingLatestCount = existingSnapCount
                    )
                }

                val playlistsJob = async {
                    try {
                        val existingPlById = existingCloudPlDocs.associateBy { it.id }
                        val retainedCloudPlIds = playlists.map { it.cloudId }.toHashSet()
                        val batches = mutableListOf<com.google.firebase.firestore.WriteBatch>()
                        var plBatch = fs.batch()
                        var plBatchSize = 0
                        for (pl in playlists) {
                            val map = pl.toMap()
                            if (!firestoreMapsEqual(existingPlById[pl.cloudId]?.data, map)) {
                                plBatch.set(playlistsRef.document(pl.cloudId), map)
                                plBatchSize++
                                if (plBatchSize >= 200) {
                                    batches += plBatch; plBatch = fs.batch(); plBatchSize = 0
                                }
                            }
                        }
                        for (doc in existingCloudPlDocs) {
                            if (doc.id !in retainedCloudPlIds) {
                                plBatch.delete(doc.reference)
                                plBatchSize++
                                if (plBatchSize >= 200) {
                                    batches += plBatch; plBatch = fs.batch(); plBatchSize = 0
                                }
                            }
                        }
                        if (plBatchSize > 0) batches += plBatch
                        if (batches.isNotEmpty()) {
                            batches.chunked(6).forEach { g -> coroutineScope { g.map { b -> async { b.commit().await() } }.awaitAll() } }
                        }
                        playlistLocalToCloudMap.clear()
                        playlistCloudToLocalMap.clear()
                        for ((localId, cId) in localPlaylistCloudIds) {
                            playlistLocalToCloudMap[localId] = cId
                            playlistCloudToLocalMap[cId] = localId
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Non-fatal error mirroring playlists collection during backup: ${e.message}")
                    }
                }

                val favoritesJob = async {
                    try {
                        val existingFavById = existingCloudFavDocs.associateBy { it.id }
                        val retainedFavSigs = favorites.map { it.songSignature }.toHashSet()
                        val batches = mutableListOf<com.google.firebase.firestore.WriteBatch>()
                        var favBatch = fs.batch()
                        var favBatchSize = 0
                        for (fav in favorites) {
                            val map = fav.toMap()
                            if (!firestoreMapsEqual(existingFavById[fav.songSignature]?.data, map)) {
                                favBatch.set(favsRef.document(fav.songSignature), map)
                                favBatchSize++
                                if (favBatchSize >= 300) {
                                    batches += favBatch; favBatch = fs.batch(); favBatchSize = 0
                                }
                            }
                        }
                        for (doc in existingCloudFavDocs) {
                            if (doc.id !in retainedFavSigs) {
                                favBatch.delete(doc.reference)
                                favBatchSize++
                                if (favBatchSize >= 300) {
                                    batches += favBatch; favBatch = fs.batch(); favBatchSize = 0
                                }
                            }
                        }
                        if (favBatchSize > 0) batches += favBatch
                        if (batches.isNotEmpty()) {
                            batches.chunked(6).forEach { g -> coroutineScope { g.map { b -> async { b.commit().await() } }.awaitAll() } }
                        }
                        cachedCloudFavorites.clear()
                        favorites.forEach { cachedCloudFavorites[it.songSignature] = it }
                    } catch (e: Exception) {
                        Log.w(TAG, "Non-fatal error mirroring favorites collection during backup: ${e.message}")
                    }
                }

                val smartAndSettingsJob = async {
                    try {
                        val existingSpById = existingSpDocs.associateBy { it.id }
                        val retainedSpIds = HashSet<String>()
                        var spBatch = fs.batch()
                        var spBatchSize = 0
                        for (sp in smartPlaylistsList) {
                            val docId = sha256(sp.name.trim().lowercase())
                            retainedSpIds += docId
                            val map = sp.toMap()
                            if (!firestoreMapsEqual(existingSpById[docId]?.data, map)) {
                                spBatch.set(spRef.document(docId), map)
                                spBatchSize++
                            }
                        }
                        for (doc in existingSpDocs) {
                            if (doc.id !in retainedSpIds) {
                                spBatch.delete(doc.reference)
                                spBatchSize++
                            }
                        }
                        if (spBatchSize > 0) spBatch.commit().await()
                        userRootRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES)
                            .set(currentSettings.toMap()).await()
                    } catch (e: Exception) {
                        Log.w(TAG, "Non-fatal error mirroring smart playlists/settings during backup: ${e.message}")
                    }
                }

                val offsetsJob = async {
                    try {
                        val existingOffById = existingOffsetDocs.associateBy { it.id }
                        val retainedOffsetDocIds = lyricsOffsets.map { sha256(it.songSignature) }.toHashSet()
                        val batches = mutableListOf<com.google.firebase.firestore.WriteBatch>()
                        var offsetsBatch = fs.batch()
                        var offsetsBatchSize = 0
                        for (offsetItem in lyricsOffsets) {
                            val docId = sha256(offsetItem.songSignature)
                            val map = offsetItem.toMap()
                            if (!firestoreMapsEqual(existingOffById[docId]?.data, map)) {
                                offsetsBatch.set(offsetsRef.document(docId), map, SetOptions.merge())
                                offsetsBatchSize++
                                if (offsetsBatchSize >= 100) {
                                    batches += offsetsBatch; offsetsBatch = fs.batch(); offsetsBatchSize = 0
                                }
                            }
                        }
                        for (doc in existingOffsetDocs) {
                            if (doc.id !in retainedOffsetDocIds) {
                                offsetsBatch.delete(doc.reference)
                                offsetsBatchSize++
                                if (offsetsBatchSize >= 100) {
                                    batches += offsetsBatch; offsetsBatch = fs.batch(); offsetsBatchSize = 0
                                }
                            }
                        }
                        if (offsetsBatchSize > 0) batches += offsetsBatch
                        if (batches.isNotEmpty()) {
                            batches.chunked(6).forEach { g -> coroutineScope { g.map { b -> async { b.commit().await() } }.awaitAll() } }
                        }
                        cachedCloudLyricsOffsets.clear()
                        lyricsOffsets.forEach { cachedCloudLyricsOffsets[it.songSignature] = it }
                    } catch (e: Exception) {
                        Log.w(TAG, "Non-fatal error mirroring lyrics offsets collection during backup: ${e.message}")
                    }
                }

                val liveLyricsJob = async {
                    try {
                        val existingLyricsById = existingLiveLyricsDocs.associateBy { it.id }
                        val retainedLyricsDocIds = cloudLyrics.map { sha256(it.songSignature) }.toHashSet()
                        val batches = mutableListOf<com.google.firebase.firestore.WriteBatch>()
                        var lyricsBatch = fs.batch()
                        var lyricsBatchCount = 0
                        var lyricsBatchBytes = 0
                        for (entry in cloudLyrics) {
                            val docId = sha256(entry.songSignature)
                            val mapData = entry.toMap()
                            if (firestoreMapsEqual(existingLyricsById[docId]?.data, mapData)) continue
                            val entryBytes = mapData.toString().toByteArray(Charsets.UTF_8).size
                            if (entryBytes > 700 * 1024) continue
                            if (lyricsBatchCount > 0 && (lyricsBatchCount >= 45 || lyricsBatchBytes + entryBytes > 450 * 1024)) {
                                batches += lyricsBatch; lyricsBatch = fs.batch(); lyricsBatchCount = 0; lyricsBatchBytes = 0
                            }
                            lyricsBatch.set(liveLyricsRef.document(docId), mapData, SetOptions.merge())
                            lyricsBatchCount++
                            lyricsBatchBytes += entryBytes
                        }
                        for (doc in existingLiveLyricsDocs) {
                            if (doc.id !in retainedLyricsDocIds) {
                                if (lyricsBatchCount >= 45) {
                                    batches += lyricsBatch; lyricsBatch = fs.batch(); lyricsBatchCount = 0; lyricsBatchBytes = 0
                                }
                                lyricsBatch.delete(doc.reference)
                                lyricsBatchCount++
                            }
                        }
                        if (lyricsBatchCount > 0) batches += lyricsBatch
                        if (batches.isNotEmpty()) {
                            batches.chunked(6).forEach { g -> coroutineScope { g.map { b -> async { b.commit().await() } }.awaitAll() } }
                        }
                        cachedCloudLyrics.clear()
                        cloudLyrics.forEach { cachedCloudLyrics[it.songSignature] = it }
                    } catch (e: Exception) {
                        Log.w(TAG, "Non-fatal error mirroring live cloud_lyrics collection during backup: ${e.message}")
                    }
                }

                awaitAll(
                    historyJob,
                    playlistHistoryJob,
                    lyricsSnapshotJob,
                    playlistsJob,
                    favoritesJob,
                    smartAndSettingsJob,
                    offsetsJob,
                    liveLyricsJob
                )
            }

            if (getCurrentUid() != uid) throw CancellationException("Account changed before snapshot publication")
            stagingDoc.set(payloadMap).await()

            val stagingSnap = stagingDoc.get(Source.SERVER).await()
            if (!stagingSnap.exists() || stagingSnap.data?.let { UserBackupPayload.isCompleteSnapshot(it, uid) } != true ||
                stagingSnap.getString("dataHash") != dataHash) {
                throw IllegalStateException("Failed to verify newly written backup snapshot")
            }

            if (getCurrentUid() != uid) throw CancellationException("Account changed before latest snapshot update")
            latestDoc.set(payloadMap).await()

            val latestSnap = latestDoc.get(Source.SERVER).await()
            if (!latestSnap.exists() || latestSnap.data?.let { UserBackupPayload.isCompleteSnapshot(it, uid) } != true ||
                latestSnap.getString("dataHash") != dataHash) {
                throw IllegalStateException("Failed to verify latest backup document")
            }

            // Cleanup staging/legacy backup docs and update timestamp concurrently
            coroutineScope {
                val cleanupJob = async {
                    try {
                        val allBackups = backupCollection.get(Source.SERVER).await()
                        val docsToDelete = allBackups.documents.filter { it.id != "latest" }
                        if (docsToDelete.isNotEmpty()) {
                            docsToDelete.chunked(400).forEach { chunk ->
                                val delBatch = fs.batch()
                                chunk.forEach { delBatch.delete(it.reference) }
                                delBatch.commit().await()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error pruning previous backup documents: ${e.message}")
                    }
                }
                val rootUpdateJob = async {
                    try {
                        userRootRef.set(mapOf("lastBackupAt" to now), SetOptions.merge()).await()
                    } catch (e: Exception) {
                        Log.w(TAG, "Snapshot succeeded but updating the account timestamp failed: ${e.message}")
                    }
                }
                awaitAll(cleanupJob, rootUpdateJob)
            }

            userPreferences.setLastBackupTimestamp(now, dataHash)
            _lastBackupTimestamp.value = now
            _backupStatus.value = BackupStatus.Success(now)
            Log.i(TAG, "Cloud backup completed successfully at $now")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cloud backup failed: ${e.message}", e)
            _backupStatus.value = BackupStatus.Error(e.localizedMessage ?: "Backup failed")
            false
        }
    }

    private data class BackupGatheredData(
        val currentSettings: CloudUserSettings,
        val avatarBase64: String?,
        val allSongs: List<SongEntity>,
        val localPlaylistEntities: List<PlaylistEntity>,
        val playlistSongsById: Map<Long, List<SongEntity>>,
        val historyRecords: List<PlaybackHistoryEntity>,
        val playlistPlayEvents: List<PlaylistPlayEventEntity>,
        val topArtists: List<String>,
        val recentSearches: List<CloudRecentSearch>,
        val smartPlaylists: List<CloudSmartPlaylist>,
        val localOffsets: Map<Long, Long>,
        val localOffsetTimestamps: Map<Long, Long>,
        val dbLyrics: List<LyricsEntity>,
        val lastHash: String?
    )

    private data class CloudExistingState(
        val latestSnap: com.google.firebase.firestore.DocumentSnapshot?,
        val history: List<CloudPlaybackEvent>,
        val playlistHistory: List<CloudPlaylistPlayEvent>,
        val playlistsDocs: List<com.google.firebase.firestore.DocumentSnapshot>,
        val favoritesDocs: List<com.google.firebase.firestore.DocumentSnapshot>,
        val smartPlaylistDocs: List<com.google.firebase.firestore.DocumentSnapshot>,
        val offsetDocs: List<com.google.firebase.firestore.DocumentSnapshot>,
        val liveLyricsDocs: List<com.google.firebase.firestore.DocumentSnapshot>
    )

    /**
     * Called when LibraryScanner completes a scan.
     * Automatically matches newly discovered songs against cloud favorites, playlists, and listening history.
     */
    private suspend fun refreshPlaylistSongsFromSnapshot(uid: String, data: Map<String, Any?>) = withContext(Dispatchers.IO) {
        if (getCurrentUid() != uid) throw CancellationException("Account changed during playlist refresh")
        if (!UserBackupPayload.isCompleteSnapshot(data, uid)) {
            throw IllegalStateException("Cloud playlist snapshot is incomplete")
        }
        val payload = UserBackupPayload.fromMap(data)
        val allSongs = songDao.getAllSongsOnce()
        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val songsByNormKey = allSongs.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, it.path.substringAfterLast('/')).lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }.filterKeys { !it.startsWith("|") }
        val songsByFilename = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }.filterKeys { it.isNotBlank() }
        val songsByCleanTitle = allSongs.groupBy {
            com.example.util.TagCleaner.cleanTitle(it.title, it.path.substringAfterLast('/')).lowercase()
        }.filterKeys { it.isNotBlank() }
        val localPlaylists = playlistDao.getAllPlaylistsOnce()
        for (cloudPl in payload.playlists) {
            val cloudId = cloudPl.cloudId
            val local = localPlaylists.firstOrNull { it.cloudId == cloudId }
                ?: localPlaylists.firstOrNull { it.name.trim().equals(cloudPl.name.trim(), ignoreCase = true) }
                ?: continue
            if (local.cloudId != cloudId) playlistDao.updatePlaylistCloudId(local.id, cloudId)
            playlistLocalToCloudMap[local.id] = cloudId
            playlistCloudToLocalMap[cloudId] = local.id
            val crossRefs = mutableListOf<PlaylistSongCrossRef>()
            val seenSongIds = HashSet<Long>()
            for (item in cloudPl.songs.sortedBy { it.position }) {
                val matched = resolveSongFromCloudItem(
                    signature = item.songSignature,
                    title = item.title,
                    artist = item.artist,
                    filename = item.filename,
                    durationMs = item.durationMs,
                    songsBySig = songsBySig,
                    songsByNormKey = songsByNormKey,
                    songsByFilename = songsByFilename,
                    songsByCleanTitle = songsByCleanTitle
                )
                if (matched != null && seenSongIds.add(matched.id)) {
                    crossRefs += PlaylistSongCrossRef(local.id, matched.id, crossRefs.size)
                }
            }
            playlistDao.updatePlaylistOrder(local.id, crossRefs)
        }
    }

    private suspend fun ensureLocalCompanionLrcFilesAndCleanupTxt(skipSongIds: Set<Long> = emptySet()) = withContext(Dispatchers.IO) {
        try {
            val allSongs = songDao.getAllSongsOnce()
            val visitedDirs = HashSet<String>()
            try {
                val publicMusic = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                if (publicMusic != null && visitedDirs.add(publicMusic.absolutePath)) {
                    com.example.util.LrcParser.cleanupLegacyLrcTxtFiles(context, publicMusic)
                }
                val publicLyrics = File(publicMusic, "Lyrics")
                if (visitedDirs.add(publicLyrics.absolutePath)) {
                    com.example.util.LrcParser.cleanupLegacyLrcTxtFiles(context, publicLyrics)
                }
            } catch (_: Exception) {}

            for (song in allSongs) {
                val parentDir = File(song.path).parentFile
                if (parentDir != null && visitedDirs.add(parentDir.absolutePath)) {
                    com.example.util.LrcParser.cleanupLegacyLrcTxtFiles(context, parentDir)
                }
            }

            val allLyrics = lyricsDao.getAllLyricsOnce()
            if (allLyrics.isNotEmpty()) {
                val songsById = allSongs.associateBy { it.id }
                val allOffsets = userPreferences.getAllLyricsSyncOffsets()
                val pendingLyrics = allLyrics.filter { lyrics ->
                    lyrics.songId !in skipSongIds &&
                        (!lyrics.syncedLyricsJson.isNullOrBlank() || !lyrics.plainLyrics.isNullOrBlank()) &&
                        songsById[lyrics.songId]?.path?.isNotBlank() == true
                }
                pendingLyrics.chunked(24).forEach { chunk ->
                    coroutineScope {
                        chunk.map { lyrics ->
                            async(Dispatchers.IO) {
                                val song = songsById[lyrics.songId] ?: return@async
                                val offsetMs = allOffsets[song.id] ?: 0L
                                com.example.util.LrcParser.writeCompanionLrcForSong(
                                    context = context,
                                    songPath = song.path,
                                    songTitle = song.title,
                                    plainLyrics = lyrics.plainLyrics,
                                    syncedLyricsJson = lyrics.syncedLyricsJson,
                                    offsetMs = offsetMs,
                                    skipDirectoryCleanup = true
                                )
                            }
                        }.awaitAll()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal error ensuring local companion .lrc files: ${e.message}")
        }
    }

    fun onLibraryScanCompleted() {
        scope.launch {
            try {
                matchAndApplyCloudFavoritesToRoom()
                matchAndApplyCloudLyricsAndOffsetsToRoom()
                relinkHistoryAndRecomputePlayStats()
                ensureLocalCompanionLrcFilesAndCleanupTxt()
            } catch (e: Exception) {
                Log.w(TAG, "Error in post-scan sync hook: ${e.message}")
            }
        }
    }
}
