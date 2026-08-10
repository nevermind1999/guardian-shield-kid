package com.guardianshield.child.services

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
import java.text.SimpleDateFormat
import java.util.Locale
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
            val forceFreshLocation = pollRulesSync()
            if (tickCount % 5 == 0) postInstalledApps()
            if (GuardianPrefs.pendingPinUnlockAck(this)) ackPinUnlock()
            // Localização a cada ciclo (60s) — bem mais fresco que antes, quando só
            // atualizava se a WebView estivesse aberta. forceFreshLocation vem de um
            // pedido de "Forçar Atualização" do pai (ver locationUpdateRequested).
            fetchLocationAndReport(forceFreshLocation)
        }
    }

    /**
     * GET /api/tasks/sync no backend, tentando os mesmos candidatos de servidor usados
     * pela WebView (SERVER_URLS em App.jsx) — o nativo mantém sua própria lista porque
     * não compartilha o cliente socket.io dela. Grava tarefas + regras de bloqueio
     * (pausa geral, apps bloqueados, limite diário, PIN de emergência) em GuardianPrefs;
     * em caso de falha (sem internet, servidor fora do ar), mantém o último valor
     * conhecido. Retorna se o pai pediu uma atualização de GPS forçada nesse ciclo.
     */
    private fun pollRulesSync(): Boolean {
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
                        unlockPinHash = if (json.isNull("unlockPinHash")) null else json.optString("unlockPinHash", null),
                        rawTasksJson = body
                    )
                    // Deixa em cache pra LauncherHomeActivity tentar este endereço primeiro
                    // ao enviar uma foto, em vez de percorrer todos os candidatos de novo.
                    GuardianPrefs.setLastWorkingServerUrl(this, baseUrl)
                    connection.disconnect()
                    // As regras acabaram de mudar (ou foram confirmadas) — reavalia de
                    // novo pra refletir na hora, sem esperar o próximo tick de 1min.
                    tickHandler.post { reevaluateBlockState() }
                    return json.optBoolean("locationUpdateRequested", false)
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Tenta o próximo candidato; se nenhum responder, mantém o cache antigo.
            }
        }
        return false
    }

    /**
     * POST JSON num dos candidatos de servidor, tentando primeiro o último que
     * funcionou (cache em GuardianPrefs) antes de percorrer a lista toda de novo.
     * Compartilhado por apps-sync, location-sync e pin-unlock-ack. Retorna se algum
     * candidato respondeu 200.
     */
    private fun postJson(path: String, jsonBody: String): Boolean {
        val candidates = (listOfNotNull(GuardianPrefs.lastWorkingServerUrl(this)) + ServerConfig.SERVER_URL_CANDIDATES).distinct()
        for (baseUrl in candidates) {
            try {
                val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode == 200) {
                    GuardianPrefs.setLastWorkingServerUrl(this, baseUrl)
                    connection.disconnect()
                    return true
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Tenta o próximo candidato; se nenhum responder, tenta de novo no próximo ciclo.
            }
        }
        return false
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
            appsArray.put(
                JSONObject()
                    .put("package", app.packageName)
                    .put("name", app.label)
                    .put("category", app.category)
            )
        }
        postJson("/api/device/apps-sync", JSONObject().put("installedApps", appsArray).toString())
    }

    /**
     * Ack de um desbloqueio local por PIN (ver currentBlockReason/pinOverrideDate) —
     * avisa o backend que o aparelho já foi liberado de verdade, pra o painel do pai
     * não continuar mostrando "Pausa Geral Ativa" quando na prática já foi resolvido.
     * Só limpa a flag local se o ack for confirmado; se a rede cair de novo no meio
     * do caminho, tenta de novo no próximo ciclo.
     */
    private fun ackPinUnlock() {
        if (postJson("/api/device/pin-unlock-ack", "{}")) {
            GuardianPrefs.setPendingPinUnlockAck(this, false)
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Busca a posição atual e reporta pro backend — a cada tick normal usa a última
     * posição em cache (barato, não acorda o GPS); quando `forceFresh` (pedido de
     * "Forçar Atualização" do pai) ou não há nada em cache ainda, busca uma leitura
     * ativa de verdade via requestSingleUpdate.
     */
    private fun fetchLocationAndReport(forceFresh: Boolean) {
        if (!hasLocationPermission()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        if (!forceFresh) {
            val cached = bestLastKnownLocation(lm)
            if (cached != null) {
                postLocation(cached)
                return
            }
        }
        requestFreshLocationOnce(lm)
    }

    private fun bestLastKnownLocation(lm: LocationManager): Location? {
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { provider ->
                try {
                    if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
                } catch (e: SecurityException) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }

    /** Pede uma leitura de GPS/rede ativa (não só cache) — usa o looper principal pra receber o callback. */
    private fun requestFreshLocationOnce(lm: LocationManager) {
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lm.removeUpdates(this)
                // O callback chega no looper principal (passado abaixo) — a chamada de
                // rede em postLocation precisa sair da main thread.
                networkExecutor.execute { postLocation(location) }
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            // Timeout de segurança: se não vier fix em 10s, desiste (tenta de novo no
            // próximo tick de 60s) em vez de deixar o listener pendurado pra sempre.
            tickHandler.postDelayed({ lm.removeUpdates(listener) }, 10_000L)
        } catch (e: SecurityException) {
            // Permissão negada em runtime apesar do manifesto — ignora.
        }
    }

    private fun postLocation(location: Location) {
        val body = JSONObject()
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracy", location.accuracy.toDouble())
            .toString()
        postJson("/api/device/location-sync", body)
    }

    /**
     * Motivo atual de bloqueio do aparelho, na prioridade PIN de emergência > Pausa Geral >
     * Tarefas pendentes > Tempo esgotado — usado tanto pelo ticker (pra travar um app já
     * aberto quando o motivo muda no meio da sessão) quanto pelo onAccessibilityEvent (pra
     * decidir travar antes de um app abrir). Retorna null se nada estiver bloqueando.
     */
    private fun currentBlockReason(): Pair<String, String>? {
        return when {
            // Válvula de emergência: se a criança (ou o pai) digitou o PIN certo na
            // tela de bloqueio hoje, suspende TODOS os motivos abaixo até a virada do
            // dia — não só Pausa Geral, senão o "nunca mais travar" vira mentira assim
            // que bater o horário de tarefa pendente ou tempo esgotado de novo.
            GuardianPrefs.isPinOverrideActiveToday(this) -> null
            // Janela curta (poucos minutos) aberta pelo botão "Enviar Foto" da tela de
            // tarefas pendentes — sem isso, o próprio app de câmera do sistema seria
            // barrado de volta pra tela de bloqueio assim que abrisse.
            GuardianPrefs.isTaskSubmissionWindowActive(this) -> null
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
        // ou tempo esgotado), nenhum app pode ser aberto (exceto o próprio Guardian Shield).
        // allowPinUnlock=true aqui: é exatamente esse tipo de bloqueio que o PIN de
        // emergência resolve (ver currentBlockReason).
        if (blockReason != null) {
            val (title, message) = blockReason
            Log.w("GuardianShield", "Bloqueio ativo! Forçando sobreposição para $packageName")
            // O motivo específico "tarefas pendentes" ganha a lista de tarefas + botão
            // de enviar foto na sobreposição (ver LockOverlayService) — os outros dois
            // motivos globais (Pausa Geral, tempo esgotado) não têm o que mostrar aqui.
            showOverlay(title, message, allowPinUnlock = true, allowTaskSubmission = GuardianPrefs.isTaskGateBlocking(this))
            bringGuardianShieldToFront()
            return
        }

        // 2. Sem bloqueio global, verifica se o aplicativo em 1º plano está bloqueado
        // individualmente. allowPinUnlock=false aqui: o PIN de emergência é pra
        // destravar o aparelho, não pra liberar um app específico que o pai escolheu
        // bloquear de propósito (ver currentBlockReason, que não olha pra isso).
        val isBlocked = blockedPackagesSet.contains(packageName) || isPackageBlockedFallback(packageName, blockedPackagesSet)

        if (isBlocked) {
            Log.w("GuardianShield", "App Bloqueado Detectado: $packageName. Exibindo tela de bloqueio do app!")
            val appLabel = getAppNameFromPackage(packageName)
            showOverlay("🔒 APLICATIVO BLOQUEADO", "O aplicativo $appLabel foi bloqueado pelos seus pais.", allowPinUnlock = false)
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

    private fun showOverlay(title: String, message: String, allowPinUnlock: Boolean, allowTaskSubmission: Boolean = false) {
        val overlayIntent = Intent(this, LockOverlayService::class.java)
        overlayIntent.action = "SHOW_OVERLAY"
        overlayIntent.putExtra("title", title)
        overlayIntent.putExtra("message", message)
        overlayIntent.putExtra("allowPinUnlock", allowPinUnlock)
        overlayIntent.putExtra("allowTaskSubmission", allowTaskSubmission)
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
