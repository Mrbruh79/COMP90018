package com.example.blap.venue

import android.content.Context

interface VenueRepository {
    /** Caller obtains location permission first. Null means no matching venue. */
    suspend fun findNearbyVenue(): Venue?
}

/** Keeps the current cloud lookup and radius matching in use during the refactor. */
class VenueManagerRepository(context: Context) : VenueRepository {
    private val appContext = context.applicationContext

    override suspend fun findNearbyVenue(): Venue? = VenueManager.findNearbyVenue(appContext)
}
