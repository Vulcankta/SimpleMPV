package com.simplempv

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log

class SimpleMPVApp : Application() {

    private lateinit var themeManager: ThemeManager

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Apply saved theme
        themeManager = ThemeManager(this)
        themeManager.applyTheme()

        Log.d("SimpleMPVApp", "Application started")
    }

    override fun onLowMemory() {
        super.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
    }

    override fun onTerminate() {
        super.onTerminate()
    }

    companion object {
        lateinit var instance: SimpleMPVApp
            private set
    }
}
