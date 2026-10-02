package com.example.blap.platform

import android.content.Context
import com.example.blap.DeviceContactsReader
import com.example.blap.application.DeviceContactsSource

class AndroidDeviceContactsSource(context: Context) : DeviceContactsSource {
    private val appContext = context.applicationContext
    override suspend fun read() = DeviceContactsReader.read(appContext)
}
