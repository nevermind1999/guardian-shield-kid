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

    // Cache em memória da lista (com ícones): montar ela do zero lê rótulo + ícone de cada
    // app instalado e levava centenas de ms — antes isso rodava na thread principal toda vez
    // que a Home voltava pra frente, que era a principal causa de travadinha ao apertar
    // Home. Agora só recarrega quando algum app foi instalado/removido/atualizado de verdade
    // (PackageManager.getChangedPackages, Android 8+) ou quando alguém chama invalidate().
    @Volatile private var cache: List<AppEntry>? = null
    @Volatile private var cacheSequence = 0

    /** A lista em cache, se nenhum app mudou desde que ela foi montada — instantâneo, pode
     * chamar na thread principal. null = precisa recarregar (fora da thread principal). */
    fun cachedIfFresh(context: Context): List<AppEntry>? {
        val current = cache ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return current // receivers de pacote chamam invalidate()
        return if (context.packageManager.getChangedPackages(cacheSequence) == null) current else null
    }

    fun invalidate() {
        cache = null
    }

    private fun currentSequence(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.getChangedPackages(0)?.sequenceNumber ?: 0 else 0

    /** Lista dos apps abríveis — do cache se ainda valer, senão monta de novo (lento: fora
     * da thread principal). */
    fun loadLaunchableApps(context: Context): List<AppEntry> {
        cachedIfFresh(context)?.let { return it }
        val sequence = currentSequence(context)
        val loaded = loadFromPackageManager(context)
        cache = loaded
        cacheSequence = sequence
        return loaded
    }

    private fun loadFromPackageManager(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val selfPackage = context.packageName

        return pm.queryIntentActivities(mainIntent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != selfPackage }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                val packageName = info.activityInfo.packageName
                val installedAt = try {
                    pm.getPackageInfo(packageName, 0).firstInstallTime
                } catch (e: Exception) {
                    0L
                }
                AppEntry(
                    packageName = packageName,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm),
                    category = categoryOf(info.activityInfo.applicationInfo),
                    installedAt = installedAt
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
