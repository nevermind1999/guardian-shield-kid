package com.guardianshield.child.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * Ponto único de leitura do SharedPreferences "GuardianShieldPrefs" — o mesmo arquivo que
 * o app Capacitor (MainActivity/PauseModule) grava a partir do estado sincronizado por
 * Socket.IO. A Home/Gaveta nativas (LauncherHomeActivity/LauncherDrawerActivity) e o
 * ParentalAccessibilityService leem daqui, então mudanças de chave só precisam ser feitas
 * neste arquivo.
 */
object GuardianPrefs {
    private const val PREFS_NAME = "GuardianShieldPrefs"

    // Paleta de temas disponível para o usuário escolher em "Personalizar tela inicial".
    // Cada par é [corInicial, corFinal] de um degradê.
    val THEME_PRESETS: List<Pair<String, String>> = listOf(
        "#3A86FF" to "#8338EC", // azul/roxo (padrão)
        "#06D6A0" to "#118AB2", // verde/azul
        "#FF006E" to "#FB5607", // rosa/laranja
        "#FFBE0B" to "#FB5607", // amarelo/laranja
        "#8338EC" to "#FF006E", // roxo/rosa
        "#118AB2" to "#06D6A0", // azul/verde
        "#EF476F" to "#8338EC", // vermelho/roxo
        "#495057" to "#212529"  // cinza monocromático
    )
    private val DEFAULT_THEME = THEME_PRESETS[0]
    val GRID_COLUMN_OPTIONS = listOf(3, 4, 5, 6)
    private const val DEFAULT_GRID_COLUMNS = 4

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

    // --- Apps fixados na tela inicial (grid ajustável, quantos o usuário quiser) ---

    fun pinnedHomeApps(context: Context): Set<String> =
        of(context).getStringSet("pinnedHomeApps", null) ?: emptySet()

    fun setPinnedHomeApps(context: Context, packages: Set<String>) {
        of(context).edit().putStringSet("pinnedHomeApps", packages).apply()
    }

    fun togglePinned(context: Context, packageName: String): Boolean {
        val current = pinnedHomeApps(context).toMutableSet()
        val nowPinned = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        setPinnedHomeApps(context, current)
        return nowPinned
    }

    // --- Colunas da grade da tela inicial (ajustável) ---

    fun homeGridColumns(context: Context): Int =
        of(context).getInt("homeGridColumns", DEFAULT_GRID_COLUMNS)

    fun setHomeGridColumns(context: Context, columns: Int) {
        of(context).edit().putInt("homeGridColumns", columns).apply()
    }

    // --- Cor personalizada do tema do launcher (degradê de destaque) ---

    fun themeColors(context: Context): Pair<Int, Int> {
        val prefs = of(context)
        val startHex = prefs.getString("themeColorStart", DEFAULT_THEME.first) ?: DEFAULT_THEME.first
        val endHex = prefs.getString("themeColorEnd", DEFAULT_THEME.second) ?: DEFAULT_THEME.second
        return try {
            Color.parseColor(startHex) to Color.parseColor(endHex)
        } catch (e: IllegalArgumentException) {
            Color.parseColor(DEFAULT_THEME.first) to Color.parseColor(DEFAULT_THEME.second)
        }
    }

    fun setThemeColors(context: Context, startHex: String, endHex: String) {
        of(context).edit()
            .putString("themeColorStart", startHex)
            .putString("themeColorEnd", endHex)
            .apply()
    }
}
