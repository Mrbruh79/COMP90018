package com.example.blap.event

import com.example.blap.location.GeoCoordinates
import com.example.blap.location.LocationDistanceCalculator
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos

enum class EventListSection {
    DISCOVER,
    MY_EVENTS,
}

enum class EventDiscoveryMode {
    UPCOMING,
    NEARBY,
    CITY,
    SEARCH,
}

enum class EventDateFilter {
    ANY_UPCOMING,
    TODAY,
    NEXT_7_DAYS,
}

enum class EventAccessFilter {
    ALL,
    OPEN,
    SIGN_IN_REQUIRED,
}

data class EventDiscoveryRequest(
    val mode: EventDiscoveryMode = EventDiscoveryMode.UPCOMING,
    val searchQuery: String = "",
    val cityQuery: String = "",
    val centre: GeoCoordinates? = null,
    val distanceKm: Int = DEFAULT_DISCOVERY_DISTANCE_KM,
    val dateFilter: EventDateFilter = EventDateFilter.ANY_UPCOMING,
    val accessFilter: EventAccessFilter = EventAccessFilter.ALL,
    val limit: Int = DISCOVERY_PAGE_SIZE,
    val now: Long = System.currentTimeMillis(),
)

data class EventDiscoveryResult(
    val events: List<CommunityEvent>,
    val hasMore: Boolean,
)

object EventDiscoveryPolicy {
    fun filterAndSort(
        events: Collection<CommunityEvent>,
        request: EventDiscoveryRequest,
    ): List<CommunityEvent> {
        val filtered = events.asSequence()
            .filter { event -> event.visibility == EventVisibility.PUBLIC && !event.isDeleted }
            .filter { event -> event.endsAt >= request.now }
            .filter { event -> matchesDate(event, request) }
            .filter { event -> matchesAccess(event, request.accessFilter) }
            .filter { event -> matchesMode(event, request) }
            .distinctBy(CommunityEvent::id)
            .toList()
        return if (request.mode == EventDiscoveryMode.NEARBY && request.centre != null) {
            filtered.sortedWith(
                compareBy<CommunityEvent> { event -> distanceMetres(event, request.centre) }
                    .thenBy(CommunityEvent::startsAt),
            )
        } else {
            filtered.sortedBy(CommunityEvent::startsAt)
        }.take(request.limit.coerceIn(1, MAX_DISCOVERY_RESULTS))
    }

    fun distanceMetres(event: CommunityEvent, centre: GeoCoordinates): Double =
        LocationDistanceCalculator.distanceMetres(
            centre,
            GeoCoordinates(event.latitude, event.longitude),
        )

    private fun matchesDate(event: CommunityEvent, request: EventDiscoveryRequest): Boolean {
        val latestStart = when (request.dateFilter) {
            EventDateFilter.ANY_UPCOMING -> Long.MAX_VALUE
            EventDateFilter.TODAY -> Instant.ofEpochMilli(request.now)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli() - 1
            EventDateFilter.NEXT_7_DAYS -> request.now + 7L * 24L * 60L * 60L * 1_000L
        }
        return event.startsAt <= latestStart
    }

    private fun matchesAccess(event: CommunityEvent, filter: EventAccessFilter): Boolean = when (filter) {
        EventAccessFilter.ALL -> true
        EventAccessFilter.OPEN -> !event.requiresSignIn
        EventAccessFilter.SIGN_IN_REQUIRED -> event.requiresSignIn
    }

    private fun matchesMode(event: CommunityEvent, request: EventDiscoveryRequest): Boolean = when (request.mode) {
        EventDiscoveryMode.UPCOMING -> true
        EventDiscoveryMode.SEARCH -> EventSearchIndex.matches(event, request.searchQuery)
        EventDiscoveryMode.CITY -> EventSearchIndex.normalized(event.venueName)
            .contains(EventSearchIndex.normalized(request.cityQuery))
        EventDiscoveryMode.NEARBY -> request.centre?.let { centre ->
            distanceMetres(event, centre) <= request.distanceKm.coerceIn(1, 100) * 1_000.0
        } == true
    }
}

