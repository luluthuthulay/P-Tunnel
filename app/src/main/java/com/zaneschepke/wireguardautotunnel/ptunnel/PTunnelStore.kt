package com.zaneschepke.wireguardautotunnel.ptunnel

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zaneschepke.wireguardautotunnel.data.DataStoreManager

/**
 * P-Tunnel persistence, following WG Tunnel's existing DataStore pattern
 * ([DataStoreManager] + Preferences keys) instead of inventing a new store.
 *
 * - Base conf: stored in app-private DataStore, exactly like Phoenix stores
 *   its baseConf. It contains key material, so it is never logged and never
 *   shown in the UI; only its presence/absence is observable.
 * - Endpoints: the raw feed JSON, stored in file order so the JSON order
 *   survives process death. Parsed on read by [PTunnelFeedService.parse].
 * - Connected endpoint: "ip:port" of the actually connected endpoint, or
 *   null when disconnected. Cleared on every disconnect so a stale endpoint
 *   is never shown as connected.
 */
class PTunnelStore(
    context: Context,
    private val dataStoreManager: DataStoreManager,
) {
    /** Application context, for the feed/conf fetches and diagnostics. */
    val appContext: Context = context.applicationContext

    suspend fun getBaseConf(): String? = dataStoreManager.getFromStore(KEY_BASE_CONF)

    suspend fun saveBaseConf(confText: String) {
        dataStoreManager.saveToDataStore(KEY_BASE_CONF, confText)
    }

    suspend fun getEndpointsJson(): String? = dataStoreManager.getFromStore(KEY_ENDPOINTS_JSON)

    suspend fun saveEndpointsJson(json: String) {
        dataStoreManager.saveToDataStore(KEY_ENDPOINTS_JSON, json)
    }

    suspend fun getConnectedEndpoint(): String? =
        dataStoreManager.getFromStore(KEY_CONNECTED_ENDPOINT)

    suspend fun saveConnectedEndpoint(endpointId: String) {
        dataStoreManager.saveToDataStore(KEY_CONNECTED_ENDPOINT, endpointId)
    }

    suspend fun clearConnectedEndpoint() {
        dataStoreManager.removeFromDataStore(KEY_CONNECTED_ENDPOINT)
    }

    companion object {
        private val KEY_BASE_CONF = stringPreferencesKey("PTUNNEL_BASE_CONF")
        private val KEY_ENDPOINTS_JSON = stringPreferencesKey("PTUNNEL_ENDPOINTS_JSON")
        private val KEY_CONNECTED_ENDPOINT = stringPreferencesKey("PTUNNEL_CONNECTED_ENDPOINT")
    }
}
