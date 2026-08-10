package com.guardianshield.child.util

/**
 * Candidatos de endereço do backend usados pelo lado NATIVO do app (Home/serviço de
 * acessibilidade), que não têm cliente socket.io — só a WebView/React (App.jsx,
 * SERVER_URLS) tem. Mantidos aqui em Kotlin puro pra evitar duplicar a lista entre
 * ParentalAccessibilityService (poll de tarefas) e LauncherHomeActivity (envio de fotos).
 */
object ServerConfig {
    val SERVER_URL_CANDIDATES = listOf(
        "http://192.168.1.114:3001",
        "http://10.0.2.2:3001"
    )
}
