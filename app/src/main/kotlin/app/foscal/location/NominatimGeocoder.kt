package app.foscal.location

import app.foscal.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject

/** A place resolved by [NominatimGeocoder]: its coordinates and a human-readable address. */
data class GeoPlace(
    val latitude: Double,
    val longitude: Double,
    val displayName: String,
)

/**
 * Thin client for OpenStreetMap's Nominatim geocoding service, used only by the opt-in location
 * picker. Both calls send the user's IP and the queried coordinates/text to Nominatim's servers;
 * this is the only part of the app that talks to the network. Nominatim's usage policy requires a
 * descriptive User-Agent and light use — we issue at most one request per user action.
 */
class NominatimGeocoder @Inject constructor() {

    /** Turns a map point into a display address, or null if the lookup fails or has no result. */
    suspend fun reverse(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/reverse?format=jsonv2&addressdetails=0" +
            "&lat=$latitude&lon=$longitude&zoom=18"
        val body = get(url) ?: return@withContext null
        runCatching {
            JSONObject(body).optString("display_name").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Finds the best-matching place for free-text [query], used to center the picker on open. */
    suspend fun search(query: String): GeoPlace? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext null
        val url = "$BASE_URL/search?format=jsonv2&limit=1&q=${URLEncoder.encode(query, "UTF-8")}"
        val body = get(url) ?: return@withContext null
        runCatching {
            val results = JSONArray(body)
            if (results.length() == 0) return@runCatching null
            val first = results.getJSONObject(0)
            GeoPlace(
                latitude = first.getString("lat").toDouble(),
                longitude = first.getString("lon").toDouble(),
                displayName = first.optString("display_name"),
            )
        }.getOrNull()
    }

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // Nominatim blocks requests without a descriptive User-Agent identifying the app.
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            // Any network/parse failure degrades to "no result" — the picker falls back to coords.
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://nominatim.openstreetmap.org"
        const val TIMEOUT_MS = 10_000
        val USER_AGENT = "Foscal/${BuildConfig.VERSION_NAME} (github.com/foscal)"
    }
}
