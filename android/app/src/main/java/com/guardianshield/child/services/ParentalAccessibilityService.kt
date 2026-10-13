package com.guardianshield.child.services

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.widget.Toast
import com.guardianshield.child.LauncherHomeActivity
import com.guardianshield.child.util.AppRepository
import com.guardianshield.child.util.GuardianPrefs
import com.guardianshield.child.util.PermissionWatchdog
import com.guardianshield.child.util.ServerConfig
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class ParentalAccessibilityService : AccessibilityService() {

    // Pacote do app de Configurações do sistema — ver a exceção "0." em reevaluateBlockState.
    // Só "com.android.settings" mesmo em aparelhos Samsung/OneUI (confirmado com
    // `adb shell pm list packages` num Galaxy A06 real); listado como Set já pensando em
    // cobrir variações de outros fabricantes se aparecerem no futuro.
    private val SETTINGS_PACKAGES = setOf("com.android.settings")

    // Pacotes "utilitários do sistema" fixos (fora teclado/câmera, que são resolvidos em
    // runtime — ver isTransientHelperPackage) que só aparecem em cima de um app já aberto
    // (diálogo de permissão, folha de "compartilhar com"), nunca como algo que a criança
    // "abriu" de propósito.
    private val SYSTEM_HELPER_PACKAGES = setOf(
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.android.systemui"
    )

    companion object {
        /** Quanto o backend pode segurar cada consulta de regras esperando uma mudança. */
        private const val RULE_SYNC_WAIT_SECONDS = 25
        /** Espera entre tentativas quando a consulta falha (sem internet etc), crescente. */
        private const val RULE_SYNC_RETRY_MIN_MS = 5_000L
        private const val RULE_SYNC_RETRY_MAX_MS = 60_000L
    }

    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    // Sincronização de regras (pausa geral, tarefas, limite diário, liberação manual do
    // pai) por long-poll — ver startRuleSyncLoop. Thread própria, porque cada consulta pode
    // ficar até RULE_SYNC_WAIT_SECONDS aberta esperando o pai mudar algo; separada do tick
    // de 1min de uso/localização/telemetria, que continua no ritmo dele.
    private val ruleSyncExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var ruleSyncRunning = false
    // Versão das regras que este aparelho já tem (syncVersion devolvido pelo backend).
    @Volatile private var ruleSyncVersion: String? = null

    // Consulta HTTP simples (o serviço não tem cliente socket.io, só a WebView tem) —
    // roda numa thread própria pra nunca bloquear a main thread do tick. Compartilhada
    // pelos dois tickers (usage e rule sync); por ser single-thread, as chamadas dos dois
    // sempre executam em série, nunca concorrem entre si.
    private val networkExecutor = Executors.newSingleThreadExecutor()

    // Gravar o histórico de apps abertos mexe num JSON de até 100 entradas — fora da thread
    // principal, pra troca de app não esperar por isso.
    private val historyExecutor = Executors.newSingleThreadExecutor()

    // Última resposta do poll de regras (GPS/sincronizar forçados pelo pai) — escrita pelo
    // ruleSyncTicker (a cada ~15s) e lida pelo tick de uso (a cada 60s, que é quem de fato
    // busca localização/telemetria). O flag do backend só é zerado quando o POST
    // correspondente é confirmado (não pelo GET em si), então não tem risco de "perder"
    // um pedido entre os dois ritmos — o próximo poll de 15s continua devolvendo true até
    // o tick de 60s de fato atender.
    @Volatile private var pendingSyncFlags = SyncFlags(forceFreshLocation = false, forceDeviceSync = false)

    // Último pacote visto em primeiro plano (atualizado em onAccessibilityEvent) — usado
    // pelo ticker pra reavaliar o bloqueio do app atual mesmo sem um novo evento de troca
    // de janela (ex: a criança fica parada olhando a tela de bloqueio de um app que acabou
    // de ser desbloqueado pelo pai; sem isso, nada disparava a reavaliação).
    private var lastForegroundPackage: String? = null

    // Conta os ticks de 1min pra disparar o envio da lista de apps instalados só a cada
    // ~5min (ela muda raramente, não precisa ir a cada ciclo de 60s como o resto).
    private var tickCount = 0

    private var screenStateReceiverRegistered = false

    // Cada vez que a tela liga de novo, sorteia uma cor nova pra tela de bloqueio (pedido
    // explícito do usuário: "faça o degradê e cor de fundo das telas de bloqueio mudar
    // sempre que a tela apagar e ativar de novo" — ver GuardianPrefs.rotateLockAccentColors)
    // e manda o LockOverlayService se reconstruir com ela, caso já esteja mostrando algo.
    // Registrado aqui (não no LockOverlayService) porque este é o único componente do app
    // garantido vivo o tempo todo — o serviço da sobreposição só existe enquanto há um
    // bloqueio ativo.
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_ON) return
            GuardianPrefs.rotateLockAccentColors(this@ParentalAccessibilityService)
            val refreshIntent = Intent(this@ParentalAccessibilityService, LockOverlayService::class.java)
            refreshIntent.action = "REFRESH_ACCENT"
            startService(refreshIntent)
            // Também reavalia o bloqueio normalmente — cobre o caso de algo ter mudado
            // (regra do pai, tarefa, horário) enquanto a tela estava desligada.
            reevaluateBlockState()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        startUsageTicker()
        startRuleSyncLoop()
        if (!screenStateReceiverRegistered) {
            registerReceiver(screenStateReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            screenStateReceiverRegistered = true
        }
        // Liga o watchdog externo (ver PermissionWatchdog) assim que a Acessibilidade é
        // habilitada — ele é quem detecta se ESTE serviço aqui for desligado de novo mais
        // tarde (sozinho ou por um otimizador de bateria do fabricante), já que nesse caso
        // este onServiceConnected simplesmente não roda de novo pra se autodenunciar.
        PermissionWatchdog.scheduleRepeating(this)
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

    /**
     * Sincronização de regras por long-poll: a consulta manda a versão que o aparelho já
     * tem e o backend só responde quando algo muda (ou depois de RULE_SYNC_WAIT_SECONDS) —
     * Pausa Geral, liberar tempo, aprovar tarefa etc. chegam no celular em ~1s, em vez de
     * até 15s com a consulta periódica de antes, e com menos requisições. Se a consulta
     * falha (sem internet), espera um pouco mais a cada vez antes de tentar de novo.
     */
    private fun startRuleSyncLoop() {
        if (ruleSyncRunning) return
        ruleSyncRunning = true
        ruleSyncExecutor.execute {
            var retryDelay = RULE_SYNC_RETRY_MIN_MS
            while (ruleSyncRunning) {
                val flags = pollRulesSync(waitSeconds = if (ruleSyncVersion != null) RULE_SYNC_WAIT_SECONDS else 0)
                if (flags != null) {
                    retryDelay = RULE_SYNC_RETRY_MIN_MS
                    pendingSyncFlags = flags
                    // Pedido do pai de GPS/sincronizar: atende já, sem esperar o tick de 1min.
                    if (flags.forceFreshLocation) networkExecutor.execute { fetchLocationAndReport(true) }
                    if (flags.forceDeviceSync) networkExecutor.execute { postDeviceTelemetry() }
                    // Backend antigo (sem long-poll, não manda versão): consulta periódica.
                    if (ruleSyncVersion == null) {
                        try { Thread.sleep(RULE_SYNC_RETRY_MIN_MS) } catch (e: InterruptedException) { return@execute }
                    }
                } else {
                    try { Thread.sleep(retryDelay) } catch (e: InterruptedException) { return@execute }
                    retryDelay = (retryDelay * 2).coerceAtMost(RULE_SYNC_RETRY_MAX_MS)
                }
            }
        }
    }

    private fun tickUsageOnce() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = powerManager?.isInteractive ?: true
        if (screenOn) {
            GuardianPrefs.incrementUsedMinutesToday(this)
            // Consome 1min da liberação manual "tempo de uso" em andamento, se houver uma
            // ativa (ver GuardianPrefs.parentTimeGrantMode) — não faz nada em modo "clock"
            // (esse escoa sozinho pelo relógio) nem se não há liberação ativa.
            GuardianPrefs.incrementParentTimeGrantUsageMinuteIfActive(this)
            maybeWarnLowTimeRemaining()
        }
        // Reavalia o bloqueio (geral + app atual) a cada ciclo, não só quando a janela
        // em 1º plano muda — cobre tempo esgotar, tarefa pendente, bloqueio/desbloqueio
        // de um app específico ou da Pausa Geral enquanto a criança fica parada no mesmo app.
        reevaluateBlockState()

        tickCount++
        networkExecutor.execute {
            // A sincronização de regras em si roda no long-poll (ver startRuleSyncLoop) —
            // aqui só consome o resultado mais recente pra decidir se precisa buscar
            // localização/telemetria "forçadas" neste ciclo de 1min.
            val syncFlags = pendingSyncFlags
            if (tickCount % 5 == 0) postInstalledApps()
            if (GuardianPrefs.pendingPinUnlockAck(this)) ackPinUnlock()
            // Localização a cada ciclo (60s) — bem mais fresco que antes, quando só
            // atualizava se a WebView estivesse aberta. forceFreshLocation vem de um
            // pedido de "Forçar Atualização" do pai (ver locationUpdateRequested).
            fetchLocationAndReport(syncFlags.forceFreshLocation)
            // Histórico de apps abertos: mesmo ritmo da localização (a cada ciclo, 60s)
            // — é o tipo de coisa que o pai quer ver fresco, não só a cada ~5min.
            postUsageHistory()
            // Bateria/wifi/modelo: sozinho a cada ~5min (tickCount % 5, mesmo ritmo dos
            // apps instalados), ou na hora se o pai clicou "sincronizar" no header
            // (deviceSyncRequested) — sem esperar o próximo ciclo de 5min.
            if (tickCount % 5 == 0 || syncFlags.forceDeviceSync) postDeviceTelemetry()
        }
    }

    /** Flags de "atualização forçada" devolvidas pelo poll de regras — ver pollRulesSync. */
    private data class SyncFlags(val forceFreshLocation: Boolean, val forceDeviceSync: Boolean)

    private fun parseScheduleWindow(json: JSONObject?): GuardianPrefs.ScheduleWindow =
        GuardianPrefs.ScheduleWindow(
            enabled = json?.optBoolean("enabled", false) ?: false,
            start = json?.optString("start", "00:00") ?: "00:00",
            end = json?.optString("end", "00:00") ?: "00:00"
        )

    /**
     * Compara o estado local de antes do poll com o que acabou de chegar do backend e
     * mostra um toast rápido se o pai liberou algo em tempo real (Pausa Geral desativada,
     * mais minutos concedidos, ou um app desbloqueado) — sem isso a criança só percebe a
     * mudança se a Home estiver aberta bem na hora e reparar sozinha. Só avisa "pra
     * melhor": nunca ao bloquear algo, porque isso já fica óbvio na hora que a sobreposição
     * de bloqueio aparece por conta própria (ver reevaluateBlockState).
     */
    private fun notifyRuleChangesIfAny(
        wasPauseAllActive: Boolean,
        previousDailyLimit: Int,
        previousBlockedPackages: Set<String>,
        isPauseAllActive: Boolean,
        dailyLimitMinutes: Int,
        blockedPackages: Set<String>
    ) {
        val message = when {
            wasPauseAllActive && !isPauseAllActive -> "✅ Pausa Geral foi desativada pelos seus pais!"
            dailyLimitMinutes > previousDailyLimit -> "🎉 Você ganhou mais tempo de tela!"
            previousBlockedPackages.size > blockedPackages.size -> "🔓 Um aplicativo foi desbloqueado!"
            else -> null
        } ?: return
        tickHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    /**
     * Aviso único por dia quando restam poucos minutos de tempo de tela — dá uma chance
     * de a criança salvar o que estava fazendo antes do bloqueio por tempo esgotado pegar
     * de surpresa. "Único por dia" pra não repetir a cada tick de 1min enquanto o uso
     * segue na faixa de aviso (ver GuardianPrefs.hasShownLowTimeWarningToday).
     */
    private fun maybeWarnLowTimeRemaining() {
        if (GuardianPrefs.isPauseAllActive(this) || GuardianPrefs.hasShownLowTimeWarningToday(this)) return
        val remaining = GuardianPrefs.dailyLimitMinutes(this) - GuardianPrefs.usedMinutesToday(this)
        if (remaining in 1..5) {
            GuardianPrefs.markLowTimeWarningShownToday(this)
            Toast.makeText(this, "⏳ Só restam $remaining minutos de tela hoje!", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * GET /api/tasks/sync no backend, tentando os mesmos candidatos de servidor usados
     * pela WebView (SERVER_URLS em App.jsx) — o nativo mantém sua própria lista porque
     * não compartilha o cliente socket.io dela. Grava tarefas + regras de bloqueio
     * (pausa geral, apps bloqueados, limite diário, PIN de emergência) em GuardianPrefs;
     * em caso de falha (sem internet, servidor fora do ar), mantém o último valor
     * conhecido. Retorna quais atualizações forçadas o pai pediu nesse ciclo (GPS/sincronizar).
     */
    private fun pollRulesSync(waitSeconds: Int): SyncFlags? {
        val candidates = (listOfNotNull(GuardianPrefs.lastWorkingServerUrl(this)) + ServerConfig.SERVER_URL_CANDIDATES).distinct()
        val query = ruleSyncVersion?.takeIf { waitSeconds > 0 }?.let { "?wait=$waitSeconds&v=$it" }.orEmpty()
        for (baseUrl in candidates) {
            try {
                val connection = URL("$baseUrl/api/tasks/sync$query").openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = (waitSeconds + 10) * 1_000
                connection.requestMethod = "GET"
                GuardianPrefs.deviceToken(this)?.let { connection.setRequestProperty("X-Device-Token", it) }
                if (connection.responseCode == 200) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    ruleSyncVersion = json.optString("syncVersion").takeIf { it.isNotEmpty() }
                    val blockedPackagesArray = json.optJSONArray("blockedPackages") ?: JSONArray()
                    val blockedPackages = (0 until blockedPackagesArray.length())
                        .map { blockedPackagesArray.getString(it) }
                        .toSet()
                    val alwaysAvailableArray = json.optJSONArray("alwaysAvailablePackages") ?: JSONArray()
                    val alwaysAvailablePackages = (0 until alwaysAvailableArray.length())
                        .map { alwaysAvailableArray.getString(it) }
                        .toSet()

                    // Estado de ANTES de sobrescrever — usado só pra comparar com o que
                    // acabou de chegar e avisar a criança se o pai liberou algo (ver
                    // notifyRuleChangesIfAny logo abaixo).
                    val wasPauseAllActive = GuardianPrefs.isPauseAllActive(this)
                    val previousDailyLimit = GuardianPrefs.dailyLimitMinutes(this)
                    val previousBlockedPackages = GuardianPrefs.blockedPackages(this)

                    val newDailyLimit = json.optInt("dailyLimitMinutes", previousDailyLimit)
                    val newIsPauseAllActive = json.optBoolean("isPauseAllActive", false)

                    GuardianPrefs.saveRulesSync(
                        this,
                        unlockMode = json.optString("unlockMode", "off"),
                        dailyLimitMinutes = newDailyLimit,
                        isPauseAllActive = newIsPauseAllActive,
                        blockedPackages = blockedPackages,
                        alwaysAvailablePackages = alwaysAvailablePackages,
                        unlockPinHash = if (json.isNull("unlockPinHash")) null else json.optString("unlockPinHash", null),
                        emergencyPhone = if (json.isNull("emergencyPhone")) null else json.optString("emergencyPhone", null),
                        rawTasksJson = body,
                        // Ver parent:grant_bonus_time — 0/ausente = nenhuma liberação manual ativa agora.
                        parentTimeGrantUntil = if (json.isNull("parentTimeGrantUntil")) 0L else json.optLong("parentTimeGrantUntil", 0L),
                        // "clock" (tempo corrido) ou "usage" (tempo de uso) — ver GuardianPrefs.parentTimeGrantMode.
                        parentTimeGrantMode = json.optString("parentTimeGrantMode", "clock"),
                        parentTimeGrantUsageMinutesTotal = json.optInt("parentTimeGrantUsageMinutesTotal", 0),
                        parentTimeGrantId = json.optLong("parentTimeGrantId", 0L)
                    )
                    GuardianPrefs.saveScheduleSync(
                        this,
                        bedtimeSchedule = parseScheduleWindow(json.optJSONObject("bedtimeSchedule")),
                        studySchedule = parseScheduleWindow(json.optJSONObject("studySchedule")),
                        scheduledBlockActive = json.optBoolean("scheduledBlockActive", false),
                        scheduledBlockLabel = if (json.isNull("scheduledBlockLabel")) null else json.optString("scheduledBlockLabel", null),
                        scheduledBlockEndsAt = if (json.isNull("scheduledBlockEndsAt")) null else json.optString("scheduledBlockEndsAt", null)
                    )
                    notifyRuleChangesIfAny(wasPauseAllActive, previousDailyLimit, previousBlockedPackages, newIsPauseAllActive, newDailyLimit, blockedPackages)
                    // "Zerar tempo usado hoje" (botão do app do pai) — zera o contador
                    // LOCAL de verdade (é ele quem decide o bloqueio por tempo esgotado,
                    // não o valor exibido no painel do pai) e confirma pro backend, pra
                    // não ficar mandando esse pedido nos próximos polls.
                    if (json.optBoolean("resetUsageRequested", false)) {
                        GuardianPrefs.resetUsedMinutesToday(this)
                        postJson("/api/device/reset-usage-ack", "{}")
                        tickHandler.post { Toast.makeText(this, "🔄 Seu tempo de tela foi reiniciado!", Toast.LENGTH_LONG).show() }
                    }
                    // Deixa em cache pra LauncherHomeActivity tentar este endereço primeiro
                    // ao enviar uma foto, em vez de percorrer todos os candidatos de novo.
                    GuardianPrefs.setLastWorkingServerUrl(this, baseUrl)
                    connection.disconnect()
                    // As regras acabaram de mudar (ou foram confirmadas) — reavalia de
                    // novo pra refletir na hora, sem esperar o próximo tick de 1min.
                    tickHandler.post { reevaluateBlockState() }
                    return SyncFlags(
                        forceFreshLocation = json.optBoolean("locationUpdateRequested", false),
                        forceDeviceSync = json.optBoolean("deviceSyncRequested", false)
                    )
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Tenta o próximo candidato; se nenhum responder, mantém o cache antigo.
            }
        }
        return null
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
                GuardianPrefs.deviceToken(this)?.let { connection.setRequestProperty("X-Device-Token", it) }
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
     * Manda a fila local de "apps abertos" (ver GuardianPrefs.recordAppOpened) pro
     * backend e só limpa a fila local se a confirmação chegou — se a rede cair no meio,
     * a próxima tentativa reenvia tudo de novo (o backend deduplica por timestamp, ver
     * /api/device/usage-history-sync, então reenviar não duplica nada do lado de lá).
     */
    private fun postUsageHistory() {
        val pending = GuardianPrefs.pendingAppHistoryJson(this)
        val pendingArray = try { JSONArray(pending) } catch (e: Exception) { JSONArray() }
        if (pendingArray.length() == 0) return
        val body = JSONObject().put("history", pendingArray).toString()
        if (postJson("/api/device/usage-history-sync", body)) {
            GuardianPrefs.removeAppHistoryEntries(this, pendingArray)
        }
    }

    /**
     * POST /api/device/telemetry-sync com bateria, tipo de rede (+ nome do Wi-Fi quando
     * conectado), modelo do aparelho e tempo de tela usado hoje — mesmo padrão de "sempre
     * vivo" do postInstalledApps (antes esses campos só chegavam via 'child:telemetry' da
     * WebView, que quase nunca abre no uso normal — o painel do pai ficava com um número
     * de "tempo usado" desatualizado o dia inteiro, mesmo o bloqueio por tempo esgotado
     * já valendo de verdade localmente). Roda sozinho a cada ~5min ou na hora quando o
     * pai pede sync.
     */
    private fun postDeviceTelemetry() {
        val body = JSONObject()
            .put("batteryLevel", currentBatteryLevel())
            .put("networkType", currentNetworkDescription())
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("usedMinutesToday", GuardianPrefs.usedMinutesToday(this))
            // Progresso da liberação "tempo de uso" em andamento — só pro app do pai mostrar
            // "usou Ymin de Xmin liberados" (ver TimeRequestsFragment); o bloqueio em si já
            // é decidido 100% local (GuardianPrefs.isParentTimeGrantActive), não depende
            // deste POST chegar. Manda junto o ID da liberação, pra o backend só aceitar o
            // valor se ainda for a mesma concessão (ver /api/device/telemetry-sync no server).
            .put("parentTimeGrantId", GuardianPrefs.parentTimeGrantId(this))
            .put("parentTimeGrantUsageMinutesUsed", GuardianPrefs.parentTimeGrantUsageMinutesUsed(this))
            .toString()
        postJson("/api/device/telemetry-sync", body)
    }

    private fun currentBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * "Wi-Fi (NomeDaRede)" / "Dados móveis" / "Sem conexão" — ler o SSID exige permissão
     * de localização (já concedida pra GPS), sem ela cai só em "Wi-Fi" sem o nome.
     */
    private fun currentNetworkDescription(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "Desconhecido"
        val network = cm.activeNetwork ?: return "Sem conexão"
        val capabilities = cm.getNetworkCapabilities(network) ?: return "Sem conexão"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = currentWifiSsid()
                if (ssid != null) "Wi-Fi ($ssid)" else "Wi-Fi"
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Dados móveis"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Conectado"
        }
    }

    private fun currentWifiSsid(): String? {
        if (!hasLocationPermission()) return null
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val ssid = wm.connectionInfo?.ssid ?: return null
            val cleaned = ssid.removePrefix("\"").removeSuffix("\"")
            if (cleaned.isBlank() || cleaned == "<unknown ssid>") null else cleaned
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Ack de um desbloqueio local por PIN (ver currentBlockReason/GuardianPrefs.setPinOverrideUntil) —
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
     * Horário de Dormir/Estudo > Tarefas pendentes > Tempo esgotado — usado tanto pelo
     * ticker (pra travar um app já aberto quando o motivo muda no meio da sessão) quanto
     * pelo onAccessibilityEvent (pra decidir travar antes de um app abrir). Retorna null
     * se nada estiver bloqueando.
     */
    private fun currentBlockReason(): Pair<String, String>? {
        return when {
            // Válvula de emergência: se o responsável digitou o PIN certo na tela de
            // bloqueio, suspende TODOS os motivos abaixo pelo tempo que ele escolheu
            // (10/20/30min, 1h ou personalizado — ver o seletor em LockOverlayService)
            // — não só Pausa Geral, senão o "liberado" vira mentira assim que bater o
            // horário de tarefa pendente ou tempo esgotado de novo.
            GuardianPrefs.isPinOverrideActive(this) -> null
            // Janela curta (poucos minutos) aberta pelo botão "Enviar Foto" da tela de
            // tarefas pendentes — sem isso, o próprio app de câmera do sistema seria
            // barrado de volta pra tela de bloqueio assim que abrisse.
            GuardianPrefs.isTaskSubmissionWindowActive(this) -> null
            GuardianPrefs.isPauseAllActive(this) ->
                "🔒 DISPOSITIVO BLOQUEADO" to "Pausa Geral Ativa.\nFale com seus pais para liberar o uso."
            // Horário de Dormir/Estudo — calculado pelo backend a cada poll (relógio do
            // servidor, não do celular, ver activeScheduleBlock em server.js). Mesma
            // prioridade/comportamento de Pausa Geral: bloqueia tudo, permite PIN de
            // emergência (allowPinUnlock=true logo abaixo em reevaluateBlockState).
            GuardianPrefs.isScheduledBlockActive(this) -> {
                val label = GuardianPrefs.scheduledBlockLabel(this) ?: "🔒 HORÁRIO BLOQUEADO"
                val endsAt = GuardianPrefs.scheduledBlockEndsAt(this)
                val message = if (endsAt != null) "Volta a liberar às $endsAt.\nFale com seus pais se for urgente." else "Fale com seus pais se for urgente."
                label to message
            }
            // Título/ícone diferentes dos outros motivos globais (📋 em vez de 🔒) e a
            // mensagem cita a(s) tarefa(s) específica(s) por nome — antes era um texto
            // genérico igual pra qualquer situação, fácil de confundir com Pausa Geral
            // (foi exatamente essa confusão que motivou essa mudança).
            GuardianPrefs.isTaskGateBlocking(this) -> {
                val pending = GuardianPrefs.parsedTodayTasks(this).filter { it.status != "approved" }
                val detail = when {
                    pending.size == 1 -> "Falta 1 tarefa: ${pending.first().title}."
                    pending.isEmpty() -> "Ainda há uma tarefa pendente de hoje."
                    else -> "Faltam ${pending.size} tarefas de hoje."
                }
                "📋 TAREFA PENDENTE" to "$detail\nComplete e envie a foto na lista abaixo para liberar o celular."
            }
            GuardianPrefs.isDailyLimitExceeded(this) ->
                "⏳ TEMPO ESGOTADO" to "Você já usou todo o tempo de tela de hoje.\nFale com seus pais para liberar mais tempo."
            else -> null
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        ruleSyncRunning = false
        if (screenStateReceiverRegistered) {
            try { unregisterReceiver(screenStateReceiver) } catch (e: Exception) { /* já desregistrado */ }
            screenStateReceiverRegistered = false
        }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val previousPackage = lastForegroundPackage

            // Teclado, câmera padrão do aparelho, diálogo de permissão ou folha de
            // "compartilhar com" que aparecem POR CIMA de um app já liberado como "sempre
            // disponível" (ex: digitar ou abrir a câmera dentro do WhatsApp) disparam esse
            // mesmo evento com o pacote DELES, não do WhatsApp — sem esse filtro,
            // reevaluateBlockState via isso como "a criança trocou pra um app não liberado"
            // e reexibia o bloqueio por cima do app que já estava liberado (bug relatado:
            // WhatsApp acessível, mas digitar/abrir câmera trazia a tela de bloqueio de
            // volta). Só ignora quando o app de origem era mesmo sempre-disponível — uma
            // troca de verdade pra outro app bloqueado continua bloqueando normalmente,
            // porque esse outro app nunca cai em isTransientHelperPackage.
            if (packageName != previousPackage &&
                previousPackage != null &&
                GuardianPrefs.alwaysAvailablePackages(this).contains(previousPackage) &&
                isTransientHelperPackage(packageName)
            ) {
                return
            }

            // Só registra no histórico numa troca de app DE VERDADE (evita duplicar a
            // cada evento de acessibilidade solto dentro do mesmo app — diálogos,
            // trocas de tela internas etc. também disparam TYPE_WINDOW_STATE_CHANGED).
            // Exclui o próprio GuardianShield: "voltou pra Home" não é um app que o pai
            // precisa ver no histórico de uso.
            if (packageName != previousPackage && packageName != this.packageName) {
                val openedAt = System.currentTimeMillis()
                historyExecutor.execute { GuardianPrefs.recordAppOpened(this, packageName, getAppNameFromPackage(packageName), openedAt) }
            }
            lastForegroundPackage = packageName
            reevaluateBlockState()
        }
    }

    /**
     * Teclado (IME padrão) e câmera padrão do aparelho são resolvidos em runtime — funciona
     * em qualquer fabricante/app padrão escolhido pelo usuário, sem precisar listar pacote
     * por pacote (ex: teclado da Samsung, Gboard, câmera da Samsung etc.). O resto vem da
     * lista fixa SYSTEM_HELPER_PACKAGES.
     */
    private fun isTransientHelperPackage(packageName: String): Boolean {
        if (packageName in SYSTEM_HELPER_PACKAGES) return true
        if (packageName == defaultInputMethodPackage()) return true
        if (packageName == defaultCameraPackage()) return true
        return false
    }

    private fun defaultInputMethodPackage(): String? {
        val value = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (e: Exception) {
            null
        }
        if (value.isNullOrBlank()) return null
        return value.substringBefore('/').ifBlank { null }
    }

    private fun defaultCameraPackage(): String? {
        return try {
            packageManager.resolveActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (e: Exception) {
            null
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

        // Apps marcados pelo pai como "sempre disponível" (ver GuardianPrefs.alwaysAvailablePackages)
        // furam TODOS os bloqueios — Pausa Geral, tarefas pendentes, tempo esgotado e
        // bloqueio individual — por isso essa checagem vem antes de qualquer outra e
        // sempre libera o app na hora, mesmo em meio a um bloqueio global ativo.
        if (GuardianPrefs.alwaysAvailablePackages(this).contains(packageName)) {
            hideOverlay()
            return
        }

        // A Home/Gaveta nativas (pacote do próprio GuardianShield) NÃO têm mais exceção
        // especial: um bloqueio geral ativo (Pausa Geral, horário, tarefas pendentes,
        // tempo esgotado) cobre a Home/Gaveta igual cobre qualquer outro app — apertar o
        // botão Home ou tentar abrir a gaveta durante um bloqueio volta a mostrar a tela
        // de bloqueio, não escapa pra trás dela. Segue o fluxo normal aqui embaixo (a
        // Home só passa incólume quando NENHUM motivo de bloqueio está ativo).

        val blockReason = currentBlockReason()
        val blockedPackagesSet = GuardianPrefs.blockedPackages(this)
        val isBlocked = blockedPackagesSet.contains(packageName) || isPackageBlockedFallback(packageName, blockedPackagesSet)

        Log.d("GuardianShield", "App em 1º plano: $packageName | Bloqueio: ${blockReason?.first ?: "nenhum"} | Bloqueados: $blockedPackagesSet")

        // 0. Configurações do Android (com.android.settings) continuam SEMPRE alcançáveis
        // em meio a qualquer bloqueio — geral ou individual — desde que o PIN dos pais seja
        // digitado ali mesmo, ou até que o pai libere pelo app. Sem essa exceção, um
        // bloqueio travado de verdade (regra esquecida ligada, ou um bug) não teria como
        // ser corrigido no próprio aparelho — nem pra revogar autorização do adb, nem pra
        // desligar a Acessibilidade se precisar (foi exatamente o que aconteceu num teste).
        // allowPinUnlock=true incondicional aqui, mesmo quando o motivo normal do bloqueio
        // não permitiria PIN (ex: app individual bloqueado) — Configurações é sempre exceção.
        if (packageName in SETTINGS_PACKAGES && (blockReason != null || isBlocked)) {
            val message = if (GuardianPrefs.hasUnlockPinConfigured(this))
                "Digite o PIN dos seus pais pra continuar, ou peça pra eles liberarem pelo app."
            else
                "Peça pra seus pais liberarem pelo app do GuardianShield (nenhum PIN foi configurado ainda)."
            showOverlay("🔧 CONFIGURAÇÕES PROTEGIDAS", message, allowPinUnlock = true, iconType = "settings")
            bringGuardianShieldToFront()
            return
        }

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
            val isTaskGate = GuardianPrefs.isTaskGateBlocking(this)
            showOverlay(title, message, allowPinUnlock = true, allowTaskSubmission = isTaskGate, iconType = if (isTaskGate) "tasks" else "lock")
            bringGuardianShieldToFront()
            return
        }

        // 2. Sem bloqueio global, verifica se o aplicativo em 1º plano está bloqueado
        // individualmente. allowPinUnlock=false aqui: o PIN de emergência é pra
        // destravar o aparelho, não pra liberar um app específico que o pai escolheu
        // bloquear de propósito (ver currentBlockReason, que não olha pra isso).
        if (isBlocked) {
            Log.w("GuardianShield", "App Bloqueado Detectado: $packageName. Exibindo tela de bloqueio do app!")
            val appLabel = getAppNameFromPackage(packageName)
            showOverlay("$appLabel bloqueado", "Esse aplicativo foi bloqueado pelos seus pais.", allowPinUnlock = false, iconType = "blocked")
            bringGuardianShieldToFront()
        } else {
            // App permitido (ou o bloqueio acabou de ser removido pelo pai) — remove a
            // sobreposição se estiver visível.
            hideOverlay()
        }
    }

    /**
     * Camada de segurança sobre o Set.contains exato (case-sensitive) acima — só cobre uma
     * comparação insensível a maiúsculas/minúsculas, caso algum evento de acessibilidade
     * estranho traga o pacote com uma grafia diferente da instalada.
     *
     * NÃO faz mais comparação por substring ("tiktok"/"instagram"/"youtube"/"freefire" no
     * meio do nome do pacote, como antes) — removido de propósito: blockedPackages sempre
     * chega aqui como o nome de pacote REAL e completo que o pai escolheu na lista de apps
     * sincronizada do próprio aparelho da criança (ver postInstalledApps acima e
     * AppsFragment no parent-app-native, que só oferece pra bloquear apps que já vieram
     * desse apps-sync) — nunca um termo solto tipo "tiktok". A heurística de substring só
     * trazia risco de SUPERBLOQUEAR apps que o pai nunca escolheu: bloquear "youtube" batia
     * também no YouTube Music e no YouTube Kids, que são pacotes totalmente diferentes.
     */
    private fun isPackageBlockedFallback(pkg: String, blockedSet: Set<String>): Boolean =
        blockedSet.any { it.equals(pkg, ignoreCase = true) }

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

    // Assinatura do último "mostra" enviado — o mesmo bloqueio reavaliado de novo (cada troca
    // de janela, cada tick, cada sincronização) não precisa reenviar nada pro serviço.
    private var lastOverlaySignature: String? = null

    private fun showOverlay(title: String, message: String, allowPinUnlock: Boolean, allowTaskSubmission: Boolean = false, iconType: String = "lock") {
        val signature = "$title|$message|$allowPinUnlock|$allowTaskSubmission|$iconType"
        if (LockOverlayService.showing && signature == lastOverlaySignature) return
        lastOverlaySignature = signature
        val overlayIntent = Intent(this, LockOverlayService::class.java)
        overlayIntent.action = "SHOW_OVERLAY"
        overlayIntent.putExtra("title", title)
        overlayIntent.putExtra("message", message)
        overlayIntent.putExtra("allowPinUnlock", allowPinUnlock)
        overlayIntent.putExtra("allowTaskSubmission", allowTaskSubmission)
        overlayIntent.putExtra("iconType", iconType)
        startService(overlayIntent)
    }

    private fun hideOverlay() {
        lastOverlaySignature = null
        if (!LockOverlayService.showing) return
        val overlayIntent = Intent(this, LockOverlayService::class.java)
        overlayIntent.action = "HIDE_OVERLAY"
        startService(overlayIntent)
    }

    /**
     * Traz a Home nativa pra frente — é o "lugar seguro" pra onde a criança deve voltar
     * quando um app é bloqueado (e rede de segurança pro caso da sobreposição não
     * conseguir aparecer, ex: permissão SYSTEM_ALERT_WINDOW revogada — pelo menos o app
     * bloqueado sai do 1º plano mesmo assim). A própria Home, ao vir pra frente, passa
     * de novo por reevaluateBlockState (novo evento de acessibilidade) e mostra a
     * sobreposição por cima dela também, já que ela não tem mais exceção especial.
     */
    private fun bringGuardianShieldToFront() {
        // Já está num app nosso (Home/painel por baixo da tela de bloqueio): nada a fazer —
        // relançar a Home a cada reavaliação só a fazia redesenhar à toa.
        if (lastForegroundPackage == packageName) return
        val intent = Intent(this, LauncherHomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.e("GuardianShield", "Serviço de acessibilidade interrompido.")
    }
}
