package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.SmartPlaylist
import com.example.data.sync.CloudSmartPlaylist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Extended rule & ordering metadata for a [SmartPlaylist] stored outside Room's fixed v12 schema
 * and serialized inside [CloudSmartPlaylist.rulesJson] for full restart & cloud backup persistence.
 */
data class SmartPlaylistMetadata(
    val minPlayCount: Int = 0,
    val timeframeDays: Int = 0,
    val artistFilter: String = "ALL",
    val favoritesOnly: Boolean = false,
    val durationFilter: String = "ANY", // ANY, SHORT (<3m), MEDIUM (3-5m), LONG (>5m)
    val unplayedOnly: Boolean = false,
    val customTrackOrder: List<String> = emptyList(), // Ordered SongIdentityHelper signatures
    val displayOrder: Int = Int.MAX_VALUE
) {
    fun toRulesJson(): String {
        val obj = JSONObject()
        obj.put("v", 2)
        obj.put("minPlayCount", minPlayCount)
        obj.put("timeframeDays", timeframeDays)
        obj.put("artistFilter", artistFilter)
        obj.put("favoritesOnly", favoritesOnly)
        obj.put("durationFilter", durationFilter)
        obj.put("unplayedOnly", unplayedOnly)
        if (displayOrder != Int.MAX_VALUE) {
            obj.put("displayOrder", displayOrder)
        }
        val orderArr = JSONArray()
        customTrackOrder.forEach { sig ->
            if (sig.isNotBlank()) orderArr.put(sig)
        }
        obj.put("customTrackOrder", orderArr)
        return obj.toString()
    }

    companion object {
        fun fromRulesJson(raw: String?): SmartPlaylistMetadata {
            if (raw.isNullOrBlank() || raw.trim() == "{}") return SmartPlaylistMetadata()
            return try {
                val obj = JSONObject(raw)
                val orderArr = obj.optJSONArray("customTrackOrder")
                val orderList = mutableListOf<String>()
                if (orderArr != null) {
                    for (i in 0 until orderArr.length()) {
                        val s = orderArr.optString(i, "")
                        if (s.isNotBlank()) orderList.add(s)
                    }
                }
                val parsedDisplayOrder = if (obj.has("displayOrder")) {
                    obj.optInt("displayOrder", Int.MAX_VALUE)
                } else {
                    Int.MAX_VALUE
                }
                SmartPlaylistMetadata(
                    minPlayCount = obj.optInt("minPlayCount", 0).coerceAtLeast(0),
                    timeframeDays = obj.optInt("timeframeDays", 0).coerceAtLeast(0),
                    artistFilter = obj.optString("artistFilter", "ALL").ifBlank { "ALL" },
                    favoritesOnly = obj.optBoolean("favoritesOnly", false),
                    durationFilter = obj.optString("durationFilter", "ANY").ifBlank { "ANY" },
                    unplayedOnly = obj.optBoolean("unplayedOnly", false),
                    customTrackOrder = orderList,
                    displayOrder = parsedDisplayOrder
                )
            } catch (_: Exception) {
                SmartPlaylistMetadata()
            }
        }

        fun fromSmartPlaylist(sp: SmartPlaylist): SmartPlaylistMetadata = SmartPlaylistMetadata(
            minPlayCount = sp.minPlayCount,
            timeframeDays = sp.timeframeDays,
            artistFilter = sp.artistFilter,
            favoritesOnly = sp.favoritesOnly,
            durationFilter = sp.durationFilter,
            unplayedOnly = sp.unplayedOnly,
            customTrackOrder = sp.customTrackOrder,
            displayOrder = sp.displayOrder
        )
    }
}

/**
 * Process-wide singleton store for [SmartPlaylistMetadata], backed by SharedPreferences and
 * exposed as a reactive [StateFlow] so both [MusicRepository] and [CloudSyncRepository] share
 * instant local persistence and seamless cloud backup/restore parity.
 */
class SmartPlaylistMetadataStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _metadataMap = MutableStateFlow<Map<String, SmartPlaylistMetadata>>(loadAllFromPrefs())
    val metadataMap: StateFlow<Map<String, SmartPlaylistMetadata>> = _metadataMap.asStateFlow()

    private fun loadAllFromPrefs(): Map<String, SmartPlaylistMetadata> {
        val result = LinkedHashMap<String, SmartPlaylistMetadata>()
        try {
            for ((k, v) in prefs.all) {
                val json = v as? String ?: continue
                if (k.startsWith(KEY_PREFIX)) {
                    val stableKey = k.removePrefix(KEY_PREFIX)
                    result[stableKey] = SmartPlaylistMetadata.fromRulesJson(json)
                }
            }
        } catch (_: Exception) {}
        return result
    }

    fun getMetadata(name: String, createdAt: Long): SmartPlaylistMetadata {
        val map = _metadataMap.value
        val exactKey = stableKey(name, createdAt)
        map[exactKey]?.let { return it }
        val namePrefix = "${name.trim().lowercase()}|"
        return map.entries.firstOrNull { it.key.startsWith(namePrefix) }?.value
            ?: SmartPlaylistMetadata()
    }

    fun saveMetadata(name: String, createdAt: Long, metadata: SmartPlaylistMetadata) {
        val key = stableKey(name, createdAt)
        val json = metadata.toRulesJson()
        prefs.edit().putString("$KEY_PREFIX$key", json).apply()
        _metadataMap.value = _metadataMap.value.toMutableMap().apply {
            put(key, metadata)
        }
    }

    fun saveFromSmartPlaylist(smartPlaylist: SmartPlaylist) {
        saveMetadata(
            name = smartPlaylist.name,
            createdAt = smartPlaylist.createdAt,
            metadata = SmartPlaylistMetadata.fromSmartPlaylist(smartPlaylist)
        )
    }

    fun saveCustomTrackOrder(name: String, createdAt: Long, orderedSignatures: List<String>) {
        val existing = getMetadata(name, createdAt)
        saveMetadata(name, createdAt, existing.copy(customTrackOrder = orderedSignatures))
    }

    fun clearCustomTrackOrder(name: String, createdAt: Long) {
        val existing = getMetadata(name, createdAt)
        saveMetadata(name, createdAt, existing.copy(customTrackOrder = emptyList()))
    }

    fun saveDisplayOrders(orderedPlaylists: List<SmartPlaylist>) {
        val editor = prefs.edit()
        val updatedMap = _metadataMap.value.toMutableMap()
        orderedPlaylists.forEachIndexed { index, sp ->
            val key = stableKey(sp.name, sp.createdAt)
            val existing = updatedMap[key] ?: SmartPlaylistMetadata.fromSmartPlaylist(sp)
            val updated = existing.copy(displayOrder = index)
            updatedMap[key] = updated
            editor.putString("$KEY_PREFIX$key", updated.toRulesJson())
        }
        editor.apply()
        _metadataMap.value = updatedMap
    }

    fun renameKeyIfNeeded(oldName: String, oldCreatedAt: Long, newSmartPlaylist: SmartPlaylist) {
        val oldKey = stableKey(oldName, oldCreatedAt)
        val newKey = stableKey(newSmartPlaylist.name, newSmartPlaylist.createdAt)
        val newMeta = SmartPlaylistMetadata.fromSmartPlaylist(newSmartPlaylist)
        val editor = prefs.edit()
        val updatedMap = _metadataMap.value.toMutableMap()
        if (oldKey != newKey) {
            editor.remove("$KEY_PREFIX$oldKey")
            updatedMap.remove(oldKey)
        }
        editor.putString("$KEY_PREFIX$newKey", newMeta.toRulesJson())
        editor.apply()
        updatedMap[newKey] = newMeta
        _metadataMap.value = updatedMap
    }

    fun removeMetadata(name: String, createdAt: Long) {
        val key = stableKey(name, createdAt)
        prefs.edit().remove("$KEY_PREFIX$key").apply()
        _metadataMap.value = _metadataMap.value.toMutableMap().apply {
            remove(key)
        }
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        _metadataMap.value = emptyMap()
    }

    fun applyMetadata(entity: SmartPlaylistEntity, metadataSnapshot: Map<String, SmartPlaylistMetadata> = _metadataMap.value): SmartPlaylist {
        val base = entity.toDomainModel()
        val exactKey = stableKey(base.name, base.createdAt)
        val meta = metadataSnapshot[exactKey]
            ?: metadataSnapshot.entries.firstOrNull { it.key.startsWith("${base.name.trim().lowercase()}|") }?.value
            ?: return base
        return base.copy(
            minPlayCount = if (base.minPlayCount > 0) base.minPlayCount else meta.minPlayCount,
            timeframeDays = if (base.timeframeDays > 0) base.timeframeDays else meta.timeframeDays,
            artistFilter = meta.artistFilter,
            favoritesOnly = meta.favoritesOnly,
            durationFilter = meta.durationFilter,
            unplayedOnly = meta.unplayedOnly,
            customTrackOrder = meta.customTrackOrder,
            displayOrder = meta.displayOrder
        )
    }

    fun toCloudSmartPlaylist(entity: SmartPlaylistEntity): CloudSmartPlaylist {
        val merged = applyMetadata(entity)
        val meta = SmartPlaylistMetadata.fromSmartPlaylist(merged)
        return CloudSmartPlaylist(
            name = merged.name,
            filterType = merged.genreFilter,
            rulesJson = meta.toRulesJson(),
            sortOrder = merged.sortCriteria,
            limit = merged.maxTrackLimit,
            createdAt = merged.createdAt
        )
    }

    fun restoreFromCloud(cloudList: List<CloudSmartPlaylist>, replaceAll: Boolean): List<SmartPlaylistEntity> {
        val editor = prefs.edit()
        val updatedMap = if (replaceAll) {
            editor.clear()
            LinkedHashMap()
        } else {
            _metadataMap.value.toMutableMap()
        }

        val entities = cloudList.mapIndexed { idx, cloud ->
            val parsedMeta = SmartPlaylistMetadata.fromRulesJson(cloud.rulesJson)
            val resolvedMeta = if (parsedMeta.displayOrder == Int.MAX_VALUE) {
                parsedMeta.copy(displayOrder = idx)
            } else {
                parsedMeta
            }
            val key = stableKey(cloud.name, cloud.createdAt)
            updatedMap[key] = resolvedMeta
            editor.putString("$KEY_PREFIX$key", resolvedMeta.toRulesJson())

            SmartPlaylistEntity(
                name = cloud.name,
                genreFilter = cloud.filterType.ifBlank { "ALL" },
                minPlayCount = resolvedMeta.minPlayCount,
                timeframeDays = resolvedMeta.timeframeDays,
                maxTrackLimit = cloud.limit,
                sortCriteria = cloud.sortOrder.ifBlank { "RECENTLY_ADDED" },
                createdAt = cloud.createdAt
            )
        }
        editor.apply()
        _metadataMap.value = updatedMap
        return entities
    }

    companion object {
        private const val PREFS_NAME = "smart_playlist_metadata_v1"
        private const val KEY_PREFIX = "sp_meta_"

        fun stableKey(name: String, createdAt: Long): String =
            "${name.trim().lowercase()}|$createdAt"

        @Volatile
        private var INSTANCE: SmartPlaylistMetadataStore? = null

        fun getInstance(context: Context): SmartPlaylistMetadataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SmartPlaylistMetadataStore(context).also { INSTANCE = it }
            }
        }
    }
}
