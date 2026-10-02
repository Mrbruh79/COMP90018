package com.example.blap.location

data class PlaceSearchResult(
    val displayName: String,
    val coordinates: GeoCoordinates,
)

interface PlaceSearchRepository {
    suspend fun search(query: String): List<PlaceSearchResult>

    /** Returns a readable address for coordinates when reverse lookup is available. */
    suspend fun reverse(coordinates: GeoCoordinates): String? = null
}
