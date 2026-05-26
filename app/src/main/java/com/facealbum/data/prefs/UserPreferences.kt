package com.facealbum.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.facealbum.config.FaceRecognitionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Durable user preferences (clustering threshold, merge threshold, minimum
 * cluster size). Defaults come from [FaceRecognitionConfig].
 *
 * The single `instance` companion ensures the underlying DataStore file is
 * only opened once per process — opening twice throws.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "face_album_prefs")

class UserPreferences private constructor(private val appContext: Context) {

    private object Keys {
        val ASSIGN_THRESHOLD = floatPreferencesKey("assign_threshold")
        val MERGE_THRESHOLD = floatPreferencesKey("merge_threshold")
        val MIN_CLUSTER_SIZE = intPreferencesKey("min_cluster_size")
    }

    val assignThreshold: Flow<Float> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.ASSIGN_THRESHOLD]?.coerceIn(MIN_ASSIGN, MAX_ASSIGN)
            ?: FaceRecognitionConfig.CLUSTER_ASSIGN_THRESHOLD
    }

    val mergeThreshold: Flow<Float> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.MERGE_THRESHOLD]?.coerceIn(MIN_MERGE, MAX_MERGE)
            ?: FaceRecognitionConfig.CLUSTER_MERGE_THRESHOLD
    }

    val minClusterSize: Flow<Int> = appContext.dataStore.data.map { prefs ->
        prefs[Keys.MIN_CLUSTER_SIZE]?.coerceAtLeast(1)
            ?: FaceRecognitionConfig.DEFAULT_MIN_CLUSTER_SIZE
    }

    suspend fun setAssignThreshold(value: Float) {
        appContext.dataStore.edit { it[Keys.ASSIGN_THRESHOLD] = value.coerceIn(MIN_ASSIGN, MAX_ASSIGN) }
    }

    suspend fun setMergeThreshold(value: Float) {
        appContext.dataStore.edit { it[Keys.MERGE_THRESHOLD] = value.coerceIn(MIN_MERGE, MAX_MERGE) }
    }

    suspend fun setMinClusterSize(value: Int) {
        appContext.dataStore.edit { it[Keys.MIN_CLUSTER_SIZE] = value.coerceAtLeast(1) }
    }

    companion object {
        const val MIN_ASSIGN = 0.30f
        const val MAX_ASSIGN = 0.90f
        const val MIN_MERGE = 0.50f
        const val MAX_MERGE = 0.95f

        @Volatile private var instance: UserPreferences? = null

        fun get(context: Context): UserPreferences =
            instance ?: synchronized(this) {
                instance ?: UserPreferences(context.applicationContext).also { instance = it }
            }
    }
}
