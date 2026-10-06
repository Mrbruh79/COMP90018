package com.example.blap

import android.app.Application
import com.example.blap.di.AppContainer
import com.example.blap.di.DefaultAppContainer

class CommonGroundApplication : Application() {
    val appContainer: AppContainer by lazy { DefaultAppContainer(this) }
}
