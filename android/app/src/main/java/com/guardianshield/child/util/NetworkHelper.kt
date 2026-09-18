package com.guardianshield.child.util

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * POST JSON genérico pro backend, tentando primeiro o último candidato que funcionou (ver
 * GuardianPrefs.lastWorkingServerUrl) antes de percorrer ServerConfig.SERVER_URL_CANDIDATES
 * inteiro — mesma lógica que já existia presa dentro de ParentalAccessibilityService.postJson
 * (privada, só usada por ela), extraída aqui pra também dar pra chamar da tela de bloqueio
 * (ver LockOverlayService.java, botão "Pedir tempo extra") sem duplicar a lógica de
 * candidatos/token. Roda de forma SÍNCRONA/bloqueante — sempre chamar numa thread própria,
 * nunca na principal. `@JvmStatic` deixa o Java chamar `NetworkHelper.postJson(...)` direto,
 * sem precisar de `.INSTANCE.`.
 */
object NetworkHelper {
    @JvmStatic
    fun postJson(context: Context, path: String, jsonBody: String): Boolean {
        val candidates = (listOfNotNull(GuardianPrefs.lastWorkingServerUrl(context)) + ServerConfig.SERVER_URL_CANDIDATES).distinct()
        for (baseUrl in candidates) {
            try {
                val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Content-Type", "application/json")
                GuardianPrefs.deviceToken(context)?.let { connection.setRequestProperty("X-Device-Token", it) }
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode == 200) {
                    GuardianPrefs.setLastWorkingServerUrl(context, baseUrl)
                    connection.disconnect()
                    return true
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Tenta o próximo candidato.
            }
        }
        return false
    }
}