object EventSearchIndex {
    private val nonAlphaNumeric = Regex("[^a-z0-9]+")

    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase()
        .replace(nonAlphaNumeric, " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun tokensFor(event: CommunityEvent): List<String> {
        val phrases = listOf(event.title, event.venueName)
            .map(::normalized)
            .filter(String::isNotBlank)
        val sources = (phrases + phrases.flatMap { it.split(' ') }).distinct()
        return sources.flatMap { source ->
            val maximum = source.length.coerceAtMost(MAX_SEARCH_TOKEN_LENGTH)
            if (maximum < MIN_SEARCH_TOKEN_LENGTH) emptyList()
            else (MIN_SEARCH_TOKEN_LENGTH..maximum).map(source::take)
        }.distinct().take(MAX_SEARCH_TOKENS)
    }

    fun lookupToken(query: String): String? = normalized(query)
        .split(' ')
        .firstOrNull { it.length >= MIN_SEARCH_TOKEN_LENGTH }
        ?.take(MAX_SEARCH_TOKEN_LENGTH)

    fun matches(event: CommunityEvent, query: String): Boolean {
        val terms = normalized(query).split(' ').filter(String::isNotBlank)
        if (terms.isEmpty()) return false
        val haystack = normalized("${event.title} ${event.venueName}")
        return terms.all(haystack::contains)
    }

    private const val MIN_SEARCH_TOKEN_LENGTH = 2
    private const val MAX_SEARCH_TOKEN_LENGTH = 32
    private const val MAX_SEARCH_TOKENS = 200
}

object EventGeoHash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(coordinates: GeoCoordinates, precision: Int = STORED_GEOHASH_PRECISION): String {
        if (!coordinates.isValid) return ""
        var latitudeRange = -90.0 to 90.0
        var longitudeRange = -180.0 to 180.0
        var longitudeBit = true
        var value = 0
        var bitCount = 0
        return buildString {
            while (length < precision) {
                val range = if (longitudeBit) longitudeRange else latitudeRange
                val coordinate = if (longitudeBit) coordinates.longitude else coordinates.latitude
                val midpoint = (range.first + range.second) / 2.0
                value = value shl 1
                if (coordinate >= midpoint) {
                    value = value or 1
                    if (longitudeBit) longitudeRange = midpoint to range.second
                    else latitudeRange = midpoint to range.second
                } else if (longitudeBit) {
                    longitudeRange = range.first to midpoint
                } else {
                    latitudeRange = range.first to midpoint
                }
                longitudeBit = !longitudeBit
                bitCount++
                if (bitCount == 5) {
                    append(BASE32[value])
                    value = 0
                    bitCount = 0
                }
            }
        }
    }

    fun coveringPrefixes(centre: GeoCoordinates, distanceKm: Int): Set<String> {
        if (!centre.isValid) return emptySet()
        val radius = distanceKm.coerceIn(1, 100).toDouble()
        val precision = if (radius <= 10.0) 4 else 3
        val latitudeDelta = radius / 111.32
        val longitudeDelta = radius / (111.32 * cos(Math.toRadians(centre.latitude)).coerceAtLeast(0.1))
        return buildSet {
            for (latitudeStep in -1..1) {
                for (longitudeStep in -1..1) {
                    val sample = GeoCoordinates(
                        latitude = (centre.latitude + latitudeStep * latitudeDelta).coerceIn(-90.0, 90.0),
                        longitude = wrapLongitude(centre.longitude + longitudeStep * longitudeDelta),
                    )
                    add(encode(sample, precision))
                }
            }
        }
    }

    private fun wrapLongitude(value: Double): Double = when {
        value > 180.0 -> value - 360.0
        value < -180.0 -> value + 360.0
        else -> value
    }

    private const val STORED_GEOHASH_PRECISION = 8
}

const val DEFAULT_DISCOVERY_DISTANCE_KM = 25
const val DISCOVERY_PAGE_SIZE = 30
const val MAX_DISCOVERY_RESULTS = 150
