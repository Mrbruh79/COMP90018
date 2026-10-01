package com.example.blap.location

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Rate-limited OpenStreetMap Nominatim search used by the event-location picker. */
class NominatimPlaceSearchRepository(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaceSearchRepository {
    private val requestMutex = Mutex()
    private var lastRequestAt = 0L

    override suspend fun search(query: String): List<PlaceSearchResult> = requestMutex.withLock {
        val normalized = query.trim()
        if (normalized.length < MIN_QUERY_LENGTH) return emptyList()
        val elapsed = SystemClock.elapsedRealtime() - lastRequestAt
        delay(max(0L, MIN_REQUEST_INTERVAL_MS - elapsed))
        lastRequestAt = SystemClock.elapsedRealtime()
        withContext(ioDispatcher) { request(normalized) }
    }

    private fun request(query: String): List<PlaceSearchResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val connection = URL(
            "$SEARCH_ENDPOINT?format=jsonv2&limit=$RESULT_LIMIT&countrycodes=au&q=$encoded",
        ).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) {
                error("OpenStreetMap search returned ${connection.responseCode}")
            }
            val results = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            return buildList {
                for (index in 0 until results.length()) {
                    val item = results.getJSONObject(index)
                    val latitude = item.optString("lat").toDoubleOrNull() ?: continue
                    val longitude = item.optString("lon").toDoubleOrNull() ?: continue
                    val coordinates = GeoCoordinates(latitude, longitude)
                    val name = item.optString("display_name").takeIf(String::isNotBlank) ?: continue
                    if (coordinates.isValid) add(PlaceSearchResult(name.take(200), coordinates))
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 3
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        const val REQUEST_TIMEOUT_MS = 8_000
        const val RESULT_LIMIT = 5
        const val SEARCH_ENDPOINT = "https://nominatim.openstreetmap.org/search"
        const val USER_AGENT = "CommonGround/1.0 (COMP90018 university project)"
    }
}
