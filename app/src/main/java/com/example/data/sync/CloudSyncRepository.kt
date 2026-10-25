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
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
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
        private const val LYRICS_OFFSETS_COLLECTION = "lyrics_offsets"

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

    // In-memory map of local playlist ID -> Cloud Playlist ID
    private val playlistLocalToCloudMap = ConcurrentHashMap<Long, String>()
    private val playlistCloudToLocalMap = ConcurrentHashMap<String, Long>()

    // Tracks local playlist modification timestamps so custom user ordering is never overwritten by stale cloud data
    private val playlistMetaPrefs by lazy {
        context.getSharedPreferences("playlist_sync_meta", Context.MODE_PRIVATE)
    }

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

    // Currently authenticated UID being synchronized
    @Volatile
    private var activeUid: String? = null

    private val firestore: FirebaseFirestore?
        get() = injectedFirestore ?: com.example.util.FirebaseInitializer.getFirestore(context)

    private val auth: FirebaseAuth?
        get() = injectedAuth ?: com.example.util.FirebaseInitializer.getAuth(context)

    fun getCurrentUid(): String? {
        return try {
            auth?.currentUser?.uid?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
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
    fun onAuthStateChanged(newUid: String?) {
        scope.launch {
            val authUser = auth?.currentUser
            val authoritativeUid = authUser?.uid?.takeIf { it.isNotBlank() } ?: newUid?.takeIf { it.isNotBlank() }
            val lastUid = userPreferences.getLastActiveUid()

            if (authoritativeUid == null || newUid == null) {
                activeSyncJob?.cancel()
                activeSyncJob = null
                activeUid = null
                cachedCloudFavorites.clear()
                cachedCloudHistoryEvents.clear()
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
                return@launch
            }

            if (!lastUid.isNullOrBlank() && lastUid != authoritativeUid) {
                Log.d(TAG, "Switching accounts from $lastUid to $authoritativeUid. Clearing previous user's local account data.")
                activeSyncJob?.cancel()
                activeSyncJob = null
                cachedCloudFavorites.clear()
                cachedCloudHistoryEvents.clear()
                playlistLocalToCloudMap.clear()
                playlistCloudToLocalMap.clear()
                clearAccountOwnedLocalData()
            }

            val shouldTriggerSync = activeUid != authoritativeUid || activeSyncJob?.isActive != true
            activeUid = authoritativeUid
            userPreferences.setLastActiveUid(authoritativeUid)
            if (shouldTriggerSync) {
                Log.d(TAG, "Cloud sync initialized for user: $authoritativeUid")
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
    fun triggerFullSync(isInitialLogin: Boolean = false) {
        val uid = getCurrentUid() ?: activeUid ?: return
        activeUid = uid
        activeSyncJob?.cancel()
        activeSyncJob = scope.launch {
            try {
                _syncStatus.value = SyncStatus.Syncing
                val fs = firestore
                if (fs == null) {
                    _syncStatus.value = SyncStatus.Error("Firestore unavailable")
                    return@launch
                }

                try {
                    userPreferences.setLastActiveUid(uid)
                } catch (_: Exception) {}

                // Ensure user root document exists
                try {
                    syncUserRootDocument(uid, fs)
                } catch (e: Exception) {
                    Log.w(TAG, "Non-fatal error syncing user root doc: ${e.message}")
                }

                // 1. Check if a latest backup snapshot exists and restore missing items from it
                var restoredBackupSnapshot = false
                try {
                    val latestBackupDoc = fs.collection(USERS_COLLECTION).document(uid)
                        .collection("backup").document("latest").get().await()
                    if (latestBackupDoc.exists() && latestBackupDoc.data != null) {
                        Log.d(TAG, "Found latest backup snapshot for user $uid. Restoring backup snapshot...")
                        restoreFromBackupSnapshot(uid, latestBackupDoc.data!!, isInitialLogin = isInitialLogin)
                        restoredBackupSnapshot = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking/restoring latest backup snapshot: ${e.message}")
                }

                // Check if cloud account has existing data
                val isCloudEmpty = !restoredBackupSnapshot && checkIsCloudAccountEmpty(uid, fs)

                if (isCloudEmpty) {
                    Log.d(TAG, "Cloud account is empty. Performing initial migration from local Room/DataStore...")
                    performInitialMigration(uid, fs)
                } else {
                    Log.d(TAG, "Cloud account has data. Restoring and merging with local Room...")
                    try { syncSettings(uid, fs, forceRestoreIfInitial = isInitialLogin) } catch (e: Exception) { Log.w(TAG, "syncSettings error: ${e.message}") }
                    try { syncProfilePicture(uid, fs) } catch (e: Exception) { Log.w(TAG, "syncProfilePicture error: ${e.message}") }
                    try { syncFolders(uid, fs) } catch (e: Exception) { Log.w(TAG, "syncFolders error: ${e.message}") }
                    try { syncFavorites(uid, fs) } catch (e: Exception) { Log.w(TAG, "syncFavorites error: ${e.message}") }
                    try { syncPlaylists(uid, fs) } catch (e: Exception) { Log.w(TAG, "syncPlaylists error: ${e.message}") }
                    try { syncSmartPlaylists(uid, fs) } catch (e: Exception) { Log.w(TAG, "syncSmartPlaylists error: ${e.message}") }
                    try { syncHistory(uid, fs) } catch (e: Exception) { Log.w(TAG, "syncHistory error: ${e.message}") }
                    try { syncRecentSearches(uid, fs) } catch (e: Exception) { Log.w(TAG, "syncRecentSearches error: ${e.message}") }
                    try { syncLyricsOffsets(uid, fs) } catch (e: Exception) { Log.w(TAG, "syncLyricsOffsets error: ${e.message}") }
                }

                // Always relink any unmatched history records and recompute song playCount & lastPlayed
                // so Most Played, Recently Played, and Listening Stats are immediately accurate.
                try {
                    relinkHistoryAndRecomputePlayStats()
                } catch (e: Exception) {
                    Log.w(TAG, "relinkHistoryAndRecomputePlayStats error: ${e.message}")
                }

                val now = System.currentTimeMillis()
                _syncStatus.value = SyncStatus.Synced(now)
                userPreferences.setLastSyncedTimestamp(now)
                _lastSyncedTimestamp.value = now
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

    private suspend fun checkIsCloudAccountEmpty(uid: String, fs: FirebaseFirestore): Boolean {
        return try {
            val settingsDoc = fs.collection(USERS_COLLECTION).document(uid)
                .collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES).get().await()
            val favoritesSnapshot = fs.collection(USERS_COLLECTION).document(uid)
                .collection(FAVORITES_COLLECTION).limit(1).get().await()
            val historySnapshot = fs.collection(USERS_COLLECTION).document(uid)
                .collection(HISTORY_COLLECTION).limit(1).get().await()
            val playlistsSnapshot = fs.collection(USERS_COLLECTION).document(uid)
                .collection(PLAYLISTS_COLLECTION).limit(1).get().await()

            !settingsDoc.exists() && favoritesSnapshot.isEmpty && historySnapshot.isEmpty && playlistsSnapshot.isEmpty
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Initial Migration:
     * When user signs in to a new empty cloud account, upload existing local data.
     */
    private suspend fun performInitialMigration(uid: String, fs: FirebaseFirestore) {
        val userRef = fs.collection(USERS_COLLECTION).document(uid)

        // 1. Migrate Settings
        try {
            val currentSettings = userPreferences.exportUserSettings()
            userRef.collection(SETTINGS_COLLECTION)
                .document(SETTINGS_DOC_PREFERENCES)
                .set(currentSettings.toMap(), SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "Initial migration settings error: ${e.message}")
        }

        // 2. Migrate Favorites
        val allSongs = songDao.getAllSongsOnce()
        try {
            val favorites = allSongs.filter { it.isFavorite }
            var favBatch = fs.batch()
            var favBatchCount = 0
            for (song in favorites) {
                val sig = SongIdentityHelper.computeSignature(song)
                val filename = song.path.substringAfterLast('/')
                val fav = CloudFavoriteSong(
                    songSignature = sig,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    relativeFilename = filename,
                    isFavorite = true,
                    timestamp = if (song.lastPlayed > 0) song.lastPlayed else song.dateAdded
                )
                cachedCloudFavorites[sig] = fav
                favBatch.set(userRef.collection(FAVORITES_COLLECTION).document(sig), fav.toMap(), SetOptions.merge())
                favBatchCount++
                if (favBatchCount >= 450) {
                    favBatch.commit().await()
                    favBatch = fs.batch()
                    favBatchCount = 0
                }
            }
            if (favBatchCount > 0) {
                favBatch.commit().await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Initial migration favorites error: ${e.message}")
        }

        // 3. Migrate Playlists
        try {
            val playlists = playlistDao.getAllPlaylistsOnce()
            for (p in playlists) {
                val cloudId = "pl_${p.createdAt}_${p.name.hashCode()}"
                playlistLocalToCloudMap[p.id] = cloudId
                playlistCloudToLocalMap[cloudId] = p.id

                val songsInPlaylist = playlistDao.getPlaylistSongsOnce(p.id)
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
                    name = p.name,
                    createdAt = p.createdAt,
                    updatedAt = now,
                    isPinned = p.isPinned,
                    artworkUri = p.artworkUri,
                    songs = cloudSongs
                )
                setLocalPlaylistUpdatedAt(p.id, now)
                userRef.collection(PLAYLISTS_COLLECTION).document(cloudId).set(cloudPl.toMap(), SetOptions.merge()).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Initial migration playlists error: ${e.message}")
        }

        // 4. Migrate Listening History
        try {
            val historyRecords = historyDao.getHistoryBatchOnce(2000)
            val songIdMap = allSongs.associateBy { it.id }
            var histBatch = fs.batch()
            var histBatchCount = 0
            for (h in historyRecords) {
                val matchingSong = songIdMap[h.trackId]
                val sig = matchingSong?.let { SongIdentityHelper.computeSignature(it) } ?: "unknown_track_${h.trackId}"
                val sessionId = if (h.sessionId.isNotBlank()) h.sessionId else "session_${h.id}_${h.startedAt}"
                val cloudEv = CloudPlaybackEvent(
                    sessionId = sessionId,
                    songSignature = sig,
                    title = matchingSong?.title ?: "",
                    artist = matchingSong?.artist ?: "",
                    album = matchingSong?.album ?: "",
                    durationMs = matchingSong?.durationMs ?: 0L,
                    startedAt = h.startedAt,
                    endedAt = h.endedAt,
                    listenedDurationMs = h.listenedDurationMs,
                    sourceContext = h.sourceContext,
                    completionPercentage = h.completionPercentage,
                    isQualified = h.isQualified,
                    createdAt = h.createdAt
                )
                cachedCloudHistoryEvents[sessionId] = cloudEv
                histBatch.set(userRef.collection(HISTORY_COLLECTION).document(sessionId), cloudEv.toMap(), SetOptions.merge())
                histBatchCount++
                if (histBatchCount >= 450) {
                    histBatch.commit().await()
                    histBatch = fs.batch()
                    histBatchCount = 0
                }
            }
            if (histBatchCount > 0) {
                histBatch.commit().await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Initial migration history error: ${e.message}")
        }

        // 5. Migrate Lyrics Offsets
        try {
            for (song in allSongs) {
                val offset = userPreferences.getLyricsSyncOffsetMs(song.id)
                if (offset != 0L) {
                    val sig = SongIdentityHelper.computeSignature(song)
                    val cloudOffset = CloudLyricsOffset(
                        songSignature = sig,
                        offsetMs = offset,
                        updatedAt = System.currentTimeMillis()
                    )
                    userRef.collection(LYRICS_OFFSETS_COLLECTION).document(sig).set(cloudOffset.toMap(), SetOptions.merge())
                }
            }
        } catch (_: Exception) {}

        // 6. Migrate Folders
        try {
            folderDao?.let { fDao ->
                val folders = fDao.getIncludedFoldersList() + fDao.getExcludedFoldersList()
                for (f in folders) {
                    val docId = "folder_${sha256(f.path)}"
                    val cloudCfg = CloudFolderConfig(
                        path = f.path,
                        name = f.name,
                        uriString = f.uriString,
                        isExcluded = f.isExcluded,
                        songCount = f.songCount
                    )
                    userRef.collection(FOLDERS_COLLECTION).document(docId).set(cloudCfg.toMap(), SetOptions.merge())
                }
            }
        } catch (_: Exception) {}

        // 7. Migrate Smart Playlists
        try {
            val smartPlaylists = smartPlaylistDao.getAllSmartPlaylistsOnce()
            for (sp in smartPlaylists) {
                val docId = "smart_${sp.name.hashCode()}_${sp.createdAt}"
                val cloudSp = CloudSmartPlaylist(
                    name = sp.name,
                    filterType = sp.genreFilter,
                    rulesJson = "{}",
                    sortOrder = sp.sortCriteria,
                    limit = sp.maxTrackLimit,
                    createdAt = sp.createdAt
                )
                userRef.collection(SMART_PLAYLISTS_COLLECTION).document(docId).set(cloudSp.toMap(), SetOptions.merge())
            }
        } catch (_: Exception) {}

        // 8. Migrate Recent Searches
        try {
            recentSearchDao?.let { sDao ->
                val searches = sDao.getAllRecentSearchesOnce()
                for (s in searches) {
                    val docId = "search_${s.query.hashCode()}"
                    val cloudSearch = CloudRecentSearch(
                        query = s.query,
                        timestamp = s.timestamp
                    )
                    userRef.collection(SEARCH_COLLECTION).document(docId).set(cloudSearch.toMap(), SetOptions.merge())
                }
            }
        } catch (_: Exception) {}

        // 9. Migrate Profile Picture
        try {
            syncProfilePicture(uid, fs)
        } catch (_: Exception) {}

        Log.d(TAG, "Initial migration to Firestore complete for user $uid")
    }

    /**
     * Synchronizes Settings:
     * Deterministic last-write-wins based on updatedAt timestamp (or forces restore on fresh login).
     */
    private suspend fun syncSettings(uid: String, fs: FirebaseFirestore, forceRestoreIfInitial: Boolean = false) {
        val userRef = fs.collection(USERS_COLLECTION).document(uid)
        val docSnap = userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES).get().await()

        val localSettings = userPreferences.exportUserSettings()
        if (docSnap.exists() && docSnap.data != null) {
            val cloudSettings = CloudUserSettings.fromMap(docSnap.data!!)
            if (forceRestoreIfInitial || localSettings.updatedAt == 0L || cloudSettings.updatedAt >= localSettings.updatedAt) {
                userPreferences.importUserSettings(cloudSettings, checkTimestamp = false)
                Log.d(TAG, "Restored settings from cloud (updatedAt: ${cloudSettings.updatedAt})")
            } else {
                userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES)
                    .set(localSettings.toMap(), SetOptions.merge()).await()
                Log.d(TAG, "Uploaded newer local settings to cloud (updatedAt: ${localSettings.updatedAt})")
            }
        } else {
            userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES)
                .set(localSettings.toMap(), SetOptions.merge()).await()
        }
    }

    /**
     * Synchronizes Favorites:
     * Restores cloud favorites and pushes newly favorited local tracks.
     */
    private suspend fun syncFavorites(uid: String, fs: FirebaseFirestore) {
        val favoritesRef = fs.collection(USERS_COLLECTION).document(uid).collection(FAVORITES_COLLECTION)
        val snapshot = favoritesRef.get().await()

        for (doc in snapshot.documents) {
            val data = doc.data ?: continue
            val cloudFav = CloudFavoriteSong.fromMap(data)
            if (cloudFav.isFavorite && cloudFav.songSignature.isNotBlank()) {
                cachedCloudFavorites[cloudFav.songSignature] = cloudFav
            }
        }

        // Rematch against currently discovered songs in Room
        matchAndApplyCloudFavoritesToRoom()

        // Push any local favorites not yet in cloud using WriteBatch
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
        val favsByNormalizedKey = favsBySig.values.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }
        val favsByTitleOnly = favsBySig.values.groupBy {
            com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase()
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

            val cleanTitle = com.example.util.TagCleaner.cleanTitle(song.title, "").lowercase()
            val cleanArtist = com.example.util.TagCleaner.cleanArtist(song.artist).lowercase()
            val normKey = "$cleanTitle|$cleanArtist"
            if (favsByNormalizedKey[normKey]?.isNotEmpty() == true) {
                matchingIds.add(song.id)
                continue
            }

            val filename = song.path.substringAfterLast('/').lowercase()
            val filenameMatch = favsByFilename[filename]?.firstOrNull {
                Math.abs((song.durationMs - it.durationMs) / 1000L) <= 5L
            }
            if (filenameMatch != null) {
                matchingIds.add(song.id)
                continue
            }

            val titleDurationMatch = favsByTitleOnly[cleanTitle]?.firstOrNull {
                it.durationMs > 0L && Math.abs((song.durationMs - it.durationMs) / 1000L) <= 3L
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
        songsBySig[signature]?.let { return it }

        val cleanTitle = com.example.util.TagCleaner.cleanTitle(title, "").lowercase()
        val cleanArtist = com.example.util.TagCleaner.cleanArtist(artist).lowercase()
        val normKey = "$cleanTitle|$cleanArtist"
        songsByNormKey[normKey]?.firstOrNull()?.let { return it }

        if (filename.isNotBlank()) {
            val fileCandidates = songsByFilename[filename.lowercase()]
            if (!fileCandidates.isNullOrEmpty()) {
                fileCandidates.firstOrNull {
                    durationMs <= 0L || Math.abs((it.durationMs - durationMs) / 1000L) <= 5L
                }?.let { return it }
            }
        }

        if (cleanTitle.isNotBlank()) {
            val titleCandidates = songsByCleanTitle[cleanTitle]
            if (!titleCandidates.isNullOrEmpty()) {
                if (titleCandidates.size == 1) return titleCandidates.first()
                titleCandidates.firstOrNull {
                    durationMs > 0L && Math.abs((it.durationMs - durationMs) / 1000L) <= 4L
                }?.let { return it }
            }
        }
        return null
    }

    /**
     * Synchronizes Playlists:
     * Downloads cloud playlists and reconstructs them locally with stable IDs.
     * Preserves local custom song order when the local playlist has been reordered more recently.
     */
    private suspend fun syncPlaylists(uid: String, fs: FirebaseFirestore) = withContext(Dispatchers.IO) {
        val playlistsRef = fs.collection(USERS_COLLECTION).document(uid).collection(PLAYLISTS_COLLECTION)
        val snapshot = playlistsRef.get().await()
        val allSongs = songDao.getAllSongsOnce()
        val existingPlaylists = playlistDao.getAllPlaylistsOnce()
        val existingByName = existingPlaylists.associateBy { it.name.trim().lowercase() }.toMutableMap()

        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val songsByNormKey = allSongs.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }
        val songsByFilename = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }
        val songsByCleanTitle = allSongs.groupBy { com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase() }

        for (doc in snapshot.documents) {
            val data = doc.data ?: continue
            val cloudPl = CloudPlaylist.fromMap(data)
            if (cloudPl.name.isBlank()) continue

            val key = cloudPl.name.trim().lowercase()
            val localPlaylist = existingByName[key]
            val playlistId = if (localPlaylist != null) {
                localPlaylist.id
            } else {
                val newId = playlistDao.insertPlaylist(
                    PlaylistEntity(
                        name = cloudPl.name,
                        createdAt = if (cloudPl.createdAt > 0L) cloudPl.createdAt else System.currentTimeMillis(),
                        isPinned = cloudPl.isPinned,
                        artworkUri = cloudPl.artworkUri
                    )
                )
                existingByName[key] = PlaylistEntity(
                    id = newId,
                    name = cloudPl.name,
                    createdAt = cloudPl.createdAt,
                    isPinned = cloudPl.isPinned,
                    artworkUri = cloudPl.artworkUri
                )
                newId
            }

            val canonicalCloudId = cloudPl.cloudId.ifBlank { "pl_${cloudPl.createdAt}_${cloudPl.name.hashCode()}" }
            playlistLocalToCloudMap[playlistId] = canonicalCloudId
            playlistCloudToLocalMap[canonicalCloudId] = playlistId

            val localSongCount = playlistDao.getPlaylistSongCount(playlistId)
            val localUpdatedAt = getLocalPlaylistUpdatedAt(playlistId)

            // If local playlist was reordered/updated more recently than the cloud doc and has songs,
            // push local custom order to cloud instead of overwriting it!
            if (localSongCount > 0 && localUpdatedAt > cloudPl.updatedAt && cloudPl.updatedAt > 0L) {
                val localOrderedSongs = playlistDao.getPlaylistSongsOnce(playlistId)
                val updatedCloudSongs = localOrderedSongs.mapIndexed { idx, s ->
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
                    updatedAt = localUpdatedAt,
                    songs = updatedCloudSongs
                )
                playlistsRef.document(canonicalCloudId).set(pushedPl.toMap(), SetOptions.merge())
                continue
            }

            // Otherwise, reconstruct playlist song associations in the exact custom position order from cloud
            val sortedCloudSongs = cloudPl.songs.sortedBy { it.position }
            val crossRefs = mutableListOf<PlaylistSongCrossRef>()
            val seenSongIds = HashSet<Long>()
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
                if (matchedSong != null && seenSongIds.add(matchedSong.id)) {
                    crossRefs.add(PlaylistSongCrossRef(playlistId, matchedSong.id, crossRefs.size))
                }
            }

            if (crossRefs.isNotEmpty() && (localSongCount == 0 || cloudPl.updatedAt >= localUpdatedAt)) {
                playlistDao.updatePlaylistOrder(playlistId, crossRefs)
                if (cloudPl.updatedAt > 0L) {
                    setLocalPlaylistUpdatedAt(playlistId, cloudPl.updatedAt)
                }
            }
        }

        // Push newly created local playlists that aren't in cloud yet
        for (localPl in playlistDao.getAllPlaylistsOnce()) {
            if (!playlistLocalToCloudMap.containsKey(localPl.id)) {
                val cloudId = "pl_${localPl.createdAt}_${localPl.name.hashCode()}"
                playlistLocalToCloudMap[localPl.id] = cloudId
                playlistCloudToLocalMap[cloudId] = localPl.id

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
                    artworkUri = localPl.artworkUri,
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
    private suspend fun syncHistory(uid: String, fs: FirebaseFirestore) {
        val historyRef = fs.collection(USERS_COLLECTION).document(uid).collection(HISTORY_COLLECTION)
        val snapshot = historyRef.limit(2500).get().await()
        val allSongs = songDao.getAllSongsOnce()
        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val songsByNormKey = allSongs.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }
        val songsByCleanTitle = allSongs.groupBy { com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase() }

        val toInsert = mutableListOf<PlaybackHistoryEntity>()
        for (doc in snapshot.documents) {
            val data = doc.data ?: continue
            val ev = CloudPlaybackEvent.fromMap(data)
            if (ev.sessionId.isBlank()) continue
            cachedCloudHistoryEvents[ev.sessionId] = ev

            val localExisting = historyDao.getHistoryBySessionId(ev.sessionId)
            if (localExisting == null) {
                val matchedSong = resolveSongFromCloudItem(
                    signature = ev.songSignature,
                    title = ev.title,
                    artist = ev.artist,
                    filename = "",
                    durationMs = ev.durationMs,
                    songsBySig = songsBySig,
                    songsByNormKey = songsByNormKey,
                    songsByFilename = emptyMap(),
                    songsByCleanTitle = songsByCleanTitle
                )

                val trackId = matchedSong?.id ?: 0L
                toInsert.add(
                    PlaybackHistoryEntity(
                        id = 0L,
                        trackId = trackId,
                        startedAt = ev.startedAt,
                        endedAt = ev.endedAt,
                        listenedDurationMs = ev.listenedDurationMs,
                        sourceContext = ev.sourceContext,
                        completionPercentage = ev.completionPercentage,
                        sessionId = ev.sessionId,
                        isQualified = ev.isQualified,
                        createdAt = ev.createdAt
                    )
                )
            }
        }
        if (toInsert.isNotEmpty()) {
            toInsert.chunked(400).forEach { chunk ->
                historyDao.insertPlaybackHistoryBatch(chunk)
            }
        }

        // Push local history events that aren't yet in Firestore using WriteBatch
        val localHistory = historyDao.getHistoryBatchOnce(500)
        val songIdMap = allSongs.associateBy { it.id }
        var histBatch = fs.batch()
        var histBatchCount = 0
        for (h in localHistory) {
            val sessionId = if (h.sessionId.isNotBlank()) h.sessionId else "session_${h.id}_${h.startedAt}"
            if (cachedCloudHistoryEvents.containsKey(sessionId)) continue

            val matchingSong = songIdMap[h.trackId]
            val sig = matchingSong?.let { SongIdentityHelper.computeSignature(it) } ?: "track_${h.trackId}"
            val cloudEv = CloudPlaybackEvent(
                sessionId = sessionId,
                songSignature = sig,
                title = matchingSong?.title ?: "",
                artist = matchingSong?.artist ?: "",
                album = matchingSong?.album ?: "",
                durationMs = matchingSong?.durationMs ?: 0L,
                startedAt = h.startedAt,
                endedAt = h.endedAt,
                listenedDurationMs = h.listenedDurationMs,
                sourceContext = h.sourceContext,
                completionPercentage = h.completionPercentage,
                isQualified = h.isQualified,
                createdAt = h.createdAt
            )
            cachedCloudHistoryEvents[sessionId] = cloudEv
            histBatch.set(historyRef.document(sessionId), cloudEv.toMap(), SetOptions.merge())
            histBatchCount++
            if (histBatchCount >= 450) {
                histBatch.commit().await()
                histBatch = fs.batch()
                histBatchCount = 0
            }
        }
        if (histBatchCount > 0) {
            histBatch.commit().await()
        }
    }

    /**
     * Relinks any `playback_history` rows with `trackId == 0` against local songs and recomputes
     * `playCount` and `lastPlayed` on `songs` so Most Played, Recently Played, and Listening Stats
     * immediately reflect cloud history after login or library scan.
     */
    suspend fun relinkHistoryAndRecomputePlayStats() = withContext(Dispatchers.IO) {
        val allSongs = songDao.getAllSongsOnce()
        if (allSongs.isEmpty()) return@withContext

        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val songsByNormKey = allSongs.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }
        val songsByCleanTitle = allSongs.groupBy { com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase() }

        val unmatched = historyDao.getUnmatchedHistory()
        for (h in unmatched) {
            val cachedEv = cachedCloudHistoryEvents[h.sessionId]
            if (cachedEv != null) {
                val matched = resolveSongFromCloudItem(
                    signature = cachedEv.songSignature,
                    title = cachedEv.title,
                    artist = cachedEv.artist,
                    filename = "",
                    durationMs = cachedEv.durationMs,
                    songsBySig = songsBySig,
                    songsByNormKey = songsByNormKey,
                    songsByFilename = emptyMap(),
                    songsByCleanTitle = songsByCleanTitle
                )
                if (matched != null) {
                    historyDao.updateTrackIdById(h.id, matched.id)
                }
            }
        }

        val aggregatedStats = historyDao.getAggregateSongPlayStats()
        if (aggregatedStats.isNotEmpty()) {
            val songById = allSongs.associateBy { it.id }
            for (stat in aggregatedStats) {
                val song = songById[stat.trackId] ?: continue
                val mergedPlayCount = maxOf(song.playCount, stat.pCount)
                val mergedLastPlayed = maxOf(song.lastPlayed, stat.maxStartedAt)
                if (mergedPlayCount != song.playCount || mergedLastPlayed != song.lastPlayed) {
                    songDao.updatePlayStats(stat.trackId, mergedPlayCount, mergedLastPlayed)
                }
            }
        }
    }

    /**
     * Synchronizes Lyrics Offsets.
     */
    private suspend fun syncLyricsOffsets(uid: String, fs: FirebaseFirestore) {
        val offsetsRef = fs.collection(USERS_COLLECTION).document(uid).collection(LYRICS_OFFSETS_COLLECTION)
        val snapshot = offsetsRef.get().await()
        val allSongs = songDao.getAllSongsOnce()
        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }

        for (doc in snapshot.documents) {
            val data = doc.data ?: continue
            val cloudOffset = CloudLyricsOffset.fromMap(data)
            if (cloudOffset.songSignature.isBlank() || cloudOffset.offsetMs == 0L) continue

            val matchedSong = songsBySig[cloudOffset.songSignature]
            if (matchedSong != null) {
                userPreferences.setLyricsSyncOffsetMs(matchedSong.id, cloudOffset.offsetMs)
            }
        }
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

    /**
     * Synchronizes Profile Picture / Avatar:
     * Restores cloud base64 avatar, or falls back to the user's Google/Firebase photoUrl if local avatar is unset,
     * or uploads local custom avatar to Firestore.
     */
    private suspend fun syncProfilePicture(uid: String, fs: FirebaseFirestore) {
        try {
            val userRef = fs.collection(USERS_COLLECTION).document(uid)
            val prefDoc = userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES).get().await()
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
            val snapshot = foldersRef.get().await()

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
    private suspend fun syncSmartPlaylists(uid: String, fs: FirebaseFirestore) {
        try {
            val smartRef = fs.collection(USERS_COLLECTION).document(uid).collection(SMART_PLAYLISTS_COLLECTION)
            val snapshot = smartRef.get().await()

            val cloudSmart = snapshot.documents.mapNotNull { doc ->
                doc.data?.let { CloudSmartPlaylist.fromMap(it) }
            }

            val localSmart = smartPlaylistDao.getAllSmartPlaylistsOnce()
            val localNames = localSmart.map { it.name.trim().lowercase() }.toSet()

            val toInsertLocally = cloudSmart.filter { !localNames.contains(it.name.trim().lowercase()) }.map {
                SmartPlaylistEntity(
                    name = it.name,
                    genreFilter = it.filterType,
                    maxTrackLimit = it.limit,
                    sortCriteria = it.sortOrder.ifBlank { "RECENTLY_ADDED" },
                    createdAt = it.createdAt
                )
            }
            if (toInsertLocally.isNotEmpty()) {
                smartPlaylistDao.insertSmartPlaylists(toInsertLocally)
            }

            val cloudNames = cloudSmart.map { it.name.trim().lowercase() }.toSet()
            val toUpload = localSmart.filter { !cloudNames.contains(it.name.trim().lowercase()) }
            for (sp in toUpload) {
                val cloudSp = CloudSmartPlaylist(
                    name = sp.name,
                    filterType = sp.genreFilter,
                    rulesJson = "{}",
                    sortOrder = sp.sortCriteria,
                    limit = sp.maxTrackLimit,
                    createdAt = sp.createdAt
                )
                val docId = "smart_${sp.name.hashCode()}_${sp.createdAt}"
                smartRef.document(docId).set(cloudSp.toMap(), SetOptions.merge())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error synchronizing smart playlists: ${e.message}")
        }
    }

    /**
     * Synchronizes Recent Search queries.
     */
    private suspend fun syncRecentSearches(uid: String, fs: FirebaseFirestore) {
        val sDao = recentSearchDao ?: return
        try {
            val searchRef = fs.collection(USERS_COLLECTION).document(uid).collection(SEARCH_COLLECTION)
            val snapshot = searchRef.get().await()

            val cloudSearches = snapshot.documents.mapNotNull { doc ->
                doc.data?.let { CloudRecentSearch.fromMap(it) }
            }

            val localSearches = sDao.getAllRecentSearchesOnce()
            val localQueries = localSearches.map { it.query.trim().lowercase() }.toSet()

            val toInsertLocally = cloudSearches.filter { it.query.isNotBlank() && !localQueries.contains(it.query.trim().lowercase()) }.map {
                RecentSearchEntity(
                    query = it.query,
                    timestamp = it.timestamp
                )
            }
            if (toInsertLocally.isNotEmpty()) {
                sDao.insertSearchesBatch(toInsertLocally)
            }

            val cloudQueries = cloudSearches.map { it.query.trim().lowercase() }.toSet()
            val toUpload = localSearches.filter { it.query.isNotBlank() && !cloudQueries.contains(it.query.trim().lowercase()) }
            for (s in toUpload) {
                val docId = "search_${s.query.hashCode()}"
                val cloudSearch = CloudRecentSearch(
                    query = s.query,
                    timestamp = s.timestamp
                )
                searchRef.document(docId).set(cloudSearch.toMap(), SetOptions.merge())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error synchronizing recent searches: ${e.message}")
        }
    }

    /**
     * Restores complete account state from latest backup snapshot without clobbering newer local custom playlist ordering.
     */
    private suspend fun restoreFromBackupSnapshot(
        uid: String,
        data: Map<String, Any?>,
        isInitialLogin: Boolean = false
    ) = withContext(Dispatchers.IO) {
        try {
            val payload = UserBackupPayload.fromMap(data)
            Log.d(TAG, "Restoring data from backup snapshot dated ${payload.backupDateFormatted}")

            // 1. Settings & Profile
            if (payload.settings.updatedAt > 0L) {
                userPreferences.importUserSettings(payload.settings, checkTimestamp = !isInitialLogin)
            }
            if (!payload.profile.customDisplayName.isNullOrBlank()) {
                userPreferences.setCustomDisplayName(payload.profile.customDisplayName)
            }
            if (!payload.profile.customAvatarUri.isNullOrBlank()) {
                userPreferences.setCustomAvatarUri(payload.profile.customAvatarUri)
            }

            val allSongs = songDao.getAllSongsOnce()
            val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
            val songsByNormKey = allSongs.groupBy {
                "${com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
            }
            val songsByFilename = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }
            val songsByCleanTitle = allSongs.groupBy { com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase() }

            // 2. Favorites
            for (fav in payload.favorites) {
                if (fav.isFavorite && fav.songSignature.isNotBlank()) {
                    cachedCloudFavorites[fav.songSignature] = fav
                }
            }
            matchAndApplyCloudFavoritesToRoom()

            // 3. Playlists (only populate songs from snapshot if playlist is new/empty or snapshot is newer than local edit)
            val existingPlaylists = playlistDao.getAllPlaylistsOnce()
            val existingByName = existingPlaylists.associateBy { it.name.trim().lowercase() }.toMutableMap()
            for (cloudPl in payload.playlists) {
                if (cloudPl.name.isBlank()) continue
                val key = cloudPl.name.trim().lowercase()
                val localPl = existingByName[key]
                val playlistId = localPl?.id ?: playlistDao.insertPlaylist(
                    PlaylistEntity(
                        name = cloudPl.name,
                        createdAt = if (cloudPl.createdAt > 0L) cloudPl.createdAt else System.currentTimeMillis(),
                        isPinned = cloudPl.isPinned,
                        artworkUri = cloudPl.artworkUri
                    )
                ).also { newId ->
                    existingByName[key] = PlaylistEntity(
                        id = newId,
                        name = cloudPl.name,
                        createdAt = cloudPl.createdAt,
                        isPinned = cloudPl.isPinned,
                        artworkUri = cloudPl.artworkUri
                    )
                }

                val localCount = playlistDao.getPlaylistSongCount(playlistId)
                val localUpdatedAt = getLocalPlaylistUpdatedAt(playlistId)
                if (localCount == 0 || (cloudPl.updatedAt > localUpdatedAt && localUpdatedAt == 0L)) {
                    val sortedSongs = cloudPl.songs.sortedBy { it.position }
                    val crossRefs = mutableListOf<PlaylistSongCrossRef>()
                    val seenSongIds = HashSet<Long>()
                    for (item in sortedSongs) {
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
                        if (matchedSong != null && seenSongIds.add(matchedSong.id)) {
                            crossRefs.add(PlaylistSongCrossRef(playlistId, matchedSong.id, crossRefs.size))
                        }
                    }
                    if (crossRefs.isNotEmpty()) {
                        playlistDao.updatePlaylistOrder(playlistId, crossRefs)
                    }
                }
            }

            // 4. History & Listening Stats from Backup Snapshot
            val historyToInsert = mutableListOf<PlaybackHistoryEntity>()
            for (ev in payload.historySample) {
                if (ev.sessionId.isBlank()) continue
                cachedCloudHistoryEvents[ev.sessionId] = ev
                val existing = historyDao.getHistoryBySessionId(ev.sessionId)
                if (existing == null) {
                    val matchedSong = resolveSongFromCloudItem(
                        signature = ev.songSignature,
                        title = ev.title,
                        artist = ev.artist,
                        filename = "",
                        durationMs = ev.durationMs,
                        songsBySig = songsBySig,
                        songsByNormKey = songsByNormKey,
                        songsByFilename = songsByFilename,
                        songsByCleanTitle = songsByCleanTitle
                    )
                    historyToInsert.add(
                        PlaybackHistoryEntity(
                            id = 0L,
                            trackId = matchedSong?.id ?: 0L,
                            startedAt = ev.startedAt,
                            endedAt = ev.endedAt,
                            listenedDurationMs = ev.listenedDurationMs,
                            sourceContext = ev.sourceContext,
                            completionPercentage = ev.completionPercentage,
                            sessionId = ev.sessionId,
                            isQualified = ev.isQualified,
                            createdAt = ev.createdAt
                        )
                    )
                }
            }
            if (historyToInsert.isNotEmpty()) {
                historyToInsert.chunked(400).forEach { chunk ->
                    historyDao.insertPlaybackHistoryBatch(chunk)
                }
            }

            // 5. Folders
            folderDao?.let { fDao ->
                val localFolders = fDao.getIncludedFoldersList() + fDao.getExcludedFoldersList()
                val localPaths = localFolders.map { it.path }.toSet()
                val toInsert = payload.folders.filter { !localPaths.contains(it.path) }.map {
                    FolderEntity(
                        path = it.path,
                        name = it.name,
                        uriString = it.uriString,
                        isExcluded = it.isExcluded,
                        songCount = it.songCount
                    )
                }
                if (toInsert.isNotEmpty()) {
                    fDao.insertFoldersBatch(toInsert)
                }
            }

            // 6. Smart Playlists
            val localSmart = smartPlaylistDao.getAllSmartPlaylistsOnce()
            val localSmartNames = localSmart.map { it.name.trim().lowercase() }.toSet()
            val smartToInsert = payload.smartPlaylists.filter { !localSmartNames.contains(it.name.trim().lowercase()) }.map {
                SmartPlaylistEntity(
                    name = it.name,
                    genreFilter = it.filterType,
                    maxTrackLimit = it.limit,
                    sortCriteria = it.sortOrder.ifBlank { "RECENTLY_ADDED" },
                    createdAt = it.createdAt
                )
            }
            if (smartToInsert.isNotEmpty()) {
                smartPlaylistDao.insertSmartPlaylists(smartToInsert)
            }

            // 7. Recent Searches
            recentSearchDao?.let { sDao ->
                val localSearches = sDao.getAllRecentSearchesOnce().map { it.query.trim().lowercase() }.toSet()
                val searchesToInsert = payload.recentSearches.filter { !localSearches.contains(it.query.trim().lowercase()) }.map {
                    RecentSearchEntity(query = it.query, timestamp = it.timestamp)
                }
                if (searchesToInsert.isNotEmpty()) {
                    sDao.insertSearchesBatch(searchesToInsert)
                }
            }

            Log.i(TAG, "Restored snapshot successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring from backup snapshot: ${e.message}", e)
        }
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
                val sig = song?.let { SongIdentityHelper.computeSignature(it) } ?: "track_$trackId"
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
                val cloudId = playlistLocalToCloudMap[playlistId] ?: "pl_${createdAt}_${name.hashCode()}"
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
                    artworkUri = localPl?.artworkUri,
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

    fun performSyncNow() {
        triggerFullSync(isInitialLogin = false)
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

            val currentUser = auth?.currentUser
            val currentSettings = userPreferences.exportUserSettings()
            val allSongs = songDao.getAllSongsOnce()
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
            }

            val playlists = playlistDao.getAllPlaylistsOnce().map { p ->
                val songsInPlaylist = playlistDao.getPlaylistSongsOnce(p.id)
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
                    cloudId = "pl_${p.createdAt}_${p.name.hashCode()}",
                    name = p.name,
                    createdAt = p.createdAt,
                    updatedAt = localUpdatedAt,
                    isPinned = p.isPinned,
                    artworkUri = p.artworkUri,
                    songs = cloudSongs
                )
            }

            val historyRecords = historyDao.getHistoryBatchOnce(500)
            val songIdMap = allSongs.associateBy { it.id }
            val historySample = historyRecords.map { h ->
                val matchingSong = songIdMap[h.trackId]
                val sig = matchingSong?.let { SongIdentityHelper.computeSignature(it) } ?: "track_${h.trackId}"
                CloudPlaybackEvent(
                    sessionId = if (h.sessionId.isNotBlank()) h.sessionId else "session_${h.id}_${h.startedAt}",
                    songSignature = sig,
                    title = matchingSong?.title ?: "",
                    artist = matchingSong?.artist ?: "",
                    album = matchingSong?.album ?: "",
                    durationMs = matchingSong?.durationMs ?: 0L,
                    startedAt = h.startedAt,
                    endedAt = h.endedAt,
                    listenedDurationMs = h.listenedDurationMs,
                    sourceContext = h.sourceContext,
                    completionPercentage = h.completionPercentage,
                    isQualified = h.isQualified,
                    createdAt = h.startedAt
                )
            }

            val totalPlays = historyDao.getHistoryCountOnce().toLong()
            val totalListeningMs = historyRecords.sumOf { it.listenedDurationMs }
            val topArtists = historyDao.getTopArtistsBetween(0L, limit = 5).firstOrNull()?.map { it.name } ?: emptyList()
            val statsSummary = BackupStatisticsSummary(
                totalPlayCount = totalPlays,
                totalListeningTimeMs = totalListeningMs,
                uniqueTracksPlayed = historyRecords.map { it.trackId }.distinct().size,
                mostPlayedArtist = topArtists.firstOrNull(),
                topArtistsSummary = topArtists
            )

            val profile = BackupProfileData(
                uid = uid,
                email = currentUser?.email,
                displayName = currentUser?.displayName,
                photoUrl = currentUser?.photoUrl?.toString(),
                customDisplayName = currentSettings.customDisplayName,
                customAvatarUri = currentSettings.customAvatarUri
            )

            val foldersList = (folderDao?.getIncludedFoldersList().orEmpty() + folderDao?.getExcludedFoldersList().orEmpty()).map {
                CloudFolderConfig(
                    path = it.path,
                    name = it.name,
                    uriString = it.uriString,
                    isExcluded = it.isExcluded,
                    songCount = it.songCount
                )
            }
            val recentSearchesList = recentSearchDao?.getAllRecentSearchesOnce().orEmpty().map {
                CloudRecentSearch(
                    query = it.query,
                    timestamp = it.timestamp
                )
            }
            val smartPlaylistsList = smartPlaylistDao.getAllSmartPlaylistsOnce().map {
                CloudSmartPlaylist(
                    name = it.name,
                    filterType = it.genreFilter,
                    rulesJson = "{}",
                    sortOrder = it.sortCriteria,
                    limit = it.maxTrackLimit,
                    createdAt = it.createdAt
                )
            }

            val storyCardData = BackupStoryCardData(
                lastGeneratedPeriod = "ALL_TIME",
                totalMinutesListened = totalListeningMs / (60 * 1000L),
                storyHighlights = topArtists.take(3)
            )

            val playlistOrderFingerprint = playlists.joinToString("|") { pl ->
                "${pl.name}:${pl.songs.joinToString(",") { it.songSignature }}"
            }.hashCode()
            val rawHashSource = "${currentSettings.updatedAt}_${favorites.size}_${playlists.size}_${playlistOrderFingerprint}_${foldersList.size}_${recentSearchesList.size}_${smartPlaylistsList.size}_${totalPlays}_${profile.customDisplayName}_${profile.customAvatarUri}"
            val dataHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(rawHashSource.toByteArray())
                .joinToString("") { "%02x".format(it) }

            val lastHash = userPreferences.lastBackupDataHash.firstOrNull()
            val now = System.currentTimeMillis()
            if (isAutoBackup && lastHash == dataHash) {
                Log.d(TAG, "Auto-backup: No account data changes detected (hash match). Skipping redundant write.")
                userPreferences.setLastBackupTimestamp(now, dataHash)
                _lastBackupTimestamp.value = now
                _backupStatus.value = BackupStatus.Success(now)
                return@withContext true
            }

            val payload = UserBackupPayload(
                version = 1,
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
                historyCount = historyRecords.size,
                historySample = historySample,
                statistics = statsSummary,
                storyCardData = storyCardData
            )

            val backupCollection = fs.collection(USERS_COLLECTION).document(uid).collection("backup")
            val stagingDoc = backupCollection.document("staging")
            val latestDoc = backupCollection.document("latest")

            stagingDoc.set(payload.toMap()).await()

            val stagingSnap = stagingDoc.get().await()
            if (!stagingSnap.exists() || stagingSnap.getString("dataHash") != dataHash) {
                throw IllegalStateException("Failed to verify newly written backup snapshot")
            }

            latestDoc.set(payload.toMap()).await()

            val latestSnap = latestDoc.get().await()
            if (!latestSnap.exists()) {
                throw IllegalStateException("Failed to verify latest backup document")
            }

            try {
                stagingDoc.delete().await()
            } catch (_: Exception) {}

            try {
                val allBackups = backupCollection.get().await()
                for (doc in allBackups.documents) {
                    if (doc.id != "latest") {
                        doc.reference.delete().await()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error pruning previous backup documents: ${e.message}")
            }

            fs.collection(USERS_COLLECTION).document(uid)
                .set(mapOf("lastBackupAt" to now), SetOptions.merge()).await()

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

    /**
     * Called when LibraryScanner completes a scan.
     * Automatically matches newly discovered songs against cloud favorites, playlists, and listening history.
     */
    fun onLibraryScanCompleted() {
        scope.launch {
            try {
                matchAndApplyCloudFavoritesToRoom()
                val uid = getCurrentUid()
                val fs = firestore
                if (uid != null && fs != null) {
                    syncPlaylists(uid, fs)
                }
                relinkHistoryAndRecomputePlayStats()
            } catch (e: Exception) {
                Log.w(TAG, "Error in post-scan sync hook: ${e.message}")
            }
        }
    }
}