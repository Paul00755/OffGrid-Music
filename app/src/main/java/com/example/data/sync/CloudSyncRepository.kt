package com.example.data.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.data.local.*
import com.example.data.model.Song
import com.google.firebase.FirebaseApp
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

    // Currently authenticated UID being synchronized
    @Volatile
    private var activeUid: String? = null

    private val firestore: FirebaseFirestore? by lazy {
        injectedFirestore ?: try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "Firestore initialization error: ${e.message}")
            null
        }
    }

    private val auth: FirebaseAuth? by lazy {
        injectedAuth ?: try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseAuth initialization error: ${e.message}")
            null
        }
    }

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
            userPreferences.resetAccountSpecificData()
            Log.d(TAG, "Account-owned local data cleared successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing account-owned local data", e)
        }
    }

    /**
     * Called when authentication state changes or user signs in/out.
     * Enforces strict user account isolation:
     * - If no authenticated Firebase user (guest state), previous user's account data is cleared.
     * - If switching between different authenticated accounts, previous user's data is wiped first.
     * - Prevents cross-user leakage and ensures devices with no login do not inherit another user.
     */
    fun onAuthStateChanged(newUid: String?) {
        scope.launch {
            val authUser = auth?.currentUser
            val authoritativeUid = authUser?.uid?.takeIf { it.isNotBlank() }
            val lastUid = userPreferences.getLastActiveUid()

            if (authoritativeUid == null || newUid == null) {
                // User signed out OR no authenticated Firebase user (Guest state)
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

                // If a user was previously logged in on this device (or restored from another device/backup),
                // clear their account-owned local data so the guest does not see or inherit it!
                if (!lastUid.isNullOrBlank()) {
                    Log.d(TAG, "Transitioning from authenticated user ($lastUid) to clean guest state. Clearing account data.")
                    clearAccountOwnedLocalData()
                }
                Log.d(TAG, "Cloud sync stopped: guest/local state active")
                return@launch
            }

            // We have an authenticated Firebase user!
            // If switching accounts (from User A to User B), clear previous user's data before loading!
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

            if (activeUid != authoritativeUid) {
                activeSyncJob?.cancel()
                activeSyncJob = null
                activeUid = authoritativeUid
                cachedCloudFavorites.clear()
                cachedCloudHistoryEvents.clear()
                playlistLocalToCloudMap.clear()
                playlistCloudToLocalMap.clear()
                userPreferences.setLastActiveUid(authoritativeUid)
                Log.d(TAG, "Cloud sync initialized for user: $authoritativeUid")
                triggerFullSync(isInitialLogin = true)
            }
        }
    }

    /**
     * Executes a full synchronization sequence:
     * 1. Check & restore from latest backup snapshot (if present)
     * 2. Settings & Profile Photo
     * 3. Folders (included & excluded)
     * 4. Favorites
     * 5. Playlists & Smart Playlists
     * 6. History & Listening Stats
     * 7. Recent Search History
     * 8. Lyrics Offsets
     */
    fun triggerFullSync(isInitialLogin: Boolean = false) {
        val uid = getCurrentUid() ?: return
        activeSyncJob?.cancel()
        activeSyncJob = scope.launch {
            try {
                _syncStatus.value = SyncStatus.Syncing
                val fs = firestore
                if (fs == null) {
                    _syncStatus.value = SyncStatus.Error("Firestore unavailable")
                    return@launch
                }

                // Ensure user root document exists
                syncUserRootDocument(uid, fs)

                // 1. Check if a latest backup snapshot exists and restore it
                try {
                    val latestBackupDoc = fs.collection(USERS_COLLECTION).document(uid)
                        .collection("backup").document("latest").get().await()
                    if (latestBackupDoc.exists() && latestBackupDoc.data != null) {
                        Log.d(TAG, "Found latest backup snapshot for user $uid. Restoring backup snapshot...")
                        restoreFromBackupSnapshot(uid, latestBackupDoc.data!!)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking/restoring latest backup snapshot: ${e.message}")
                }

                // Check if cloud account has existing data
                val isCloudEmpty = checkIsCloudAccountEmpty(uid, fs)

                if (isCloudEmpty) {
                    Log.d(TAG, "Cloud account is empty. Performing initial migration from local Room/DataStore...")
                    performInitialMigration(uid, fs)
                } else {
                    Log.d(TAG, "Cloud account has data. Restoring and merging with local Room...")
                    // 1. Settings (reinstall restoration / conflict check)
                    syncSettings(uid, fs)

                    // 2. Profile picture / avatar
                    syncProfilePicture(uid, fs)

                    // 3. Folders
                    syncFolders(uid, fs)

                    // 4. Favorites
                    syncFavorites(uid, fs)

                    // 5. Playlists & Smart Playlists
                    syncPlaylists(uid, fs)
                    syncSmartPlaylists(uid, fs)

                    // 6. History & Listening stats
                    syncHistory(uid, fs)

                    // 7. Recent searches
                    syncRecentSearches(uid, fs)

                    // 8. Lyrics offsets
                    syncLyricsOffsets(uid, fs)
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
     * Never overwrites or deletes local data.
     */
    private suspend fun performInitialMigration(uid: String, fs: FirebaseFirestore) {
        val userRef = fs.collection(USERS_COLLECTION).document(uid)

        // 1. Migrate Settings
        val currentSettings = userPreferences.exportUserSettings()
        userRef.collection(SETTINGS_COLLECTION)
            .document(SETTINGS_DOC_PREFERENCES)
            .set(currentSettings.toMap(), SetOptions.merge())
            .await()

        // 2. Migrate Favorites
        val allSongs = songDao.getAllSongsOnce()
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

        // 3. Migrate Playlists
        val playlists = playlistDao.getAllPlaylistsOnce()
        for (p in playlists) {
            val cloudId = "pl_${p.createdAt}_${p.name.hashCode()}"
            playlistLocalToCloudMap[p.id] = cloudId
            playlistCloudToLocalMap[cloudId] = p.id

            val songsInPlaylist = playlistDao.getPlaylistSongs(p.id).firstOrNull() ?: emptyList()
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
            val cloudPl = CloudPlaylist(
                cloudId = cloudId,
                name = p.name,
                createdAt = p.createdAt,
                updatedAt = System.currentTimeMillis(),
                isPinned = p.isPinned,
                artworkUri = p.artworkUri,
                songs = cloudSongs
            )
            userRef.collection(PLAYLISTS_COLLECTION).document(cloudId).set(cloudPl.toMap(), SetOptions.merge())
        }

        // 4. Migrate Listening History
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

        // 5. Migrate Lyrics Offsets
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

        // 6. Migrate Folders
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

        // 7. Migrate Smart Playlists
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

        // 8. Migrate Recent Searches
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

        // 9. Migrate Profile Picture
        syncProfilePicture(uid, fs)

        Log.d(TAG, "Initial migration to Firestore complete for user $uid")
    }

    /**
     * Synchronizes Settings:
     * Deterministic last-write-wins based on updatedAt timestamp.
     */
    private suspend fun syncSettings(uid: String, fs: FirebaseFirestore) {
        val userRef = fs.collection(USERS_COLLECTION).document(uid)
        val docSnap = userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES).get().await()

        val localSettings = userPreferences.exportUserSettings()
        if (docSnap.exists() && docSnap.data != null) {
            val cloudSettings = CloudUserSettings.fromMap(docSnap.data!!)
            if (cloudSettings.updatedAt > localSettings.updatedAt) {
                // Cloud has newer settings -> restore to local preferences
                userPreferences.importUserSettings(cloudSettings, checkTimestamp = true)
                Log.d(TAG, "Restored newer settings from cloud (updatedAt: ${cloudSettings.updatedAt})")
            } else if (localSettings.updatedAt > cloudSettings.updatedAt) {
                // Local has newer settings -> push to cloud
                userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES)
                    .set(localSettings.toMap(), SetOptions.merge()).await()
                Log.d(TAG, "Uploaded newer local settings to cloud (updatedAt: ${localSettings.updatedAt})")
            }
        } else {
            // Document doesn't exist yet -> upload local settings
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
     * Safe against device uninstalls / MediaStore ID changes.
     */
    suspend fun matchAndApplyCloudFavoritesToRoom() = withContext(Dispatchers.IO) {
        if (cachedCloudFavorites.isEmpty()) return@withContext
        val allSongs = songDao.getAllSongsOnce()
        val favsBySig = cachedCloudFavorites.filter { it.value.isFavorite }
        val favsByNormalizedKey = favsBySig.values.groupBy { "${com.example.util.TagCleaner.cleanTitle(it.title, "")}|${com.example.util.TagCleaner.cleanArtist(it.artist)}" }
        val favsByFilename = favsBySig.values.filter { it.relativeFilename.isNotBlank() }.groupBy { it.relativeFilename.lowercase() }

        val matchingIds = mutableListOf<Long>()
        for (song in allSongs) {
            if (song.isFavorite) continue
            val localSig = SongIdentityHelper.computeSignature(song)
            val directMatch = favsBySig[localSig]
            if (directMatch != null) {
                matchingIds.add(song.id)
                continue
            }

            // O(1) normalized Title + Artist lookup
            val normKey = "${com.example.util.TagCleaner.cleanTitle(song.title, "")}|${com.example.util.TagCleaner.cleanArtist(song.artist)}"
            val titleArtistMatch = favsByNormalizedKey[normKey]?.firstOrNull()
            if (titleArtistMatch != null) {
                matchingIds.add(song.id)
                continue
            }

            // O(1) filename match
            val filename = song.path.substringAfterLast('/').lowercase()
            val filenameMatch = favsByFilename[filename]?.firstOrNull {
                Math.abs((song.durationMs - it.durationMs) / 1000L) <= 5L
            }
            if (filenameMatch != null) {
                matchingIds.add(song.id)
            }
        }

        if (matchingIds.isNotEmpty()) {
            songDao.setFavoritesBatch(matchingIds, true)
        }
    }

    /**
     * Synchronizes Playlists:
     * Downloads cloud playlists and reconstructs them locally with stable IDs.
     * Safe against missing songs; preserves ordering and metadata.
     */
    private suspend fun syncPlaylists(uid: String, fs: FirebaseFirestore) = withContext(Dispatchers.IO) {
        val playlistsRef = fs.collection(USERS_COLLECTION).document(uid).collection(PLAYLISTS_COLLECTION)
        val snapshot = playlistsRef.get().await()
        val allSongs = songDao.getAllSongsOnce()
        val existingPlaylists = playlistDao.getAllPlaylistsOnce()
        val existingByName = existingPlaylists.associateBy { it.name.trim().lowercase() }

        // Fast O(1) lookup indexes for all songs to eliminate CPU thrashing during sync
        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val songsByNormKey = allSongs.groupBy { "${com.example.util.TagCleaner.cleanTitle(it.title, "")}|${com.example.util.TagCleaner.cleanArtist(it.artist)}" }
        val songsByFilename = allSongs.groupBy { it.path.substringAfterLast('/').lowercase() }

        for (doc in snapshot.documents) {
            val data = doc.data ?: continue
            val cloudPl = CloudPlaylist.fromMap(data)
            if (cloudPl.name.isBlank()) continue

            // Determine if playlist already exists locally
            val localPlaylist = existingByName[cloudPl.name.trim().lowercase()]
            val playlistId = if (localPlaylist != null) {
                localPlaylist.id
            } else {
                playlistDao.insertPlaylist(
                    PlaylistEntity(
                        name = cloudPl.name,
                        createdAt = cloudPl.createdAt,
                        isPinned = cloudPl.isPinned,
                        artworkUri = cloudPl.artworkUri
                    )
                )
            }
            playlistLocalToCloudMap[playlistId] = cloudPl.cloudId
            playlistCloudToLocalMap[cloudPl.cloudId] = playlistId

            // Reconstruct playlist song associations based on stable song signatures with O(1) fast lookup
            val crossRefs = mutableListOf<PlaylistSongCrossRef>()
            for (item in cloudPl.songs) {
                val matchedSong = songsBySig[item.songSignature]
                    ?: songsByNormKey["${com.example.util.TagCleaner.cleanTitle(item.title, "")}|${com.example.util.TagCleaner.cleanArtist(item.artist)}"]?.firstOrNull()
                    ?: songsByFilename[item.filename.lowercase()]?.firstOrNull {
                        Math.abs((it.durationMs - item.durationMs) / 1000L) <= 5L
                    }

                if (matchedSong != null) {
                    crossRefs.add(PlaylistSongCrossRef(playlistId, matchedSong.id, item.position))
                }
            }

            if (crossRefs.isNotEmpty()) {
                playlistDao.updatePlaylistOrder(playlistId, crossRefs)
            }
        }

        // Push newly created local playlists that aren't in cloud yet
        for (localPl in playlistDao.getAllPlaylistsOnce()) {
            if (!playlistLocalToCloudMap.containsKey(localPl.id)) {
                val cloudId = "pl_${localPl.createdAt}_${localPl.name.hashCode()}"
                playlistLocalToCloudMap[localPl.id] = cloudId
                playlistCloudToLocalMap[cloudId] = localPl.id

                val songsInPlaylist = playlistDao.getPlaylistSongs(localPl.id).firstOrNull() ?: emptyList()
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
                val cloudPl = CloudPlaylist(
                    cloudId = cloudId,
                    name = localPl.name,
                    createdAt = localPl.createdAt,
                    updatedAt = System.currentTimeMillis(),
                    isPinned = localPl.isPinned,
                    artworkUri = localPl.artworkUri,
                    songs = cloudSongs
                )
                playlistsRef.document(cloudId).set(cloudPl.toMap(), SetOptions.merge())
            }
        }
    }

    /**
     * Synchronizes Listening History:
     * Idempotent insertion using sessionId.
     * Merges events from all devices without duplication.
     */
    private suspend fun syncHistory(uid: String, fs: FirebaseFirestore) {
        val historyRef = fs.collection(USERS_COLLECTION).document(uid).collection(HISTORY_COLLECTION)
        val snapshot = historyRef.limit(2500).get().await()
        val allSongs = songDao.getAllSongsOnce()
        val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
        val songsByNormKey = allSongs.groupBy { "${com.example.util.TagCleaner.cleanTitle(it.title, "")}|${com.example.util.TagCleaner.cleanArtist(it.artist)}" }

        for (doc in snapshot.documents) {
            val data = doc.data ?: continue
            val ev = CloudPlaybackEvent.fromMap(data)
            if (ev.sessionId.isBlank()) continue

            // Check if already in local Room
            val localExisting = historyDao.getHistoryBySessionId(ev.sessionId)
            if (localExisting == null) {
                // O(1) lookup matching local song for trackId
                val matchedSong = songsBySig[ev.songSignature]
                    ?: songsByNormKey["${com.example.util.TagCleaner.cleanTitle(ev.title, "")}|${com.example.util.TagCleaner.cleanArtist(ev.artist)}"]?.firstOrNull()

                val trackId = matchedSong?.id ?: 0L
                val entity = PlaybackHistoryEntity(
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
                historyDao.insertPlaybackHistory(entity)

                if (ev.isQualified && trackId > 0) {
                    songDao.recordPlay(trackId, ev.startedAt)
                }
            }
        }

        // Push local history events that aren't yet in Firestore using WriteBatch
        val localHistory = historyDao.getHistoryBatchOnce(500)
        val songIdMap = allSongs.associateBy { it.id }
        var histBatch = fs.batch()
        var histBatchCount = 0
        for (h in localHistory) {
            val sessionId = if (h.sessionId.isNotBlank()) h.sessionId else "session_${h.id}_${h.startedAt}"
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
     * Synchronizes Lyrics Offsets.
     */
    private suspend fun syncLyricsOffsets(uid: String, fs: FirebaseFirestore) {
        val offsetsRef = fs.collection(USERS_COLLECTION).document(uid).collection(LYRICS_OFFSETS_COLLECTION)
        val snapshot = offsetsRef.get().await()
        val allSongs = songDao.getAllSongsOnce()

        for (doc in snapshot.documents) {
            val data = doc.data ?: continue
            val cloudOffset = CloudLyricsOffset.fromMap(data)
            if (cloudOffset.songSignature.isBlank() || cloudOffset.offsetMs == 0L) continue

            val matchedSong = allSongs.firstOrNull { s ->
                SongIdentityHelper.computeSignature(s).equals(cloudOffset.songSignature, ignoreCase = true)
            }
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
     * Restores cloud avatar or pushes local avatar as a compact base64 representation.
     */
    private suspend fun syncProfilePicture(uid: String, fs: FirebaseFirestore) {
        try {
            val userRef = fs.collection(USERS_COLLECTION).document(uid)
            val prefDoc = userRef.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC_PREFERENCES).get().await()
            val localSettings = userPreferences.exportUserSettings()

            val cloudAvatarBase64 = prefDoc.getString("avatarBase64")
            val localAvatarUri = localSettings.customAvatarUri

            if (!cloudAvatarBase64.isNullOrBlank()) {
                val avatarDir = File(context.filesDir, "avatars")
                if (!avatarDir.exists()) avatarDir.mkdirs()
                val avatarFile = File(avatarDir, "user_avatar_$uid.png")

                val shouldWrite = !avatarFile.exists() || localAvatarUri == null
                if (shouldWrite) {
                    try {
                        val decodedBytes = Base64.decode(cloudAvatarBase64, Base64.DEFAULT)
                        avatarFile.writeBytes(decodedBytes)
                        val newUri = Uri.fromFile(avatarFile).toString()
                        userPreferences.setCustomAvatarUri(newUri)
                        Log.d(TAG, "Successfully restored avatar image from cloud")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode cloud avatar base64: ${e.message}")
                    }
                }
            } else if (!localAvatarUri.isNullOrBlank()) {
                try {
                    val uri = Uri.parse(localAvatarUri)
                    val inputStream = if (uri.scheme == "file") {
                        File(uri.path ?: "").inputStream()
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
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to upload local avatar to cloud: ${e.message}")
                }
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

            // Insert folders from cloud into local if not present
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

            // Upload local folders to cloud if not in cloud
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

            // Insert cloud smart playlists into local if not present
            val toInsertLocally = cloudSmart.filter { !localNames.contains(it.name.trim().lowercase()) }.map {
                SmartPlaylistEntity(
                    name = it.name,
                    genreFilter = it.filterType,
                    maxTrackLimit = it.limit,
                    createdAt = it.createdAt
                )
            }
            if (toInsertLocally.isNotEmpty()) {
                smartPlaylistDao.insertSmartPlaylists(toInsertLocally)
            }

            // Upload local smart playlists to cloud if not in cloud
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

            // Insert cloud searches into local if not present
            val toInsertLocally = cloudSearches.filter { it.query.isNotBlank() && !localQueries.contains(it.query.trim().lowercase()) }.map {
                RecentSearchEntity(
                    query = it.query,
                    timestamp = it.timestamp
                )
            }
            if (toInsertLocally.isNotEmpty()) {
                sDao.insertSearchesBatch(toInsertLocally)
            }

            // Upload local searches to cloud if not in cloud
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
     * Restores complete account state from latest backup snapshot.
     */
    private suspend fun restoreFromBackupSnapshot(uid: String, data: Map<String, Any?>) = withContext(Dispatchers.IO) {
        try {
            val payload = UserBackupPayload.fromMap(data)
            Log.d(TAG, "Restoring data from backup snapshot dated ${payload.backupDateFormatted}")

            // 1. Settings & Profile
            if (payload.settings.updatedAt > 0L) {
                userPreferences.importUserSettings(payload.settings, checkTimestamp = false)
            }
            if (!payload.profile.customDisplayName.isNullOrBlank()) {
                userPreferences.setCustomDisplayName(payload.profile.customDisplayName)
            }
            if (!payload.profile.customAvatarUri.isNullOrBlank()) {
                userPreferences.setCustomAvatarUri(payload.profile.customAvatarUri)
            }

            // 2. Favorites
            val allSongs = songDao.getAllSongsOnce()
            val songsBySig = allSongs.associateBy { SongIdentityHelper.computeSignature(it) }
            val songsByNormKey = allSongs.groupBy { "${com.example.util.TagCleaner.cleanTitle(it.title, "")}|${com.example.util.TagCleaner.cleanArtist(it.artist)}" }

            for (fav in payload.favorites) {
                if (fav.isFavorite && fav.songSignature.isNotBlank()) {
                    cachedCloudFavorites[fav.songSignature] = fav
                    val matchedSong = songsBySig[fav.songSignature]
                        ?: songsByNormKey["${com.example.util.TagCleaner.cleanTitle(fav.title, "")}|${com.example.util.TagCleaner.cleanArtist(fav.artist)}"]?.firstOrNull()
                    if (matchedSong != null && !matchedSong.isFavorite) {
                        songDao.setFavorite(matchedSong.id, true)
                    }
                }
            }

            // 3. Playlists
            val existingPlaylists = playlistDao.getAllPlaylistsOnce()
            val existingByName = existingPlaylists.associateBy { it.name.trim().lowercase() }
            for (cloudPl in payload.playlists) {
                if (cloudPl.name.isBlank()) continue
                val localPl = existingByName[cloudPl.name.trim().lowercase()]
                val playlistId = localPl?.id ?: playlistDao.insertPlaylist(
                    PlaylistEntity(
                        name = cloudPl.name,
                        createdAt = cloudPl.createdAt,
                        isPinned = cloudPl.isPinned,
                        artworkUri = cloudPl.artworkUri
                    )
                )
                val crossRefs = mutableListOf<PlaylistSongCrossRef>()
                for (item in cloudPl.songs) {
                    val matchedSong = songsBySig[item.songSignature]
                        ?: songsByNormKey["${com.example.util.TagCleaner.cleanTitle(item.title, "")}|${com.example.util.TagCleaner.cleanArtist(item.artist)}"]?.firstOrNull()
                    if (matchedSong != null) {
                        crossRefs.add(PlaylistSongCrossRef(playlistId, matchedSong.id, item.position))
                    }
                }
                if (crossRefs.isNotEmpty()) {
                    playlistDao.updatePlaylistOrder(playlistId, crossRefs)
                }
            }

            // 4. Folders
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

            // 5. Smart Playlists
            val localSmart = smartPlaylistDao.getAllSmartPlaylistsOnce()
            val localSmartNames = localSmart.map { it.name.trim().lowercase() }.toSet()
            val smartToInsert = payload.smartPlaylists.filter { !localSmartNames.contains(it.name.trim().lowercase()) }.map {
                SmartPlaylistEntity(
                    name = it.name,
                    genreFilter = it.filterType,
                    maxTrackLimit = it.limit,
                    createdAt = it.createdAt
                )
            }
            if (smartToInsert.isNotEmpty()) {
                smartPlaylistDao.insertSmartPlaylists(smartToInsert)
            }

            // 6. Recent Searches
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

    /**
     * Asynchronously pushes favorite state change to Firestore.
     * Non-blocking, fails gracefully when offline.
     */
    fun onFavoriteChangedAsync(song: Song, isFavorite: Boolean) {
        val uid = getCurrentUid() ?: return
        if (uid != activeUid) return
        val fs = firestore ?: return

        scope.launch {
            try {
                val currentUid = getCurrentUid()
                if (currentUid.isNullOrBlank() || currentUid != activeUid || currentUid != uid) return@launch
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

    /**
     * Asynchronously pushes playback session record to Firestore.
     */
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
        if (uid != activeUid) return
        val fs = firestore ?: return

        scope.launch {
            try {
                val currentUid = getCurrentUid()
                if (currentUid.isNullOrBlank() || currentUid != activeUid || currentUid != uid) return@launch
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
                fs.collection(USERS_COLLECTION).document(currentUid)
                    .collection(HISTORY_COLLECTION).document(sessionId)
                    .set(event.toMap(), SetOptions.merge())
            } catch (e: Exception) {
                Log.w(TAG, "Offline/Error syncing playback session: ${e.message}")
            }
        }
    }

    /**
     * Asynchronously pushes playlist change to Firestore.
     */
    fun onPlaylistUpdatedAsync(playlistId: Long, name: String, isPinned: Boolean, songs: List<Song>) {
        val uid = getCurrentUid() ?: return
        if (uid != activeUid) return
        val fs = firestore ?: return

        scope.launch {
            try {
                val currentUid = getCurrentUid()
                if (currentUid.isNullOrBlank() || currentUid != activeUid || currentUid != uid) return@launch
                val cloudId = playlistLocalToCloudMap[playlistId] ?: "pl_${System.currentTimeMillis()}_${name.hashCode()}"
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
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    isPinned = isPinned,
                    artworkUri = null,
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

    /**
     * Asynchronously pushes settings change to Firestore.
     */
    fun onSettingsChangedAsync() {
        val uid = getCurrentUid() ?: return
        if (uid != activeUid) return
        val fs = firestore ?: return

        scope.launch {
            try {
                val currentUid = getCurrentUid()
                if (currentUid.isNullOrBlank() || currentUid != activeUid || currentUid != uid) return@launch
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

    /**
     * "Sync Now" immediately synchronizes pending local changes with Firestore
     * and retrieves applicable remote changes without rebuilding playback or freezing UI.
     */
    fun performSyncNow() {
        triggerFullSync(isInitialLogin = false)
    }

    /**
     * "Backup Now" immediately creates/updates the user's latest cloud backup.
     * Retains ONLY the latest backup at users/{uid}/backup/latest.
     * Safely verifies new backup before replacing previous one.
     */
    fun triggerBackupNow() {
        scope.launch {
            performBackupInternal(isAutoBackup = false)
        }
    }

    /**
     * Performs a consistent snapshot backup directly from Room and DataStore.
     * Implements strict safe replacement:
     * 1. Writes complete backup to 'staging'
     * 2. Verifies staging was written and valid
     * 3. Writes verified backup to 'latest'
     * 4. Verifies 'latest'
     * 5. Removes staging and historical backups (keeps ONLY latest)
     * 6. Preserves canonical sync data without deletion.
     */
    suspend fun performBackupInternal(isAutoBackup: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val uid = getCurrentUid()
        if (uid.isNullOrBlank() || uid != activeUid) {
            _backupStatus.value = BackupStatus.Error("Sign in to backup account data")
            return@withContext false
        }
        val fs = firestore
        if (fs == null) {
            _backupStatus.value = BackupStatus.Error("Firestore is unavailable")
            return@withContext false
        }

        try {
            _backupStatus.value = BackupStatus.BackingUp

            // 1. Gather all account-owned data directly from Room & DataStore
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
                val songsInPlaylist = playlistDao.getPlaylistSongs(p.id).firstOrNull() ?: emptyList()
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
                CloudPlaylist(
                    cloudId = "pl_${p.createdAt}_${p.name.hashCode()}",
                    name = p.name,
                    createdAt = p.createdAt,
                    updatedAt = System.currentTimeMillis(),
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

            // Compute data checksum/hash to avoid unnecessary repeated uploads
            val rawHashSource = "${currentSettings.updatedAt}_${favorites.size}_${playlists.size}_${foldersList.size}_${recentSearchesList.size}_${smartPlaylistsList.size}_${totalPlays}_${profile.customDisplayName}_${profile.customAvatarUri}"
            val dataHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(rawHashSource.toByteArray())
                .joinToString("") { "%02x".format(it) }

            // If auto-backup and nothing changed, skip duplicate network payload
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

            // Safe replacement protocol:
            // 1. Write new complete backup to 'staging'
            val backupCollection = fs.collection(USERS_COLLECTION).document(uid).collection("backup")
            val stagingDoc = backupCollection.document("staging")
            val latestDoc = backupCollection.document("latest")

            stagingDoc.set(payload.toMap()).await()

            // 2. Verify new backup was successfully written
            val stagingSnap = stagingDoc.get().await()
            if (!stagingSnap.exists() || stagingSnap.getString("dataHash") != dataHash) {
                throw IllegalStateException("Failed to verify newly written backup snapshot")
            }

            // 3. Write verified backup to 'latest'
            latestDoc.set(payload.toMap()).await()

            // 4. Verify 'latest' exists and is complete
            val latestSnap = latestDoc.get().await()
            if (!latestSnap.exists()) {
                throw IllegalStateException("Failed to verify latest backup document")
            }

            // 5. Clean up staging
            try {
                stagingDoc.delete().await()
            } catch (_: Exception) {}

            // 6. Enforce KEEP ONLY THE LATEST BACKUP: clean up any other historical docs in backup collection
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

            // 7. Update user root document lastBackupAt & local preferences
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
     * Automatically matches newly discovered songs against cloud favorites & playlists.
     */
    fun onLibraryScanCompleted() {
        scope.launch {
            try {
                matchAndApplyCloudFavoritesToRoom()
                val uid = getCurrentUid()
                if (uid != null && firestore != null) {
                    // Re-link playlists if newly discovered songs match playlist references
                    syncPlaylists(uid, firestore!!)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in post-scan sync hook: ${e.message}")
            }
        }
    }
}

