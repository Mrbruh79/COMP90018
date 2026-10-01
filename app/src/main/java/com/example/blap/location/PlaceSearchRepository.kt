package com.example.blap.location

data class PlaceSearchResult(
    val displayName: String,
    val coordinates: GeoCoordinates,
)

interface PlaceSearchRepository {
    suspend fun search(query: String): List<PlaceSearchResult>
}
