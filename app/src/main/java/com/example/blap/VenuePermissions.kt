package com.example.blap
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object VenuePermissions {
    private val REQUIRED = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun missing(context: Context): List<String> = REQUIRED.filter {
        permission -> ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }
    fun displayName(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location for venue check-in"
        else -> permission.substringAfterLast('.')
    }
}