package com.guardianshield.child.model

import android.graphics.drawable.Drawable

/** Um app instalado e "abrível" no aparelho, pronto para renderizar na Home/Gaveta nativas. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    // "Sistema" | "Jogos" | "Aplicativos" — ver AppRepository.loadLaunchableApps.
    // Usado pelo app do pai para agrupar a lista de apps instalados por categoria.
    val category: String = "Aplicativos",
    // Epoch millis da 1ª instalação (PackageInfo.firstInstallTime) — só usado pra
    // ordenar a gaveta por "Recentes" (ver LauncherDrawerActivity.toggleSortMode).
    val installedAt: Long = 0L
)
