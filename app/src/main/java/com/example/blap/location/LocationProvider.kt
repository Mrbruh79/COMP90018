package com.example.blap.location

/** Coordinates in degrees, horizontal accuracy in metres, capture time as Unix milliseconds. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Double,
    val capturedAt: Long,
)

interface LocationProvider {
    /** Caller obtains location permission first. Null means no fix; failures propagate. */
    suspend fun getFreshLocation(): LocationFix?
}
