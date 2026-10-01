package com.example.blap.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object LocationDistanceCalculator {
    private const val EARTH_RADIUS_METRES = 6_371_000.0

    /** Invalid coordinates are treated as unreachable instead of propagating NaN through access checks. */
    fun distanceMetres(from: GeoCoordinates, to: GeoCoordinates): Double {
        if (!from.isValid || !to.isValid) return Double.POSITIVE_INFINITY
        val deltaLatitude = Math.toRadians(to.latitude - from.latitude)
        val deltaLongitude = Math.toRadians(to.longitude - from.longitude)
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val haversine = (sin(deltaLatitude / 2).let { it * it } +
            cos(fromLatitude) * cos(toLatitude) *
            sin(deltaLongitude / 2).let { it * it }).coerceIn(0.0, 1.0)
        val centralAngle = 2 * atan2(sqrt(haversine), sqrt(1 - haversine))
        return EARTH_RADIUS_METRES * centralAngle
    }
}

object LocationQuality {
    fun hasTrustedAccuracy(accuracyMetres: Double, maximumMetres: Double): Boolean =
        accuracyMetres.isFinite() && maximumMetres.isFinite() &&
            accuracyMetres > 0.0 && maximumMetres > 0.0 && accuracyMetres <= maximumMetres
}
