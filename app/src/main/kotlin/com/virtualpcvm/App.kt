package com.virtualpcvm

import android.app.Application
import android.content.Context
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applySavedLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Material You dynamic theming on Android 12+
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

