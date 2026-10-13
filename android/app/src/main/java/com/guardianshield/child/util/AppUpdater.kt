package com.guardianshield.child.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.guardianshield.child.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Checagem de versão nova do app: compara a última release publicada no GitHub (o nome da
 * tag, ex. "2.1") com o versionName deste APK. Só avisa quando a release é MAIS NOVA — um
 * APK recém-instalado de uma branch, ou mais novo que a última release, não mostra aviso.
 * O APK é baixado do próprio asset da release pelo DownloadManager do Android — ver
 * PainelActivity. Pra publicar uma versão: subir o versionName e criar uma release com
 * essa tag e o .apk anexado.
 */
object AppUpdater {
    private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/nevermind1999/guardian-shield-kid/releases/latest"
    private const val KEY_DISMISSED_VERSION = "dismissedUpdateVersion"

    data class Update(val version: String, val notes: String, val apkUrl: String)

    /** Bloqueante (rede) — chamar fora da main thread. null = nada novo (ou sem rede). */
    fun check(context: Context): Update? {
        return try {
            val connection = URL(GITHUB_LATEST_RELEASE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            if (connection.responseCode != 200) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            connection.disconnect()
            val version = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            if (!isNewer(version, BuildConfig.VERSION_NAME)) return null
            if (GuardianPrefs.of(context).getString(KEY_DISMISSED_VERSION, null) == version) return null
            val assets = json.optJSONArray("assets") ?: return null
            val apkUrl = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")?.takeIf { it.isNotEmpty() } ?: return null
            val notes = json.optString("body").lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: "Melhorias e correções."
            Update(version, notes, apkUrl)
        } catch (e: Exception) {
            null
        }
    }

    /** "2.10" > "2.9" > "2.0.1" > "2.0"; texto que não é número conta como 0. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun dismiss(context: Context, version: String) {
        GuardianPrefs.of(context).edit().putString(KEY_DISMISSED_VERSION, version).apply()
    }

    /** Começa o download (com a notificação de progresso do próprio Android) e devolve o id. */
    fun startDownload(context: Context, apkUrl: String): Long {
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("GuardianShield")
            .setDescription("Baixando atualização…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "GuardianShield-Filho-atualizacao.apk")
            .setMimeType("application/vnd.android.package-archive")
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }
}
