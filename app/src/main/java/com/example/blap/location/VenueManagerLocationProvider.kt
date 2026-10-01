package com.example.blap.location

import android.content.Context
import com.example.blap.venue.VenueManager

/** Adapter for the existing GPS implementation, originally contributed by Karthik. */
class VenueManagerLocationProvider(context: Context) : LocationProvider {
    private val appContext = context.applicationContext

    override suspend fun getFreshLocation(): LocationFix? =
        VenueManager.getFreshLocation(appContext)?.let { location ->
            LocationFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMetres = location.accuracy.toDouble(),
                capturedAt = location.time,
            )
        }
}
