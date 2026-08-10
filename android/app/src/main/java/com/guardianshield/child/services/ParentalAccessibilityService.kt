package com.guardianshield.child.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import com.guardianshield.child.LauncherHomeActivity
import com.guardianshield.child.util.AppRepository
import com.guardianshield.child.util.GuardianPrefs
import com.guardianshield.child.util.ServerConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class ParentalAccessibilityService : AccessibilityService() {

    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    // Consulta HTTP simples (o serviço não tem cliente socket.io, só a WebView tem) —
    // roda numa thread própria pra nunca bloquear a main thread do tick.
    private val networkExecutor = Executors.newSingleThreadExecutor()

    // Último pacote visto em primeiro plano (atualizado em onAccessibilityEvent) — usado
    // pelo ticker pra reavaliar o bloqueio do app atual mesmo sem um novo evento de troca
    // de janela (ex: a criança fica parada olhando a tela de bloqueio de um app que acabou
    // de ser desbloqueado pelo pai; sem isso, nada disparava a reavaliação).
    private var lastForegroundPackage: String? = null

    // Conta os ticks de 1min pra disparar o envio da lista de apps instalados só a cada
    // ~5min (ela muda raramente, não precisa ir a cada ciclo de 60s como o resto).
    private var tickCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        startUsageTicker()
    }

    /**
     * Contador de tempo de tela + sincronização de regras/tarefas: este serviço é o único
     * componente do app que fica vivo o tempo todo (a WebView/React só existe quando a
     * criança abre as Configurações, e a Home nativa pode ser destruída pelo sistema) —
     * por isso é aqui, e não no JS, que o "usado hoje" precisa ser contado de verdade e
     * as regras do pai (pausa geral, apps bloqueados, limite diário, tarefas) precisam
     * ser sincronizadas com o backend. A cada minuto: se a tela estiver ligada, soma 1
     * minuto; sempre, reavalia o bloqueio do app atual e dispara um poll em background
     * (a cada ~5 ciclos, o poll também reenvia a lista real de apps instalados).
     */
    private fun startUsageTicker() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                tickUsageOnce()
                tickHandler.postDelayed(this, 60_000L)
            }
        }
        tickRunnable = runnable
        tickHandler.postDelayed(runnable, 60_000L)
    }

    private fun tickUsageOnce() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = powerManager?.isInteractive ?: true
        if (screenOn) {
            GuardianPrefs.incrementUsedMinutesToday(this)
        }
        // Reavalia o bloqueio (geral + app atual) a cada ciclo, não só quando a janela
        // em 1º plano muda — cobre tempo esgotar, tarefa pendente, bloqueio/desbloqueio
        // de um app específico ou da Pausa Geral enquanto a criança fica parada no mesmo app.
        reevaluateBlockState()

        tickCount++
        networkExecutor.execute {
            pollRulesSync()
            if (tickCount % 5 == 0) postInstalledApps()
        }
    }

    /**
     * GET /api/tasks/sync no backend, tentando os mesmos candidatos de servidor usados
     * pela WebView (SERVER_URLS em App.jsx) — o nativo mantém sua própria lista porque
     * não compartilha o cliente socket.io dela. Grava tarefas + regras de bloqueio
     * (pausa geral, apps bloqueados, limite diário) em GuardianPrefs; em caso de falha
     * (sem internet, servidor fora do ar), simplesmente mantém o último valor conhecido.
     */
    private fun pollRulesSync() {
        for (baseUrl in ServerConfig.SERVER_URL_CANDIDATES) {
            try {
                val connection = URL("$baseUrl/api/tasks/sync").openConnection() as HttpURLConnection
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                connection.requestMethod = "GET"
                if (connection.responseCode == 200) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val blockedPackagesArray = json.optJSONArray("blockedPackages") ?: JSONArray()
                    val blockedPackages = (0 until blockedPackagesArray.length())
                        .map { blockedPackagesArray.getString(it) }
                        .toSet()
                    GuardianPrefs.saveRulesSync(
                        this,
                        unlockMode = json.optString("unlockMode", "off"),
                        dailyLimitMinutes = json.optInt("dailyLimitMinutes", GuardianPrefs.dailyLimitMinutes(this)),
                        isPauseAllActive = json.optBoolean("isPauseAllActive", false),
                        blockedPackages = blockedPackages,
                        rawTasksJson = body
                    )
                    // Deixa em cache pra LauncherHomeActivity tentar este endereço primeiro
                    // ao enviar uma foto, em vez de percorrer todos os candidatos de novo.
                    GuardianPrefs.setLastWorkingServerUrl(this, baseUrl)
                    connection.disconnect()
                    // As regras acabaram de mudar (ou foram confirmadas) — reavalia de
                    // novo pra refletir na hora, sem esperar o próximo tick de 1min.
                    tickHandler.post { reevaluateBlockState() }
                    return
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Tenta o próximo candidato; se nenhum responder, mantém o cache antigo.
            }
        }
    }

    /**
     * POST /api/device/apps-sync com a lista real de apps instalados (mesma fonte que já
     * alimenta a Home/Gaveta nativas via AppRepository) — antes essa lista só chegava ao
     * backend quando a WebView mandava telemetria, e a WebView raramente abre no uso normal.
     */
    private fun postInstalledApps() {
        val apps = AppRepository.loadLaunchableApps(this)
        val appsArray = JSONArray()
        for (app in apps) {
            appsArray.put(JSONObject().put("package", app.packageName).put("name", app.label))
        }
        val body = JSONObject().put("installedApps", appsArray).toString()

        val candidates = (listOfNotNull(GuardianPrefs.lastWorkingServerUrl(this)) + ServerConfig.SERVER_URL_CANDIDATES).distinct()
        for (baseUrl in candidates) {
            try {
                val connection = URL("$baseUrl/api/device/apps-sync").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode == 200) {
                    GuardianPrefs.setLastWorkingServerUrl(this, baseUrl)
                    connection.disconnect()
                    return
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Tenta o próximo candidato; se nenhum responder, tenta de novo no próximo ciclo.
            }
        }
    }

    /**
     * Motivo atual de bloqueio do aparelho, na prioridade Pausa Geral > Tarefas pendentes >
     * Tempo esgotado — usado tanto pelo ticker (pra travar um app já aberto quando o motivo
     * muda no meio da sessão) quanto pelo onAccessibilityEvent (pra decidir travar antes de
     * um app abrir). Retorna null se nada estiver bloqueando.
     */
    private fun currentBlockReason(): Pair<String, String>? {
        return when {
            GuardianPrefs.isPauseAllActive(this) ->
                "🔒 DISPOSITIVO BLOQUEADO" to "Pausa Geral Ativa.\nFale com seus pais para liberar o uso."
            GuardianPrefs.isTaskGateBlocking(this) ->
                "🔒 TAREFAS PENDENTES" to "Complete e envie todas as tarefas de hoje para liberar o celular."
            GuardianPrefs.isDailyLimitExceeded(this) ->
                "⏳ TEMPO ESGOTADO" to "Você já usou todo o tempo de tela de hoje.\nFale com seus pais para liberar mais tempo."
            else -> null
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastForegroundPackage = packageName
            reevaluateBlockState()
        }
    }

    /**
     * Decide se o app atualmente em 1º plano (lastForegroundPackage) deve estar bloqueado
     * e mostra/esconde a sobreposição de acordo. Chamado tanto por eventos reais de troca
     * de janela quanto pelo ticker de 1min — o ticker é o que garante que um desbloqueio
     * (ou bloqueio) decidido pelo pai enquanto a criança fica parada no mesmo app (sem
     * gerar novo evento de acessibilidade) seja detectado mesmo assim.
     */
    private fun reevaluateBlockState() {
        val packageName = lastForegroundPackage ?: return
        if (packageName == "com.guardianshield.child") return

        val blockReason = currentBlockReason()
        val blockedPackagesSet = GuardianPrefs.blockedPackages(this)

        Log.d("GuardianShield", "App em 1º plano: $packageName | Bloqueio: ${blockReason?.first ?: "nenhum"} | Bloqueados: $blockedPackagesSet")

        // 1. Se algum motivo global estiver bloqueando (Pausa Geral, tarefas pendentes
        // ou tempo esgotado), nenhum app pode ser aberto (exceto o próprio Guardian Shield)
        if (blockReason != null) {
            val (title, message) = blockReason
            Log.w("GuardianShield", "Bloqueio ativo! Forçando sobreposição para $packageName")
            showOverlay(title, message)
            bringGuardianShieldToFront()
            return
        }

        // 2. Sem bloqueio global, verifica se o aplicativo em 1º plano está bloqueado individualmente
        val isBlocked = blockedPackagesSet.contains(packageName) || isPackageBlockedFallback(packageName, blockedPackagesSet)

        if (isBlocked) {
            Log.w("GuardianShield", "App Bloqueado Detectado: $packageName. Exibindo tela de bloqueio do app!")
            val appLabel = getAppNameFromPackage(packageName)
            showOverlay("🔒 APLICATIVO BLOQUEADO", "O aplicativo $appLabel foi bloqueado pelos seus pais.")
            bringGuardianShieldToFront()
        } else {
            // App permitido (ou o bloqueio acabou de ser removido pelo pai) — remove a
            // sobreposição se estiver visível.
            hideOverlay()
        }
    }

    private fun isPackageBlockedFallback(pkg: String, blockedSet: Set<String>): Boolean {
        for (blocked in blockedSet) {
            val bLower = blocked.lowercase()
            val pkgLower = pkg.lowercase()
            if (pkgLower == bLower) return true
            if ((bLower.contains("tiktok") || bLower.contains("musically")) &&
                (pkgLower.contains("musically") || pkgLower.contains("trill") || pkgLower.contains("tiktok"))) return true
            if (bLower.contains("instagram") && pkgLower.contains("instagram")) return true
            if (bLower.contains("youtube") && pkgLower.contains("youtube")) return true
            if (bLower.contains("freefire") && pkgLower.contains("freefire")) return true
        }
        return false
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            if (packageName.contains("musically") || packageName.contains("tiktok")) "TikTok"
            else if (packageName.contains("instagram")) "Instagram"
            else if (packageName.contains("youtube")) "YouTube"
            else "selecionado"
        }
    }

    private fun showOverlay(title: String, message: String) {
        val overlayIntent = Intent(this, LockOverlayService::class.java)
        overlayIntent.action = "SHOW_OVERLAY"
        overlayIntent.putExtra("title", title)
        overlayIntent.putExtra("message", message)
        startService(overlayIntent)
    }

    private fun hideOverlay() {
        val overlayIntent = Intent(this, LockOverlayService::class.java)
        overlayIntent.action = "HIDE_OVERLAY"
        startService(overlayIntent)
    }

    private fun bringGuardianShieldToFront() {
        // Traz a Home nativa (LauncherHomeActivity) para frente — é o "lugar seguro"
        // pra onde a criança deve voltar quando um app é bloqueado.
        val intent = Intent(this, LauncherHomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.e("GuardianShield", "Serviço de acessibilidade interrompido.")
    }
}
