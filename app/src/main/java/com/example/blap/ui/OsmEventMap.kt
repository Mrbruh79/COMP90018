package com.example.blap.ui

import android.graphics.Color as AndroidColor
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

internal data class OsmPoint(val latitude: Double, val longitude: Double)

internal data class OsmSearchResult(
    val displayName: String,
    val point: OsmPoint,
)

@Composable
internal fun OsmEventMap(
    point: OsmPoint?,
    radiusMetres: Double,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    onPointSelected: ((OsmPoint) -> Unit)? = null,
) {
    val latestPointSelected by rememberUpdatedState(onPointSelected)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                Configuration.getInstance().userAgentValue =
                    "CommonGround/1.0 (${context.packageName}; COMP90018 university project)"
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    minZoomLevel = 3.0
                    maxZoomLevel = 20.0
                    controller.setZoom(if (point == null) 11.0 else 16.0)
                    controller.setCenter(point?.toGeoPoint() ?: MELBOURNE_OSM.toGeoPoint())
                    tag = point?.let { "${it.latitude},${it.longitude}" }
                    if (latestPointSelected != null) {
                        overlays += MapEventsOverlay(
                            object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(location: GeoPoint): Boolean {
                                    latestPointSelected?.invoke(location.toOsmPoint())
                                    return true
                                }

                                override fun longPressHelper(location: GeoPoint): Boolean = false
                            },
                        )
                    }
                }
            },
            update = { map ->
                map.overlays.removeAll { it is Marker || it is Polygon }
                point?.let { selected ->
                    val centre = selected.toGeoPoint()
                    map.overlays += Polygon(map).apply {
                        points = Polygon.pointsAsCircle(centre, radiusMetres)
                        fillPaint.color = AndroidColor.argb(38, 63, 81, 181)
                        outlinePaint.color = AndroidColor.rgb(63, 81, 181)
                        outlinePaint.strokeWidth = 3f
                    }
                    map.overlays += Marker(map).apply {
                        position = centre
                        title = "Event location"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    val locationKey = "${selected.latitude},${selected.longitude}"
                    if (map.tag != locationKey) {
                        map.controller.setCenter(centre)
                        map.tag = locationKey
                    }
                    if (map.zoomLevelDouble < 14.0) map.controller.setZoom(16.0)
                }
                map.invalidate()
            },
            onRelease = MapView::onDetach,
        )
        Text(
            "© OpenStreetMap contributors",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color.White.copy(alpha = 0.88f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
            color = Color.Black,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

internal object OsmPlaceSearch {
    private val requestMutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun search(query: String): List<OsmSearchResult> = requestMutex.withLock {
        val elapsed = SystemClock.elapsedRealtime() - lastRequestAt
        delay(max(0L, 1_000L - elapsed))
        lastRequestAt = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
            val connection = URL(
                "https://nominatim.openstreetmap.org/search" +
                    "?format=jsonv2&limit=5&countrycodes=au&q=$encoded",
            ).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty(
                    "User-Agent",
                    "CommonGround/1.0 (COMP90018 university project)",
                )
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) {
                    error("OpenStreetMap search returned ${connection.responseCode}")
                }
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val results = JSONArray(json)
                buildList {
                    for (index in 0 until results.length()) {
                        val item = results.getJSONObject(index)
                        val latitude = item.optString("lat").toDoubleOrNull() ?: continue
                        val longitude = item.optString("lon").toDoubleOrNull() ?: continue
                        val name = item.optString("display_name").takeIf(String::isNotBlank) ?: continue
                        add(OsmSearchResult(name.take(200), OsmPoint(latitude, longitude)))
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}

private fun OsmPoint.toGeoPoint() = GeoPoint(latitude, longitude)
private fun GeoPoint.toOsmPoint() = OsmPoint(latitude, longitude)

private val MELBOURNE_OSM = OsmPoint(-37.8136, 144.9631)
