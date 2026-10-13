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

    /** Resposta crua (código HTTP + corpo) de um POST — pra quando o corpo importa mesmo
     * em erro (ex: pareamento devolve 400 com a mensagem "código inválido" no JSON).
     * null = nenhum candidato respondeu (sem internet / servidor fora). Bloqueante. */
    @JvmStatic
    fun postJsonResponse(context: Context, path: String, jsonBody: String): Pair<Int, String>? {
        val candidates = (listOfNotNull(GuardianPrefs.lastWorkingServerUrl(context)) + ServerConfig.SERVER_URL_CANDIDATES).distinct()
        for (baseUrl in candidates) {
            try {
                val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 6_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("Content-Type", "application/json")
                GuardianPrefs.deviceToken(context)?.let { connection.setRequestProperty("X-Device-Token", it) }
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                GuardianPrefs.setLastWorkingServerUrl(context, baseUrl)
                return code to body
            } catch (e: Exception) {
                // Tenta o próximo candidato.
            }
        }
        return null
    }
}