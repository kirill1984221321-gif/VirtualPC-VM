package com.virtualpcvm

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "virtualpc_prefs"
    private const val KEY_LANG = "app_language"
    private const val KEY_FIRST_LAUNCH_LANG_CHOSEN = "first_launch_lang_chosen"

    const val LANG_RU = "ru"
    const val LANG_EN = "en"

    fun isFirstLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(KEY_FIRST_LAUNCH_LANG_CHOSEN, false)
    }

    fun markFirstLaunchCompleted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_LANG_CHOSEN, true).apply()
    }

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANG, null)
        if (saved != null) return saved

        // Default based on device system language
        val sysLang = Locale.getDefault().language
        return if (sysLang.equals("ru", ignoreCase = true)) LANG_RU else LANG_EN
    }

    fun setLanguage(activity: Activity, lang: String) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LANG, lang)
            .putBoolean(KEY_FIRST_LAUNCH_LANG_CHOSEN, true)
            .apply()

        // Modern AndroidX app-level locale setting
        val appLocale = LocaleListCompat.forLanguageTags(lang)
        AppCompatDelegate.setApplicationLocales(appLocale)

        // Legacy configuration fallback for immediate local resource refresh
        applyLocaleToConfiguration(activity, lang)

        activity.recreate()
    }

    fun applySavedLocale(context: Context): Context {
        val lang = getLanguage(context)
        return applyLocaleToConfiguration(context, lang)
    }

    private fun applyLocaleToConfiguration(context: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }
}
