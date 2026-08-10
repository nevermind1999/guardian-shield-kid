package com.guardianshield.child.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import com.guardianshield.child.model.AppEntry

/** Consulta e abre apps reais do aparelho via PackageManager — usado pela Home e pela Gaveta nativas. */
object AppRepository {

    /**
     * Categoria de exibição pro app do pai poder agrupar a lista de apps instalados.
     * FLAG_SYSTEM cobre os apps pré-instalados/OEM (Configurações, Contatos, Câmera,
     * Play Store, apps do fabricante, etc — mesmo depois de atualizados pela Play
     * Store, a flag original permanece). ApplicationInfo.category é preenchido pela
     * Play Store a partir do android:appCategory que o próprio app declara — maioria
     * dos jogos populares declara "game". Sem categoria clara, cai em "Aplicativos".
     */
    private fun categoryOf(appInfo: ApplicationInfo): String = when {
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 -> "Sistema"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appInfo.category == ApplicationInfo.CATEGORY_GAME -> "Jogos"
        else -> "Aplicativos"
    }

    fun loadLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val selfPackage = context.packageName

        return pm.queryIntentActivities(mainIntent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != selfPackage }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                AppEntry(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm),
                    category = categoryOf(info.activityInfo.applicationInfo)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun launch(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
