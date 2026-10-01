package com.example.blap.location

/** Platform-neutral latitude and longitude in degrees. */
data class GeoCoordinates(
    val latitude: Double,
    val longitude: Double,
) {
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/** Coordinates, horizontal accuracy in metres and capture time as Unix milliseconds. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Double,
    val capturedAt: Long,
) {
    val coordinates: GeoCoordinates
        get() = GeoCoordinates(latitude, longitude)
}

interface LocationProvider {
    /** Caller obtains location permission first. Null means no fix; failures propagate. */
    suspend fun getFreshLocation(): LocationFix?
}
