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
 * Checagem de versão nova do app (o que src/services/updater.js fazia na versão React):
 * compara o commit mais recente da main no GitHub com o commit em que este APK foi
 * compilado (BuildConfig.GIT_SHA, gravado pelo Gradle). O APK em si é baixado do backend
 * (mesmo endereço de sempre) pelo DownloadManager do Android — ver PainelActivity.
 */
object AppUpdater {
    private const val GITHUB_COMMITS_URL = "https://api.github.com/repos/nevermind1999/guardian-shield-kid/commits/main"
    const val APK_URL = "https://guardian-shield.oguiazevedo.com/api/download/filho"
    private const val KEY_DISMISSED_SHA = "dismissedUpdateSha"

    data class Update(val sha: String, val notes: String)

    /** Bloqueante (rede) — chamar fora da main thread. null = nada novo (ou sem rede). */
    fun check(context: Context): Update? {
        if (BuildConfig.GIT_SHA == "dev") return null
        return try {
            val connection = URL(GITHUB_COMMITS_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            if (connection.responseCode != 200) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            connection.disconnect()
            val sha = json.optString("sha").take(7)
            if (sha.isEmpty() || sha == BuildConfig.GIT_SHA) return null
            if (GuardianPrefs.of(context).getString(KEY_DISMISSED_SHA, null) == sha) return null
            val notes = json.optJSONObject("commit")?.optString("message").orEmpty()
                .lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Melhorias e correções."
            Update(sha, notes)
        } catch (e: Exception) {
            null
        }
    }

    fun dismiss(context: Context, sha: String) {
        GuardianPrefs.of(context).edit().putString(KEY_DISMISSED_SHA, sha).apply()
    }

    /** Começa o download (com a notificação de progresso do próprio Android) e devolve o id. */
    fun startDownload(context: Context): Long {
        val request = DownloadManager.Request(Uri.parse(APK_URL))
            .setTitle("GuardianShield")
            .setDescription("Baixando atualização…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "GuardianShield-Filho-atualizacao.apk")
            .setMimeType("application/vnd.android.package-archive")
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }
}
