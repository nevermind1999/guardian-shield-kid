package com.guardianshield.child.util

/**
 * Candidatos de endereço do backend usados pelo lado NATIVO do app (Home/serviço de
 * acessibilidade), que não têm cliente socket.io — só a WebView/React (App.jsx,
 * SERVER_URLS) tem. Mantidos aqui em Kotlin puro pra evitar duplicar a lista entre
 * ParentalAccessibilityService (poll de tarefas/regras) e LauncherHomeActivity (envio
 * de fotos).
 *
 * O servidor de verdade, usado em produção, é o backend online (mesmo domínio de
 * api/apks/latest em server.js e de VITE_BACKEND_URL em .env) — antes essa URL só
 * existia aqui do lado nativo faltando, então o poll nunca conseguia falar com o
 * backend real fora de uma rede local de desenvolvimento (só a WebView tinha o
 * endereço certo). Os demais candidatos (localhost via `adb reverse`, IP de rede
 * local, emulador) são só pra depuração/testes; em uso normal falham rápido e caem
 * pro próximo até chegar no de produção.
 */
object ServerConfig {
    val SERVER_URL_CANDIDATES = listOf(
        "https://guardian-shield.oguiazevedo.com",
        "http://localhost:3001",
        "http://192.168.1.114:3001",
        "http://10.0.2.2:3001"
    )
}
