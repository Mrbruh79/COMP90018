package com.example.blap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object NearbyPermissions {
    private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    fun requiredForCurrentDevice(): List<String> = buildList {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            else -> add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= 37) add(ACCESS_LOCAL_NETWORK)
    }

    fun missing(context: Context): List<String> = requiredForCurrentDevice().filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }

    fun displayName(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_SCAN -> "Find nearby devices"
        Manifest.permission.BLUETOOTH_ADVERTISE -> "Be visible over Bluetooth"
        Manifest.permission.BLUETOOTH_CONNECT -> "Connect to nearby devices"
        Manifest.permission.NEARBY_WIFI_DEVICES -> "Connect over nearby Wi-Fi"
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        -> "Location for nearby discovery"
        ACCESS_LOCAL_NETWORK -> "Local network access"
        else -> permission.substringAfterLast('.')
    }
}
