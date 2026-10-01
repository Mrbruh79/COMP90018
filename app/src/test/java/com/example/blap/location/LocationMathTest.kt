package com.example.blap.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationMathTest {
    @Test
    fun identicalCoordinatesHaveZeroDistance() {
        val melbourne = GeoCoordinates(-37.8136, 144.9631)

        assertEquals(0.0, LocationDistanceCalculator.distanceMetres(melbourne, melbourne), 0.001)
    }

    @Test
    fun distanceUsesEarthSurfaceCoordinates() {
        val flindersStreet = GeoCoordinates(-37.8183, 144.9671)
        val mcg = GeoCoordinates(-37.8199, 144.9834)

        val distance = LocationDistanceCalculator.distanceMetres(flindersStreet, mcg)

        assertTrue(distance in 1_300.0..1_600.0)
    }

    @Test
    fun invalidCoordinatesAreUnreachable() {
        val invalid = GeoCoordinates(95.0, 144.0)
        val valid = GeoCoordinates(-37.8136, 144.9631)

        assertEquals(
            Double.POSITIVE_INFINITY,
            LocationDistanceCalculator.distanceMetres(invalid, valid),
            0.0,
        )
    }

    @Test
    fun trustedAccuracyRequiresPositiveFiniteValueWithinLimit() {
        assertTrue(LocationQuality.hasTrustedAccuracy(50.0, 50.0))
        assertFalse(LocationQuality.hasTrustedAccuracy(50.1, 50.0))
        assertFalse(LocationQuality.hasTrustedAccuracy(0.0, 50.0))
        assertFalse(LocationQuality.hasTrustedAccuracy(Double.NaN, 50.0))
    }
}
