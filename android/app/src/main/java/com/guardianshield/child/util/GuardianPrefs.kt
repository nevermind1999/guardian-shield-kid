package com.guardianshield.child.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Ponto único de leitura do SharedPreferences "GuardianShieldPrefs" — o mesmo arquivo que
 * o app Capacitor (MainActivity/PauseModule) grava a partir do estado sincronizado por
 * Socket.IO. A Home/Gaveta nativas (LauncherHomeActivity/LauncherDrawerActivity) e o
 * ParentalAccessibilityService leem daqui, então mudanças de chave só precisam ser feitas
 * neste arquivo.
 */
object GuardianPrefs {
    private const val PREFS_NAME = "GuardianShieldPrefs"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPauseAllActive(context: Context): Boolean =
        of(context).getBoolean("isPauseAllActive", false)

    fun blockedPackages(context: Context): Set<String> =
        of(context).getStringSet("blockedPackagesSet", emptySet()) ?: emptySet()

    fun dailyLimitMinutes(context: Context): Int =
        of(context).getInt("dailyLimitMinutes", 120)

    fun usedMinutesToday(context: Context): Int =
        of(context).getInt("usedMinutesToday", 0)
}
