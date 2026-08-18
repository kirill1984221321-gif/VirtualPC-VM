package com.virtualpcvm;

import android.content.Context;

/** Own application preferences. Legacy MainSettingsManager is not used here. */
public final class VirtualPcSettings {
    private static final String PREFS = "virtualpcvm.settings";
    private static final String THEME = "theme";
    private static final String DEBUG = "debug";
    private static final String CONFIRM_STOP = "confirm_stop";

    private VirtualPcSettings() {}

    public static String getTheme(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(THEME, "system");
    }

    public static void setTheme(Context context, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(THEME, value).apply();
    }

    public static boolean isDebug(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DEBUG, false);
    }

    public static void setDebug(Context context, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DEBUG, value).apply();
    }

    public static boolean confirmStop(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONFIRM_STOP, true);
    }

    public static void setConfirmStop(Context context, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CONFIRM_STOP, value).apply();
    }
}
