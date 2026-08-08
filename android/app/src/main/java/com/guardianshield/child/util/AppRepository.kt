package com.guardianshield.child.util

import android.content.Context
import android.content.Intent
import com.guardianshield.child.model.AppEntry

/** Consulta e abre apps reais do aparelho via PackageManager — usado pela Home e pela Gaveta nativas. */
object AppRepository {

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
                    icon = info.loadIcon(pm)
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
