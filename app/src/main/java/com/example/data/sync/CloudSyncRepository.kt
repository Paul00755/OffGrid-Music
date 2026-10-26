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
        scope.launch { prepareAuthState(newUid) }
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

                val backupCollection = fs.collection(USERS_COLLECTION).document(uid).collection("backup")
                val latestBackupDoc = backupCollection.document("latest").get(Source.SERVER).await()
                val latestBackupData = latestBackupDoc.data
                if (latestBackupDoc.exists() && latestBackupData != null) {
                    if (!UserBackupPayload.isCompleteSnapshot(latestBackupData, uid)) {
                        throw IllegalStateException("Cloud backup snapshot is incomplete or belongs to another account")
                    }
                    if (getCurrentUid() != uid) throw CancellationException("Account changed during cloud sync")
                    val cloudHistory = fetchCloudHistoryEvents(uid, fs)
                    val cloudPlaylistHistory = fetchCloudPlaylistPlayEvents(uid, fs)
                    val payload = UserBackupPayload.fromMap(latestBackupData)
                    if (payload.version >= 2 && cloudHistory.map { it.sessionId }.distinct().size < payload.historyCount) {
                        throw IllegalStateException("Cloud listening history is incomplete; local data was left unchanged")
                    }
                    if (payload.version >= 2 && cloudPlaylistHistory.size < payload.playlistPlayEventCount) {
                        throw IllegalStateException("Cloud playlist history is incomplete; local data was left unchanged")
                    }
                    val cloudLyrics = if (payload.version >= 3) fetchCloudLyrics(uid, payload, fs) else emptyList()
                    Log.d(TAG, "Restoring authoritative backup snapshot for user $uid")
                    restoreFromBackupSnapshot(
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
                            _syncStatus.value = SyncStatus.Error("No cloud snapshot exists yet; local data was left unchanged")
                            return@launch
                        }
                        Log.d(TAG, "Cloud account is empty. Performing initial migration from local Room/DataStore...")
                        performInitialMigration(uid, fs)
                    } else {
                        Log.d(TAG, "Cloud account has legacy data. Restoring existing collections...")
                        preflightLegacyCloudState(uid, fs)
                        syncSettings(uid, fs, forceRestoreIfInitial = isInitialLogin || manualSync,
                            pushLocalIfNewer = !manualSync)
                        if (!manualSync) syncProfilePicture(uid, fs)
                        syncFavorites(uid, fs, pushLocalMissing = !manualSync, replaceLocal = manualSync)
                        syncPlaylists(uid, fs, pushLocalMissing = !manualSync, forceCloudRestore = manualSync)
                        syncSmartPlaylists(uid, fs, pushLocalMissing = !manualSync, replaceLocal = manualSync)
                        syncHistory(uid, fs, pushLocalMissing = !manualSync)
                        syncRecentSearches(uid, fs, pushLocalMissing = !manualSync, replaceLocal = manualSync)
                        syncLyricsOffsets(uid, fs, replaceLocal = manualSync)
                        syncCloudPlaylistPlayEvents(uid, fs)
                    }
                }

                if (getCurrentUid() != uid) throw CancellationException("Account changed during cloud sync")
                relinkHistoryAndRecomputePlayStats()
                if (!manualSync) syncUserRootDocument(uid, fs)

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
        val userRef = fs.collection(USERS_COLLECTION).document(uid)
        val settingsDoc = userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES).get(Source.SERVER).await()
        val favoritesSnapshot = userRef.collection(FAVORITES_COLLECTION).limit(1).get(Source.SERVER).await()
        val historySnapshot = userRef.collection(HISTORY_COLLECTION).limit(1).get(Source.SERVER).await()
        val playlistsSnapshot = userRef.collection(PLAYLISTS_COLLECTION).limit(1).get(Source.SERVER).await()
        val smartPlaylistsSnapshot = userRef.collection(SMART_PLAYLISTS_COLLECTION).limit(1).get(Source.SERVER).await()
        val foldersSnapshot = userRef.collection(FOLDERS_COLLECTION).limit(1).get(Source.SERVER).await()
        val searchesSnapshot = userRef.collection(SEARCH_COLLECTION).limit(1).get(Source.SERVER).await()
        val lyricsOffsetsSnapshot = userRef.collection(LYRICS_OFFSETS_COLLECTION).limit(1).get(Source.SERVER).await()
        val playlistHistorySnapshot = userRef.collection(PLAYLIST_HISTORY_COLLECTION).limit(1).get(Source.SERVER).await()

        return !settingsDoc.exists() && favoritesSnapshot.isEmpty && historySnapshot.isEmpty &&
            playlistsSnapshot.isEmpty && smartPlaylistsSnapshot.isEmpty && foldersSnapshot.isEmpty &&
            searchesSnapshot.isEmpty && lyricsOffsetsSnapshot.isEmpty && playlistHistorySnapshot.isEmpty
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
    private suspend fun syncPlaylists(
        uid: String,
        fs: FirebaseFirestore,
        pushLocalMissing: Boolean = true,
        forceCloudRestore: Boolean = false
    ) = withContext(Dispatchers.IO) {
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
            "${com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }
        val songsByFilename = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }
        val songsByCleanTitle = allSongs.groupBy { com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase() }
        val restoredPlaylistIds = HashSet<Long>()

        for (cloudPl in cloudPlaylists) {
            val key = cloudPl.name.trim().lowercase()
            val canonicalCloudId = cloudPl.cloudId.ifBlank { legacyPlaylistCloudId(cloudPl.name, cloudPl.createdAt) }
            val localPlaylist = existingByCloudId[canonicalCloudId] ?: existingByName[key]
            val updatedPlaylist = PlaylistEntity(
                id = localPlaylist?.id ?: 0L,
                name = cloudPl.name,
                createdAt = if (cloudPl.createdAt > 0L) cloudPl.createdAt else localPlaylist?.createdAt ?: System.currentTimeMillis(),
                isPinned = cloudPl.isPinned,
                artworkUri = cloudPl.artworkUri?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                cloudId = canonicalCloudId
            )
            val playlistId = playlistDao.insertPlaylist(updatedPlaylist)
            restoredPlaylistIds += playlistId
            existingByName[key] = updatedPlaylist.copy(id = playlistId)
            playlistLocalToCloudMap[playlistId] = canonicalCloudId
            playlistCloudToLocalMap[canonicalCloudId] = playlistId

            val localSongCount = playlistDao.getPlaylistSongCount(playlistId)
            val localUpdatedAt = getLocalPlaylistUpdatedAt(playlistId)

            // If local playlist was reordered/updated more recently than the cloud doc and has songs,
            // push local custom order to cloud instead of overwriting it!
            if (!forceCloudRestore && localSongCount > 0 && localUpdatedAt > cloudPl.updatedAt && cloudPl.updatedAt > 0L) {
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

            if (forceCloudRestore || crossRefs.isNotEmpty() && (localSongCount == 0 || cloudPl.updatedAt >= localUpdatedAt)) {
                playlistDao.updatePlaylistOrder(playlistId, crossRefs)
                if (cloudPl.updatedAt > 0L) {
                    setLocalPlaylistUpdatedAt(playlistId, cloudPl.updatedAt)
                }
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
        }
        val songsByCleanTitle = allSongs.groupBy { com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase() }
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
            val matchingSong = songIdMap[history.trackId]
            val songSignature = matchingSong?.let { SongIdentityHelper.computeSignature(it) } ?: "unresolved"
            val sessionId = stableHistorySessionId(history, songSignature)
            if (cachedCloudHistoryEvents.containsKey(sessionId)) continue
            val event = CloudPlaybackEvent(
                sessionId = sessionId,
                songSignature = songSignature,
                title = matchingSong?.title ?: "",
                artist = matchingSong?.artist ?: "",
                album = matchingSong?.album ?: "",
                durationMs = matchingSong?.durationMs ?: 0L,
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
    private suspend fun syncLyricsOffsets(uid: String, fs: FirebaseFirestore, replaceLocal: Boolean = false) {
        val offsetsRef = fs.collection(USERS_COLLECTION).document(uid).collection(LYRICS_OFFSETS_COLLECTION)
        val snapshot = offsetsRef.get(Source.SERVER).await()
        val cloudOffsets = snapshot.documents.map { doc ->
            val data = doc.data ?: throw IllegalStateException("Cloud lyrics offset ${doc.id} is unreadable")
            if ((data["songSignature"] as? String).isNullOrBlank() || data["offsetMs"] !is Number) {
                throw IllegalStateException("Cloud lyrics offset ${doc.id} is incomplete")
            }
            CloudLyricsOffset.fromMap(data)
        }
        val allSongs = songDao.getAllSongsOnce()
        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val offsetsBySongId = cloudOffsets.mapNotNull { cloudOffset ->
            songsBySig[cloudOffset.songSignature]?.let { it.id to cloudOffset.offsetMs }
        }.toMap()
        userPreferences.applyLyricsSyncOffsets(offsetsBySongId, replace = replaceLocal)
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

    private fun cloudLyricsItemsRef(uid: String, snapshotId: String, fs: FirebaseFirestore) =
        fs.collection(USERS_COLLECTION).document(uid)
            .collection("lyrics_snapshots").document(snapshotId).collection("items")

    private suspend fun writeCloudLyricsSnapshot(
        uid: String,
        snapshotId: String,
        entries: List<CloudLyricsEntry>,
        fs: FirebaseFirestore
    ) {
        val ref = cloudLyricsItemsRef(uid, snapshotId, fs)
        var batch = fs.batch()
        var count = 0
        for (entry in entries) {
            if (entry.songSignature.isBlank()) throw IllegalStateException("Lyrics snapshot contains an unidentified track")
            val data = entry.toMap()
            if (data.toString().toByteArray(Charsets.UTF_8).size > 850 * 1024) {
                throw IllegalStateException("A lyrics record exceeds Firestore's safe document size")
            }
            batch.set(ref.document(sha256(entry.songSignature)), data)
            count++
            if (count >= 400) {
                batch.commit().await()
                batch = fs.batch()
                count = 0
            }
        }
        if (count > 0) batch.commit().await()

        val verified = ref.get(Source.SERVER).await().documents
        val expectedIds = entries.map { sha256(it.songSignature) }.toSet()
        if (verified.size != entries.size || verified.map { it.id }.toSet() != expectedIds ||
            verified.any { it.getString("songSignature").isNullOrBlank() }) {
            throw IllegalStateException("Lyrics snapshot could not be verified; the previous cloud snapshot was kept")
        }
    }

    private suspend fun fetchCloudLyrics(
        uid: String,
        payload: UserBackupPayload,
        fs: FirebaseFirestore
    ): List<CloudLyricsEntry> {
        if (payload.version < 3 || !payload.lyricsStoredSeparately || payload.lyricsSnapshotId.isBlank()) return emptyList()
        val docs = cloudLyricsItemsRef(uid, payload.lyricsSnapshotId, fs).get(Source.SERVER).await().documents
        if (docs.size != payload.lyricsCount) {
            throw IllegalStateException("Cloud lyrics snapshot is incomplete; local lyrics were left unchanged")
        }
        val entries = docs.map { doc ->
            val data = doc.data ?: throw IllegalStateException("Cloud lyrics record ${doc.id} is unreadable")
            val entry = CloudLyricsEntry.fromMap(data)
            if (entry.songSignature.isBlank() || doc.id != sha256(entry.songSignature) ||
                (entry.plainLyrics.isNullOrBlank() && entry.syncedLyricsJson.isNullOrBlank())) {
                throw IllegalStateException("Cloud lyrics record ${doc.id} is incomplete")
            }
            entry
        }
        if (entries.map { it.songSignature }.distinct().size != entries.size) {
            throw IllegalStateException("Cloud lyrics snapshot contains duplicate track identities")
        }
        val lyricsHash = sha256(entries.map { it.toMap().toSortedMap() }
            .sortedBy { it["songSignature"].toString() }.toString())
        if (lyricsHash != payload.lyricsSnapshotId) {
            throw IllegalStateException("Cloud lyrics snapshot failed its integrity check")
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
    private suspend fun restoreFromBackupSnapshot(
        uid: String,
        data: Map<String, Any?>,
        cloudHistory: List<CloudPlaybackEvent>,
        cloudPlaylistHistory: List<CloudPlaylistPlayEvent>,
        cloudLyrics: List<CloudLyricsEntry>
    ) = withContext(Dispatchers.IO) {
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
        val uniqueSongsBySig = allSongs.groupBy { SongIdentityHelper.computeSignature(it) }
            .filterValues { it.size == 1 }.mapValues { it.value.single() }
        val songsByNormKey = allSongs.groupBy {
            "${com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }
        val songsByFilename = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }
        val songsByCleanTitle = allSongs.groupBy { com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase() }

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

        // Snapshot collections are authoritative, so explicit cloud deletions are applied locally.
        allSongs.filter { it.isFavorite }.map { it.id }.chunked(400)
            .forEach { ids -> songDao.setFavoritesBatch(ids, false) }
        cachedCloudFavorites.clear()
        payload.favorites.filter { it.isFavorite && it.songSignature.isNotBlank() }.forEach {
            cachedCloudFavorites[it.songSignature] = it
        }
        matchAndApplyCloudFavoritesToRoom()

        val existingPlaylists = playlistDao.getAllPlaylistsOnce()
        val priorCloudIds = playlistLocalToCloudMap.toMap()
        val existingByIdentity = existingPlaylists.associateBy { playlist ->
            priorCloudIds[playlist.id] ?: playlist.cloudId.takeIf { it.isNotBlank() }
                ?: legacyPlaylistCloudId(playlist.name, playlist.createdAt)
        }.toMutableMap()
        val existingByName = existingPlaylists.associateBy { it.name.trim().lowercase() }.toMutableMap()
        val retainedPlaylistIds = HashSet<Long>()
        playlistLocalToCloudMap.clear()
        playlistCloudToLocalMap.clear()
        for (cloudPl in payload.playlists) {
            val canonicalCloudId = cloudPl.cloudId.ifBlank { legacyPlaylistCloudId(cloudPl.name, cloudPl.createdAt) }
            val key = cloudPl.name.trim().lowercase()
            val existing = existingByIdentity[canonicalCloudId] ?: existingByName[key]
            val playlist = PlaylistEntity(
                id = existing?.id ?: 0L,
                name = cloudPl.name,
                createdAt = cloudPl.createdAt,
                isPinned = cloudPl.isPinned,
                artworkUri = cloudPl.artworkUri?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                cloudId = canonicalCloudId
            )
            val playlistId = playlistDao.insertPlaylist(playlist)
            retainedPlaylistIds += playlistId
            playlistLocalToCloudMap[playlistId] = canonicalCloudId
            playlistCloudToLocalMap[canonicalCloudId] = playlistId
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
                    crossRefs += PlaylistSongCrossRef(playlistId, matched.id, crossRefs.size)
                }
            }
            playlistDao.updatePlaylistOrder(playlistId, crossRefs)
            setLocalPlaylistUpdatedAt(playlistId, cloudPl.updatedAt)
        }
        existingPlaylists.filter { it.id !in retainedPlaylistIds }.forEach { stale ->
            playlistDao.clearPlaylistSongs(stale.id)
            playlistDao.deletePlaylist(stale.id)
            playlistLocalToCloudMap.remove(stale.id)?.let(playlistCloudToLocalMap::remove)
        }

        applyCloudPlaylistPlayEvents(cloudPlaylistHistory)

        // History is append-only: merge all valid events and never clear locally recorded sessions.
        cachedCloudHistoryEvents.clear()
        historyById.values.forEach { cachedCloudHistoryEvents[it.sessionId] = it }
        val historyToInsert = mutableListOf<PlaybackHistoryEntity>()
        for (event in historyById.values) {
            if (historyDao.getHistoryBySessionId(event.sessionId) != null) continue
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

        val matchedOffsetSongIds = payload.lyricsOffsets.mapNotNull { offset ->
            uniqueSongsBySig[offset.songSignature]?.id
        }.toSet()
        val offsetsBySongId = payload.lyricsOffsets.mapNotNull { offset ->
            uniqueSongsBySig[offset.songSignature]?.let { it.id to offset.offsetMs }
        }.toMap()
        val offsetTimestampsBySongId = payload.lyricsOffsets.mapNotNull { offset ->
            uniqueSongsBySig[offset.songSignature]?.let { it.id to offset.updatedAt }
        }.toMap()
        userPreferences.applyLyricsSyncOffsets(
            offsetsBySongId,
            clearSongIds = matchedOffsetSongIds,
            timestampsBySongId = offsetTimestampsBySongId
        )

        val localLyricsBySongId = lyricsDao.getAllLyricsOnce().associateBy { it.songId }
        for (entry in cloudLyrics) {
            val song = uniqueSongsBySig[entry.songSignature] ?: continue
            val local = localLyricsBySongId[song.id]
            // Keep locally edited lyrics if they are newer than the last explicit cloud snapshot.
            if (local != null && local.updatedAt > entry.updatedAt) continue
            lyricsDao.insertLyrics(
                LyricsEntity(
                    songId = song.id,
                    plainLyrics = entry.plainLyrics,
                    syncedLyricsJson = entry.syncedLyricsJson,
                    isCached = entry.isCached,
                    source = entry.source,
                    updatedAt = entry.updatedAt
                )
            )
        }
        Log.i(TAG, "Restored authoritative snapshot successfully")
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

            val currentUser = auth?.currentUser
            if (currentUser?.uid != uid) throw CancellationException("Account changed before backup")
            val exportedSettings = userPreferences.exportUserSettings()
            val avatarBase64 = encodeAvatarForBackup(exportedSettings.customAvatarUri)
            val currentSettings = exportedSettings.copy(customAvatarUri = null, avatarBase64 = avatarBase64)
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

            val localPlaylistEntities = playlistDao.getAllPlaylistsOnce()
            val localPlaylistCloudIds = mutableMapOf<Long, String>()
            for (playlist in localPlaylistEntities) {
                localPlaylistCloudIds[playlist.id] = ensureCloudPlaylistId(playlist)
            }
            val playlists = localPlaylistEntities.map { p ->
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
                    cloudId = localPlaylistCloudIds.getValue(p.id),
                    name = p.name,
                    createdAt = p.createdAt,
                    updatedAt = localUpdatedAt,
                    isPinned = p.isPinned,
                    artworkUri = p.artworkUri?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                    songs = cloudSongs
                )
            }

            val historyRecords = historyDao.getAllHistoryOnce()
            val songIdMap = allSongs.associateBy { it.id }
            val localHistory = historyRecords.map { h ->
                val matchingSong = songIdMap[h.trackId]
                val sig = matchingSong?.let { SongIdentityHelper.computeSignature(it) } ?: "unresolved"
                CloudPlaybackEvent(
                    sessionId = stableHistorySessionId(h, sig),
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
            val historyById = LinkedHashMap<String, CloudPlaybackEvent>()
            val existingCloudHistory = fetchCloudHistoryEvents(uid, fs)
            val existingCloudHistoryById = existingCloudHistory.associateBy { it.sessionId }
            existingCloudHistory.forEach { historyById[it.sessionId] = it }
            localHistory.forEach { historyById[it.sessionId] = it }
            val fullHistory = historyById.values.toList()
            val historySample = fullHistory.sortedByDescending { it.startedAt }.take(100)

            val existingPlaylistHistory = fetchCloudPlaylistPlayEvents(uid, fs)
            val existingPlaylistHistoryById = existingPlaylistHistory.associateBy { it.eventId }
            val playlistHistoryById = LinkedHashMap<String, CloudPlaylistPlayEvent>()
            existingPlaylistHistory.forEach { playlistHistoryById[it.eventId] = it }
            playlistDao.getAllPlaylistPlayEventsOnce().forEach { localEvent ->
                val cloudPlaylistId = localPlaylistCloudIds[localEvent.playlistId] ?: return@forEach
                val eventId = sha256("$cloudPlaylistId:${localEvent.timestamp}")
                playlistHistoryById[eventId] = CloudPlaylistPlayEvent(
                    eventId = eventId,
                    playlistCloudId = cloudPlaylistId,
                    timestamp = localEvent.timestamp
                )
            }
            val fullPlaylistHistory = playlistHistoryById.values.toList()

            val totalPlays = fullHistory.size.toLong()
            val totalListeningMs = fullHistory.sumOf { it.listenedDurationMs }
            val topArtists = historyDao.getTopArtistsBetween(0L, limit = 5).firstOrNull()?.map { it.name } ?: emptyList()
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

            val uniqueSongSignatures = allSongs.groupBy { SongIdentityHelper.computeSignature(it) }
                .filterValues { it.size == 1 }.keys
            val localLyricsOffsets = userPreferences.getAllLyricsSyncOffsets()
            val lyricsOffsets = allSongs.mapNotNull { song ->
                val signature = SongIdentityHelper.computeSignature(song)
                if (signature !in uniqueSongSignatures) return@mapNotNull null
                val offset = localLyricsOffsets[song.id] ?: 0L
                val updatedAt = userPreferences.getLyricsSyncOffsetUpdatedAt(song.id)
                if (offset == 0L && updatedAt == 0L) null else CloudLyricsOffset(signature, offset, updatedAt)
            }
            val songsById = allSongs.associateBy { it.id }
            val cloudLyrics = lyricsDao.getAllLyricsOnce().mapNotNull { lyrics ->
                val song = songsById[lyrics.songId] ?: return@mapNotNull null
                val signature = SongIdentityHelper.computeSignature(song)
                if (signature !in uniqueSongSignatures ||
                    (lyrics.plainLyrics.isNullOrBlank() && lyrics.syncedLyricsJson.isNullOrBlank())) return@mapNotNull null
                CloudLyricsEntry(
                    songSignature = signature,
                    plainLyrics = lyrics.plainLyrics,
                    syncedLyricsJson = lyrics.syncedLyricsJson,
                    isCached = lyrics.isCached,
                    source = lyrics.source,
                    updatedAt = lyrics.updatedAt
                )
            }.associateBy { it.songSignature }.values.toList()

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

            val lastHash = userPreferences.lastBackupDataHash.firstOrNull()
            val now = System.currentTimeMillis()
            if (isAutoBackup && lastHash == dataHash) {
                val latest = fs.collection(USERS_COLLECTION).document(uid).collection("backup")
                    .document("latest").get(Source.SERVER).await()
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

            val backupCollection = fs.collection(USERS_COLLECTION).document(uid).collection("backup")
            val stagingDoc = backupCollection.document("staging")
            val latestDoc = backupCollection.document("latest")
            val payloadMap = payload.toMap()
            if (payloadMap.toString().toByteArray(Charsets.UTF_8).size > 850 * 1024) {
                throw IllegalStateException("Snapshot is too large for Firestore; the previous cloud snapshot was kept")
            }

            // Preserve existing history events and verify the history collection before publishing a snapshot.
            if (getCurrentUid() != uid) throw CancellationException("Account changed during backup")
            var historyBatch = fs.batch()
            var historyBatchSize = 0
            val historyToWrite = fullHistory.filter { existingCloudHistoryById[it.sessionId]?.toMap() != it.toMap() }
            for (event in historyToWrite) {
                historyBatch.set(fs.collection(USERS_COLLECTION).document(uid)
                    .collection(HISTORY_COLLECTION).document(event.sessionId), event.toMap(), SetOptions.merge())
                historyBatchSize++
                if (historyBatchSize >= 400) {
                    historyBatch.commit().await()
                    historyBatch = fs.batch()
                    historyBatchSize = 0
                }
            }
            if (historyBatchSize > 0) historyBatch.commit().await()
            val verifiedHistoryIds = fetchCloudHistoryEvents(uid, fs).map { it.sessionId }.toHashSet()
            if (!verifiedHistoryIds.containsAll(fullHistory.map { it.sessionId })) {
                throw IllegalStateException("Listening history could not be fully verified; the previous snapshot was kept")
            }

            val playlistHistoryToWrite = fullPlaylistHistory.filter {
                existingPlaylistHistoryById[it.eventId]?.toMap() != it.toMap()
            }
            var playlistHistoryBatch = fs.batch()
            var playlistHistoryBatchSize = 0
            val playlistHistoryRef = fs.collection(USERS_COLLECTION).document(uid)
                .collection(PLAYLIST_HISTORY_COLLECTION)
            for (event in playlistHistoryToWrite) {
                playlistHistoryBatch.set(playlistHistoryRef.document(event.eventId), event.toMap(), SetOptions.merge())
                playlistHistoryBatchSize++
                if (playlistHistoryBatchSize >= 400) {
                    playlistHistoryBatch.commit().await()
                    playlistHistoryBatch = fs.batch()
                    playlistHistoryBatchSize = 0
                }
            }
            if (playlistHistoryBatchSize > 0) playlistHistoryBatch.commit().await()
            val verifiedPlaylistHistoryIds = fetchCloudPlaylistPlayEvents(uid, fs).map { it.eventId }.toHashSet()
            if (!verifiedPlaylistHistoryIds.containsAll(fullPlaylistHistory.map { it.eventId })) {
                throw IllegalStateException("Playlist history could not be fully verified; the previous snapshot was kept")
            }

            if (getCurrentUid() != uid) throw CancellationException("Account changed before lyrics snapshot publication")
            writeCloudLyricsSnapshot(uid, lyricsSnapshotId, cloudLyrics, fs)

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

            try {
                stagingDoc.delete().await()
            } catch (_: Exception) {}

            try {
                val allBackups = backupCollection.get(Source.SERVER).await()
                for (doc in allBackups.documents) {
                    if (doc.id != "latest") {
                        doc.reference.delete().await()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error pruning previous backup documents: ${e.message}")
            }

            try {
                fs.collection(USERS_COLLECTION).document(uid)
                    .set(mapOf("lastBackupAt" to now), SetOptions.merge()).await()
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot succeeded but updating the account timestamp failed: ${e.message}")
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
            "${com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase()}|${com.example.util.TagCleaner.cleanArtist(it.artist).lowercase()}"
        }
        val songsByFilename = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }
        val songsByCleanTitle = allSongs.groupBy { com.example.util.TagCleaner.cleanTitle(it.title, "").lowercase() }
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

    fun onLibraryScanCompleted() {
        scope.launch {
            try {
                matchAndApplyCloudFavoritesToRoom()
                relinkHistoryAndRecomputePlayStats()
            } catch (e: Exception) {
                Log.w(TAG, "Error in post-scan sync hook: ${e.message}")
            }
        }
    }
}
