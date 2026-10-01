package com.example.blap.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

/** Android implementation. Permission checks remain with the Activity/UI that starts the request. */
class FusedLocationProvider(context: Context) : LocationProvider {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission")
    override suspend fun getFreshLocation(): LocationFix? = client
        .getCurrentLocation(
            CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .build(),
            null,
        )
        .await()
        ?.let { location ->
            LocationFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMetres = location.accuracy.toDouble(),
                capturedAt = location.time,
            )
        }
}
