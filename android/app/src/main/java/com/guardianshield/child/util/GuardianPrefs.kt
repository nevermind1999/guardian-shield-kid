package com.guardianshield.child.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * Ponto único de leitura do SharedPreferences "GuardianShieldPrefs". O
 * ParentalAccessibilityService grava aqui as regras que vêm do backend; a Home/Gaveta
 * nativas, a tela de bloqueio e o painel (PainelActivity) leem daqui, então mudanças de
 * chave só precisam ser feitas neste arquivo.
 */
object GuardianPrefs {
    private const val PREFS_NAME = "GuardianShieldPrefs"
    private const val KEY_PINNED_ORDERED = "pinnedHomeAppsOrdered"
    private const val KEY_PINNED_LEGACY = "pinnedHomeApps" // Set<String> sem ordem (versão anterior)
    private const val ORDER_DELIMITER = "|||"
    private const val KEY_VIDEO_WALLPAPER_URI = "homeVideoWallpaperUri"

    // Paleta de temas disponível para o usuário escolher em "Personalizar tela inicial".
    // Cada par é [corInicial, corFinal] de um degradê. Repaginação laranja/quente
    // (era azul/roxo por padrão) pra bater com a nova identidade dos 3 apps.
    val THEME_PRESETS: List<Pair<String, String>> = listOf(
        "#F97316" to "#EA580C", // laranja (padrão)
        "#3B82F6" to "#2563EB", // azul
        "#A855F7" to "#7E22CE", // roxo
        "#10B981" to "#059669", // esmeralda
        "#F43F5E" to "#E11D48", // rosa
        "#EAB308" to "#CA8A04", // amarelo
        "#14B8A6" to "#0D9488", // teal
        "#57534E" to "#292524"  // escuro monocromático
    )
    private val DEFAULT_THEME = THEME_PRESETS[0]
    val GRID_COLUMN_OPTIONS = listOf(3, 4, 5, 6)
    private const val DEFAULT_GRID_COLUMNS = 4

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPauseAllActive(context: Context): Boolean =
        of(context).getBoolean("isPauseAllActive", false)

    fun blockedPackages(context: Context): Set<String> =
        of(context).getStringSet("blockedPackagesSet", emptySet()) ?: emptySet()

    /**
     * Apps que o pai marcou como "sempre disponível" — ficam liberados mesmo com Pausa
     * Geral, tarefas pendentes, tempo esgotado ou bloqueio individual ativos (ver o
     * early-return em ParentalAccessibilityService.reevaluateBlockState).
     */
    fun alwaysAvailablePackages(context: Context): Set<String> =
        of(context).getStringSet("alwaysAvailablePackagesSet", emptySet()) ?: emptySet()

    fun dailyLimitMinutes(context: Context): Int =
        of(context).getInt("dailyLimitMinutes", 120)

    // --- Tempo de tela usado hoje: contado localmente pelo ParentalAccessibilityService
    // (ele fica vivo o tempo todo enquanto a Acessibilidade estiver habilitada, diferente
    // da WebView/React que só existe quando a criança abre as Configurações). Zera sozinho
    // quando o dia muda, comparando com a última data registrada. ---

    private const val KEY_USED_MINUTES = "usedMinutesToday"
    private const val KEY_USAGE_DATE = "usageTrackedDate"

    fun usedMinutesToday(context: Context): Int {
        ensureUsageDateCurrent(context)
        return of(context).getInt(KEY_USED_MINUTES, 0)
    }

    fun incrementUsedMinutesToday(context: Context, byMinutes: Int = 1) {
        ensureUsageDateCurrent(context)
        val prefs = of(context)
        val current = prefs.getInt(KEY_USED_MINUTES, 0)
        prefs.edit().putInt(KEY_USED_MINUTES, current + byMinutes).apply()
    }

    fun isDailyLimitExceeded(context: Context): Boolean {
        if (isParentTimeGrantActive(context)) return false
        return usedMinutesToday(context) >= dailyLimitMinutes(context)
    }

    // --- Liberação manual do pai, escolhida na hora pelo app (ver parent:grant_bonus_time
    // no backend) — ao contrário do override do PIN (que suspende TUDO, inclusive Pausa
    // Geral), este suspende só o gate de tarefa ('all_or_nothing') e o tempo esgotado
    // (isTaskGateBlocking/isDailyLimitExceeded acima), respeitando Pausa Geral e horário
    // agendado ainda ativos. Sincronizado a cada poll, então funciona mesmo se o pai
    // conceder com o Filho já bloqueado — o próximo /api/tasks/sync já traz a liberação.
    //
    // Dois modos (escolhidos pelo pai na hora de conceder, ver TimeRequestsFragment):
    // "clock" (padrão/antigo) — a liberação vale até um horário fixo, "tempo corrido":
    // continua contando mesmo se a criança nem tocar no aparelho nesse meio tempo.
    // "usage" — a liberação vale por N minutos de uso DE VERDADE: o contador só anda
    // enquanto a tela está ligada (mesmo relógio que usedMinutesToday, incrementado no
    // ticker de 1min — ver incrementParentTimeGrantUsageMinuteIfActive), então se a
    // criança guardar o celular no meio da liberação, o tempo concedido não escoa à toa. ---

    fun parentTimeGrantUntil(context: Context): Long = of(context).getLong(KEY_PARENT_TIME_GRANT_UNTIL, 0L)

    /** "clock" (tempo corrido, padrão) ou "usage" (tempo de uso). */
    fun parentTimeGrantMode(context: Context): String =
        of(context).getString(KEY_PARENT_TIME_GRANT_MODE, "clock") ?: "clock"

    fun parentTimeGrantUsageMinutesTotal(context: Context): Int =
        of(context).getInt(KEY_PARENT_TIME_GRANT_USAGE_TOTAL, 0)

    fun parentTimeGrantUsageMinutesUsed(context: Context): Int =
        of(context).getInt(KEY_PARENT_TIME_GRANT_USAGE_USED, 0)

    /** Identificador da concessão em andamento (Date.now() do backend no momento em que o pai concedeu) — 0 = nenhuma. */
    fun parentTimeGrantId(context: Context): Long =
        of(context).getLong(KEY_PARENT_TIME_GRANT_ID, 0L)

    fun isParentTimeGrantActive(context: Context): Boolean =
        if (parentTimeGrantMode(context) == "usage") {
            parentTimeGrantUsageMinutesUsed(context) < parentTimeGrantUsageMinutesTotal(context)
        } else {
            System.currentTimeMillis() < parentTimeGrantUntil(context)
        }

    /** Minutos restantes da liberação manual em andamento (0 se não houver nenhuma ativa) — pra exibir "liberado por mais Xmin" na Home/tela de bloqueio, se precisar. */
    fun parentTimeGrantRemainingMinutes(context: Context): Int {
        if (parentTimeGrantMode(context) == "usage") {
            val remaining = parentTimeGrantUsageMinutesTotal(context) - parentTimeGrantUsageMinutesUsed(context)
            return if (remaining > 0) remaining else 0
        }
        val remainingMs = parentTimeGrantUntil(context) - System.currentTimeMillis()
        return if (remainingMs > 0) ((remainingMs + 59_999L) / 60_000L).toInt() else 0
    }

    /**
     * Chamado pelo ticker de 1min (ParentalAccessibilityService.tickUsageOnce), só quando a
     * tela está ligada — mesmo gate que incrementUsedMinutesToday. Consome 1 minuto da
     * liberação "tempo de uso" em andamento, se houver uma ativa; não faz nada em modo
     * "clock" (esse consome sozinho, só pelo relógio) nem se já esgotou o total concedido.
     */
    fun incrementParentTimeGrantUsageMinuteIfActive(context: Context) {
        if (parentTimeGrantMode(context) != "usage") return
        val prefs = of(context)
        val used = prefs.getInt(KEY_PARENT_TIME_GRANT_USAGE_USED, 0)
        val total = prefs.getInt(KEY_PARENT_TIME_GRANT_USAGE_TOTAL, 0)
        if (used < total) prefs.edit().putInt(KEY_PARENT_TIME_GRANT_USAGE_USED, used + 1).apply()
    }

    /** Zera o contador de uso na hora — usado em resposta a resetUsageRequested (botão
     * "Zerar tempo usado hoje" do app do pai, ver ParentalAccessibilityService.pollRulesSync). */
    fun resetUsedMinutesToday(context: Context) {
        ensureUsageDateCurrent(context)
        of(context).edit().putInt(KEY_USED_MINUTES, 0).apply()
    }

    // --- Aviso único por dia de "tempo quase acabando" (ver maybeWarnLowTimeRemaining em
    // ParentalAccessibilityService) — mesmo padrão de reset diário do contador de uso acima. ---
    private const val KEY_LOW_TIME_WARNING_DATE = "lowTimeWarningShownDate"

    fun hasShownLowTimeWarningToday(context: Context): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        return of(context).getString(KEY_LOW_TIME_WARNING_DATE, null) == today
    }

    fun markLowTimeWarningShownToday(context: Context) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        of(context).edit().putString(KEY_LOW_TIME_WARNING_DATE, today).apply()
    }

    /** Zera o contador de uso quando a data mudou desde a última leitura/escrita. */
    private fun ensureUsageDateCurrent(context: Context) {
        val prefs = of(context)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val savedDate = prefs.getString(KEY_USAGE_DATE, null)
        if (savedDate != today) {
            prefs.edit().putString(KEY_USAGE_DATE, today).putInt(KEY_USED_MINUTES, 0).apply()
        }
    }

    // --- Tarefas diárias + regras de bloqueio (pausa geral, apps bloqueados, limite diário):
    // o backend é a fonte da verdade (o pai edita tudo isso pelo app dele). O
    // ParentalAccessibilityService consulta GET /api/tasks/sync (long-poll — volta na hora
    // quando o pai muda algo) e grava o resultado aqui. A Home, a tela de bloqueio e o
    // painel leem só daqui, nunca da rede diretamente, então continuam funcionando com o
    // último valor conhecido mesmo sem internet. ---

    private const val KEY_TASK_UNLOCK_MODE = "taskUnlockMode"
    private const val KEY_TASKS_SYNC_JSON = "tasksSyncJson"
    private const val KEY_PARENT_TIME_GRANT_UNTIL = "parentTimeGrantUntilMillis"
    private const val KEY_PARENT_TIME_GRANT_MODE = "parentTimeGrantMode"
    private const val KEY_PARENT_TIME_GRANT_USAGE_TOTAL = "parentTimeGrantUsageMinutesTotal"
    private const val KEY_PARENT_TIME_GRANT_USAGE_USED = "parentTimeGrantUsageMinutesUsed"
    // Identifica CADA concessão (Date.now() no backend no momento de conceder) — só serve
    // pra este device perceber "essa é uma liberação NOVA" e zerar o contador de minutos já
    // usados (KEY_PARENT_TIME_GRANT_USAGE_USED); sem isso, uma 2ª liberação em modo "usage"
    // continuaria de onde a anterior parou (ou já apareceria "esgotada" se o total for igual).
    private const val KEY_PARENT_TIME_GRANT_ID = "parentTimeGrantId"

    data class TaskItem(
        val id: String,
        val title: String,
        val icon: String,
        val rewardMinutes: Int,
        val status: String, // "pending" | "submitted" | "approved" | "rejected"
        val rejectedReason: String?,
        // Timestamp (epoch millis) até quando o pai "adiou" esta tarefa — enquanto não
        // passar, ela não conta como pendência bloqueante no modo "tudo ou nada" (ver
        // isTaskGateBlocking), mesmo com status ainda 'pending'. Null = não adiada.
        val snoozeUntil: Long?
    )

    fun taskUnlockMode(context: Context): String =
        of(context).getString(KEY_TASK_UNLOCK_MODE, "off") ?: "off"

    /**
     * Chamado só pelo poll do ParentalAccessibilityService, depois de um GET /api/tasks/sync
     * bem-sucedido — grava de uma vez tudo que o backend manda: tarefas, limite diário
     * (o backend já calcula o valor efetivo certo pra cada modo, então pode gravar sempre,
     * sem condicional), pausa geral e apps bloqueados individualmente.
     */
    fun saveRulesSync(
        context: Context,
        unlockMode: String,
        dailyLimitMinutes: Int,
        isPauseAllActive: Boolean,
        blockedPackages: Set<String>,
        alwaysAvailablePackages: Set<String>,
        unlockPinHash: String?,
        emergencyPhone: String?,
        rawTasksJson: String,
        parentTimeGrantUntil: Long = 0L,
        parentTimeGrantMode: String = "clock",
        parentTimeGrantUsageMinutesTotal: Int = 0,
        parentTimeGrantId: Long = 0L
    ) {
        val prefs = of(context)
        // Liberação "usage" nova (grantId mudou desde o último poll): zera o contador de
        // minutos já consumidos, senão uma 2ª concessão herdaria o progresso da anterior.
        val isNewGrant = parentTimeGrantId != 0L && parentTimeGrantId != prefs.getLong(KEY_PARENT_TIME_GRANT_ID, 0L)

        val editor = prefs.edit()
            .putString(KEY_TASK_UNLOCK_MODE, unlockMode)
            .putString(KEY_TASKS_SYNC_JSON, rawTasksJson)
            .putInt("dailyLimitMinutes", dailyLimitMinutes)
            .putBoolean("isPauseAllActive", isPauseAllActive)
            .putStringSet("blockedPackagesSet", blockedPackages)
            .putStringSet("alwaysAvailablePackagesSet", alwaysAvailablePackages)
            .putLong(KEY_PARENT_TIME_GRANT_UNTIL, parentTimeGrantUntil)
            .putString(KEY_PARENT_TIME_GRANT_MODE, parentTimeGrantMode)
            .putInt(KEY_PARENT_TIME_GRANT_USAGE_TOTAL, parentTimeGrantUsageMinutesTotal)
            .putLong(KEY_PARENT_TIME_GRANT_ID, parentTimeGrantId)
        if (isNewGrant) editor.putInt(KEY_PARENT_TIME_GRANT_USAGE_USED, 0)
        if (unlockPinHash != null) editor.putString(KEY_UNLOCK_PIN_HASH, unlockPinHash) else editor.remove(KEY_UNLOCK_PIN_HASH)
        if (emergencyPhone != null) editor.putString(KEY_EMERGENCY_PHONE, emergencyPhone) else editor.remove(KEY_EMERGENCY_PHONE)
        editor.apply()
    }

    // --- Telefone de emergência: o pai cadastra no app dele; o botão "🚨 Emergência" (Home
    // e tela de bloqueio) abre o discador do sistema já com esse número, sem exigir a
    // permissão perigosa CALL_PHONE — o toque final de ligar continua sendo do usuário. ---
    private const val KEY_EMERGENCY_PHONE = "emergencyPhone"

    fun emergencyPhone(context: Context): String? =
        of(context).getString(KEY_EMERGENCY_PHONE, null)?.takeIf { it.isNotBlank() }

    // --- Horário de Dormir / Horário de Estudo: o backend é quem decide se está bloqueando
    // AGORA (relógio do servidor, não do celular — ver activeScheduleBlock em server.js),
    // sincronizado a cada poll em scheduledBlockActive/Label/EndsAt. Os horários brutos
    // (bedtimeSchedule/studySchedule) ficam salvos à parte só pra Home calcular e mostrar
    // o PRÓXIMO bloqueio agendado (decorativo, com o relógio do próprio aparelho) mesmo
    // quando nada está bloqueando ainda — ver nextScheduledBlockLabel em LauncherHomeActivity. ---

    data class ScheduleWindow(val enabled: Boolean, val start: String, val end: String)

    fun saveScheduleSync(
        context: Context,
        bedtimeSchedule: ScheduleWindow,
        studySchedule: ScheduleWindow,
        scheduledBlockActive: Boolean,
        scheduledBlockLabel: String?,
        scheduledBlockEndsAt: String?
    ) {
        of(context).edit()
            .putBoolean("bedtimeEnabled", bedtimeSchedule.enabled)
            .putString("bedtimeStart", bedtimeSchedule.start)
            .putString("bedtimeEnd", bedtimeSchedule.end)
            .putBoolean("studyEnabled", studySchedule.enabled)
            .putString("studyStart", studySchedule.start)
            .putString("studyEnd", studySchedule.end)
            .putBoolean("scheduledBlockActive", scheduledBlockActive)
            .putString("scheduledBlockLabel", scheduledBlockLabel)
            .putString("scheduledBlockEndsAt", scheduledBlockEndsAt)
            .apply()
    }

    fun bedtimeSchedule(context: Context): ScheduleWindow {
        val p = of(context)
        return ScheduleWindow(p.getBoolean("bedtimeEnabled", false), p.getString("bedtimeStart", "22:00") ?: "22:00", p.getString("bedtimeEnd", "07:00") ?: "07:00")
    }

    fun studySchedule(context: Context): ScheduleWindow {
        val p = of(context)
        return ScheduleWindow(p.getBoolean("studyEnabled", false), p.getString("studyStart", "14:00") ?: "14:00", p.getString("studyEnd", "17:00") ?: "17:00")
    }

    fun isScheduledBlockActive(context: Context): Boolean = of(context).getBoolean("scheduledBlockActive", false)
    fun scheduledBlockLabel(context: Context): String? = of(context).getString("scheduledBlockLabel", null)
    fun scheduledBlockEndsAt(context: Context): String? = of(context).getString("scheduledBlockEndsAt", null)

    /**
     * Resumo curto (1 linha) do motivo GERAL de bloqueio atual, na mesma prioridade e
     * fontes de ParentalAccessibilityService.currentBlockReason — mas achatado num texto
     * só, pra telas que só precisam explicar rapidamente por que algo está bloqueado (ver
     * o toast ao tocar num ícone cinza em AppGridAdapter). Mantido como função separada
     * (não uma chamada direta pro serviço) porque o serviço nem sempre está vivo quando a
     * Home/Gaveta são as únicas coisas rodando; os dois lêem do mesmo GuardianPrefs, então
     * não saem de sincronia — só cuidado ao adicionar um novo motivo: atualizar os dois.
     * Retorna null se nada estiver bloqueando globalmente (não olha bloqueio individual
     * de app nem o override do PIN).
     */
    fun currentGlobalBlockLabel(context: Context): String? {
        if (isPinOverrideActive(context)) return null
        if (isTaskSubmissionWindowActive(context)) return null
        return when {
            isPauseAllActive(context) -> "Pausa Geral ativada pelos seus pais"
            isScheduledBlockActive(context) -> scheduledBlockLabel(context) ?: "Horário bloqueado"
            isTaskGateBlocking(context) -> {
                val pending = parsedTodayTasks(context).filter { it.status != "approved" && !isTaskSnoozed(it) }
                if (pending.size == 1) "Tarefa pendente: ${pending.first().title}" else "Tarefas pendentes de hoje"
            }
            isDailyLimitExceeded(context) -> "Tempo de tela esgotado"
            else -> null
        }
    }

    // --- PIN de emergência: desbloqueia o aparelho mesmo sem internet ---
    // Só o hash SHA-256 chega até aqui (sincronizado pelo poll acima, nunca o valor em
    // texto puro) — LockOverlayService compara o hash do que a criança digitou contra
    // isto, 100% localmente. Quando bate, pergunta por quanto tempo liberar (10/20/30min,
    // 1h ou personalizado — ver o seletor em LockOverlayService) e grava até QUANDO
    // (timestamp, não mais "o dia inteiro" fixo) o aparelho fica liberado — Pausa Geral,
    // tarefas pendentes, horário agendado e tempo esgotado, todos suspensos até lá. Local
    // 100%: nem pra valer nem pra expirar depende de rede.

    private const val KEY_UNLOCK_PIN_HASH = "unlockPinHash"
    private const val KEY_PIN_OVERRIDE_UNTIL = "pinOverrideUntilMillis"
    private const val KEY_PENDING_PIN_UNLOCK_ACK = "pendingPinUnlockAck"

    fun unlockPinHash(context: Context): String? = of(context).getString(KEY_UNLOCK_PIN_HASH, null)

    fun hasUnlockPinConfigured(context: Context): Boolean = !unlockPinHash(context).isNullOrEmpty()

    /** Libera o aparelho (suspende os bloqueios gerais) até o instante informado. */
    fun setPinOverrideUntil(context: Context, untilMillis: Long) {
        of(context).edit().putLong(KEY_PIN_OVERRIDE_UNTIL, untilMillis).apply()
    }

    fun isPinOverrideActive(context: Context): Boolean =
        System.currentTimeMillis() < of(context).getLong(KEY_PIN_OVERRIDE_UNTIL, 0L)

    /** Minutos restantes da liberação por PIN em andamento (0 se não houver nenhuma ativa)
     * — usado só pra exibir "liberado por mais Xmin" em algum aviso, se precisar. */
    fun pinOverrideRemainingMinutes(context: Context): Int {
        val remainingMs = of(context).getLong(KEY_PIN_OVERRIDE_UNTIL, 0L) - System.currentTimeMillis()
        return if (remainingMs > 0) ((remainingMs + 59_999L) / 60_000L).toInt() else 0
    }

    /** Fica true entre um desbloqueio local por PIN e a confirmação bem-sucedida pro backend. */
    fun pendingPinUnlockAck(context: Context): Boolean = of(context).getBoolean(KEY_PENDING_PIN_UNLOCK_ACK, false)

    fun setPendingPinUnlockAck(context: Context, pending: Boolean) {
        of(context).edit().putBoolean(KEY_PENDING_PIN_UNLOCK_ACK, pending).apply()
    }

    // --- Janela de exceção temporária pro bloqueio, usada só pra deixar a câmera do
    // sistema abrir sem ser barrada de volta pra tela de bloqueio (ver "Enviar Foto"
    // na tela de tarefas pendentes, em LockOverlayService). Curta (poucos minutos) e
    // por tempo, diferente do override do PIN que dura o dia inteiro. ---

    private const val KEY_TASK_SUBMISSION_UNTIL = "taskSubmissionAllowedUntil"

    fun setTaskSubmissionAllowedUntil(context: Context, untilMillis: Long) {
        of(context).edit().putLong(KEY_TASK_SUBMISSION_UNTIL, untilMillis).apply()
    }

    fun isTaskSubmissionWindowActive(context: Context): Boolean =
        System.currentTimeMillis() < of(context).getLong(KEY_TASK_SUBMISSION_UNTIL, 0L)

    // Cache do último parse (a mesma string é lida várias vezes por troca de app — motivo
    // do bloqueio, tela de bloqueio, Home…) — só reprocessa quando o JSON salvo muda.
    @Volatile private var parsedTasksCache: Pair<String, List<TaskItem>>? = null

    fun parsedTodayTasks(context: Context): List<TaskItem> {
        val raw = of(context).getString(KEY_TASKS_SYNC_JSON, null) ?: return emptyList()
        parsedTasksCache?.let { (cachedRaw, tasks) -> if (cachedRaw == raw) return tasks }
        return parseTasks(raw).also { parsedTasksCache = raw to it }
    }

    private fun parseTasks(raw: String): List<TaskItem> {
        return try {
            val root = org.json.JSONObject(raw)
            val dailyTasks = root.optJSONArray("dailyTasks") ?: org.json.JSONArray()
            val todayStatus = root.optJSONArray("todayStatus") ?: org.json.JSONArray()
            val statusById = (0 until todayStatus.length()).associate { i ->
                val obj = todayStatus.getJSONObject(i)
                obj.getString("taskId") to obj
            }
            (0 until dailyTasks.length()).map { i ->
                val obj = dailyTasks.getJSONObject(i)
                val id = obj.getString("id")
                val statusObj = statusById[id]
                TaskItem(
                    id = id,
                    title = obj.optString("title", "Tarefa"),
                    icon = obj.optString("icon", "✅"),
                    rewardMinutes = obj.optInt("rewardMinutes", 0),
                    status = statusObj?.optString("status", "pending") ?: "pending",
                    rejectedReason = statusObj?.let { if (it.isNull("rejectedReason")) null else it.optString("rejectedReason") },
                    snoozeUntil = statusObj?.let { if (it.isNull("snoozeUntil")) null else it.optLong("snoozeUntil") }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** true enquanto o pai tiver adiado esta tarefa e o prazo ainda não tiver passado. */
    fun isTaskSnoozed(task: TaskItem): Boolean =
        task.snoozeUntil != null && System.currentTimeMillis() < task.snoozeUntil

    /** true se o modo "tudo ou nada" estiver ativo e ainda faltar pelo menos 1 tarefa
     * aprovada hoje — tarefas adiadas pelo pai (ver isTaskSnoozed) não contam como
     * pendência enquanto o prazo do adiamento não passar. */
    fun isTaskGateBlocking(context: Context): Boolean {
        if (isParentTimeGrantActive(context)) return false
        if (taskUnlockMode(context) != "all_or_nothing") return false
        val tasks = parsedTodayTasks(context)
        if (tasks.isEmpty()) return false // sem tarefa cadastrada, não há o que travar
        return tasks.any { it.status != "approved" && !isTaskSnoozed(it) }
    }

    // --- Token de dispositivo (JWT) devolvido pelo backend ao completar o pareamento (ver
    // PainelActivity.parear). Sem expiração (perder o pareamento = parear de novo). Vai em
    // toda chamada HTTP (header X-Device-Token) pra o backend resolver a família certa —
    // ver resolveDeviceFamily em server.js. ---

    private const val KEY_DEVICE_TOKEN = "deviceToken"

    fun deviceToken(context: Context): String? =
        of(context).getString(KEY_DEVICE_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Pareado = já recebeu um token de dispositivo do backend (ver PainelActivity). */
    fun isPaired(context: Context): Boolean = deviceToken(context) != null

    fun setDeviceToken(context: Context, token: String?) {
        val editor = of(context).edit()
        if (token.isNullOrBlank()) editor.remove(KEY_DEVICE_TOKEN) else editor.putString(KEY_DEVICE_TOKEN, token)
        editor.apply()
    }

    // --- Histórico local de apps abertos (fila antes de sincronizar) — cada entrada vira
    // uma linha na tela de Histórico do app do Pai e alimenta o "último acesso" da Home
    // de lá. Guardado aqui (não só em memória) pra não perder nada se o processo morrer
    // entre um ciclo de sync e outro; ParentalAccessibilityService.postUsageHistory limpa
    // esta fila depois de confirmar o envio. ---

    private const val KEY_APP_HISTORY_QUEUE = "appUsageHistoryQueue"
    private const val MAX_LOCAL_HISTORY_QUEUE = 100

    /** Registra que `packageName` acabou de entrar em 1º plano — chamado só numa troca de
     * app DE VERDADE (ver lastForegroundPackage em ParentalAccessibilityService), nunca a
     * cada evento de acessibilidade solto. */
    fun recordAppOpened(context: Context, packageName: String, appLabel: String, openedAt: Long = System.currentTimeMillis()) {
        val prefs = of(context)
        val current = try {
            org.json.JSONArray(prefs.getString(KEY_APP_HISTORY_QUEUE, null) ?: "[]")
        } catch (e: Exception) {
            org.json.JSONArray()
        }
        current.put(
            org.json.JSONObject()
                .put("package", packageName)
                .put("name", appLabel)
                .put("timestamp", openedAt)
        )
        // Corta as mais antigas se a fila crescer demais (ex: dias sem internet) — perde
        // histórico bem antigo em vez de crescer sem limite no disco.
        val trimmed = if (current.length() > MAX_LOCAL_HISTORY_QUEUE) {
            org.json.JSONArray().apply {
                for (i in (current.length() - MAX_LOCAL_HISTORY_QUEUE) until current.length()) put(current.get(i))
            }
        } else current
        prefs.edit().putString(KEY_APP_HISTORY_QUEUE, trimmed.toString()).apply()
    }

    fun pendingAppHistoryJson(context: Context): String =
        of(context).getString(KEY_APP_HISTORY_QUEUE, null) ?: "[]"

    /**
     * Remove só as entradas que já foram confirmadas pelo backend (por timestamp), lendo
     * a fila de novo em vez de simplesmente apagar tudo — se um app novo abriu enquanto a
     * chamada de rede estava em andamento, essa entrada não estava no que foi mandado e
     * não pode ser perdida aqui.
     */
    fun removeAppHistoryEntries(context: Context, sentEntries: org.json.JSONArray) {
        val sentTimestamps = (0 until sentEntries.length())
            .map { sentEntries.getJSONObject(it).optLong("timestamp") }
            .toSet()
        val current = try {
            org.json.JSONArray(pendingAppHistoryJson(context))
        } catch (e: Exception) {
            return
        }
        val remaining = org.json.JSONArray()
        for (i in 0 until current.length()) {
            val entry = current.getJSONObject(i)
            if (entry.optLong("timestamp") !in sentTimestamps) remaining.put(entry)
        }
        of(context).edit().putString(KEY_APP_HISTORY_QUEUE, remaining.toString()).apply()
    }

    // Último endereço do backend que respondeu com sucesso a um poll de tarefas — deixado
    // em cache pra LauncherHomeActivity tentar primeiro ao enviar uma foto, em vez de
    // percorrer todos os candidatos de novo a cada envio.
    private const val KEY_LAST_WORKING_SERVER = "lastWorkingServerUrl"

    fun lastWorkingServerUrl(context: Context): String? =
        of(context).getString(KEY_LAST_WORKING_SERVER, null)

    fun setLastWorkingServerUrl(context: Context, url: String) {
        of(context).edit().putString(KEY_LAST_WORKING_SERVER, url).apply()
    }

    // --- Apps fixados na tela inicial, em ORDEM (o usuário reorganiza arrastando) ---

    fun hasInitializedPinnedApps(context: Context): Boolean {
        val prefs = of(context)
        return prefs.contains(KEY_PINNED_ORDERED) || prefs.contains(KEY_PINNED_LEGACY)
    }

    fun pinnedHomeApps(context: Context): List<String> {
        val prefs = of(context)
        val raw = prefs.getString(KEY_PINNED_ORDERED, null)
        if (raw != null) {
            return if (raw.isEmpty()) emptyList() else raw.split(ORDER_DELIMITER)
        }
        // Migração de uma versão anterior que guardava um Set sem ordem definida.
        val legacy = prefs.getStringSet(KEY_PINNED_LEGACY, null)
        if (legacy != null) {
            val migrated = legacy.toList()
            setPinnedHomeApps(context, migrated)
            return migrated
        }
        return emptyList()
    }

    fun setPinnedHomeApps(context: Context, packages: List<String>) {
        of(context).edit()
            .putString(KEY_PINNED_ORDERED, packages.joinToString(ORDER_DELIMITER))
            .remove(KEY_PINNED_LEGACY)
            .apply()
    }

    /** Fixa (se ainda não estava) ou desafixa (se já estava) — usado pela Gaveta. */
    fun togglePinned(context: Context, packageName: String): Boolean {
        val current = pinnedHomeApps(context).toMutableList()
        val nowPinned = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        setPinnedHomeApps(context, current)
        return nowPinned
    }

    /** Remove explicitamente — usado pelo menu "Remover" da Home. */
    fun unpin(context: Context, packageName: String) {
        setPinnedHomeApps(context, pinnedHomeApps(context).filterNot { it == packageName })
    }

    // --- Layout da Home (posição + pastas) — camada por CIMA de pinnedHomeApps acima.
    // pinnedHomeApps continua sendo a fonte da verdade de QUAIS pacotes estão na Home (a
    // Gaveta só mexe nela); esta camada só descreve COMO eles estão arrumados (ordem +
    // agrupados em pasta ou não). homeLayout() sempre reconcilia contra pinnedHomeApps
    // toda vez que é lida — um pacote fixado pela Gaveta que ainda não apareceu em
    // nenhuma pasta/posição salva entra solto no fim; um pacote desafixado desaparece
    // (inclusive de dentro de pastas, que somem sozinhas se ficarem vazias). Assim a
    // Gaveta nunca precisa saber que pastas existem. ---

    sealed class HomeItem {
        data class AppItem(val packageName: String) : HomeItem()
        data class FolderItem(val id: String, val name: String, val packages: List<String>) : HomeItem()
    }

    private const val KEY_HOME_LAYOUT = "homeLayoutJson"

    fun homeLayout(context: Context): List<HomeItem> {
        val pinnedSet = pinnedHomeApps(context).toSet()
        val raw = of(context).getString(KEY_HOME_LAYOUT, null)
        val saved: List<HomeItem> = if (raw != null) {
            try { parseHomeLayout(raw) } catch (e: Exception) { emptyList() }
        } else emptyList()

        val reconciled = mutableListOf<HomeItem>()
        val seenPackages = mutableSetOf<String>()
        for (item in saved) {
            when (item) {
                is HomeItem.AppItem -> if (item.packageName in pinnedSet) {
                    reconciled.add(item)
                    seenPackages.add(item.packageName)
                }
                is HomeItem.FolderItem -> {
                    val kept = item.packages.filter { it in pinnedSet }
                    if (kept.isNotEmpty()) {
                        reconciled.add(HomeItem.FolderItem(item.id, item.name, kept))
                        seenPackages.addAll(kept)
                    }
                }
            }
        }
        // Pacotes fixados que ainda não apareceram em nenhum item salvo (recém-fixados
        // pela Gaveta, ou a 1ª leitura de todas antes de qualquer pasta existir) entram
        // soltos, na ordem de pinnedHomeApps.
        pinnedHomeApps(context).filter { it !in seenPackages }.forEach { reconciled.add(HomeItem.AppItem(it)) }
        return reconciled
    }

    fun setHomeLayout(context: Context, items: List<HomeItem>) {
        val json = org.json.JSONArray()
        items.forEach { item ->
            when (item) {
                is HomeItem.AppItem -> json.put(org.json.JSONObject().put("type", "app").put("package", item.packageName))
                is HomeItem.FolderItem -> json.put(
                    org.json.JSONObject()
                        .put("type", "folder")
                        .put("id", item.id)
                        .put("name", item.name)
                        .put("packages", org.json.JSONArray(item.packages))
                )
            }
        }
        of(context).edit().putString(KEY_HOME_LAYOUT, json.toString()).apply()
    }

    private fun parseHomeLayout(raw: String): List<HomeItem> {
        val arr = org.json.JSONArray(raw)
        val list = mutableListOf<HomeItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("type") == "folder") {
                val pkgsArr = obj.optJSONArray("packages") ?: org.json.JSONArray()
                val pkgs = (0 until pkgsArr.length()).map { pkgsArr.getString(it) }
                list.add(HomeItem.FolderItem(obj.optString("id"), obj.optString("name", "Pasta"), pkgs))
            } else {
                val pkg = obj.optString("package")
                if (pkg.isNotEmpty()) list.add(HomeItem.AppItem(pkg))
            }
        }
        return list
    }

    // --- Posição de cada coisa na grade da Home (células), por chave: "app:<pacote>",
    // "folder:<id>", "widget:clock|status|tasks". Guardado à parte do homeLayout (que diz
    // O QUE está na Home); aqui só ONDE. Item sem posição salva ganha a 1ª célula livre. ---
    private const val KEY_HOME_POSITIONS = "homePositionsJson"

    fun homePositions(context: Context): Map<String, IntArray> {
        val raw = of(context).getString(KEY_HOME_POSITIONS, null) ?: return emptyMap()
        return try {
            val obj = org.json.JSONObject(raw)
            obj.keys().asSequence().mapNotNull { key ->
                val arr = obj.optJSONArray(key) ?: return@mapNotNull null
                if (arr.length() != 4) return@mapNotNull null
                key to IntArray(4) { arr.getInt(it) }
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setHomePositions(context: Context, positions: Map<String, IntArray>) {
        val obj = org.json.JSONObject()
        positions.forEach { (key, v) -> obj.put(key, org.json.JSONArray(v.toList())) }
        of(context).edit().putString(KEY_HOME_POSITIONS, obj.toString()).apply()
    }

    // --- Colunas da grade da tela inicial (ajustável) ---

    fun homeGridColumns(context: Context): Int =
        of(context).getInt("homeGridColumns", DEFAULT_GRID_COLUMNS)

    fun setHomeGridColumns(context: Context, columns: Int) {
        of(context).edit().putInt("homeGridColumns", columns).apply()
    }

    // --- Cor personalizada do tema do launcher (degradê de destaque) ---

    fun themeColors(context: Context): Pair<Int, Int> {
        val prefs = of(context)
        val startHex = prefs.getString("themeColorStart", DEFAULT_THEME.first) ?: DEFAULT_THEME.first
        val endHex = prefs.getString("themeColorEnd", DEFAULT_THEME.second) ?: DEFAULT_THEME.second
        return try {
            Color.parseColor(startHex) to Color.parseColor(endHex)
        } catch (e: IllegalArgumentException) {
            Color.parseColor(DEFAULT_THEME.first) to Color.parseColor(DEFAULT_THEME.second)
        }
    }

    fun setThemeColors(context: Context, startHex: String, endHex: String) {
        of(context).edit()
            .putString("themeColorStart", startHex)
            .putString("themeColorEnd", endHex)
            .apply()
    }

    // --- Cor da tela de bloqueio: diferente da cor fixa do tema (themeColors, que o pai
    // escolhe pra Home/ícone e continua igual sempre), essa aqui ROTACIONA sozinha —
    // pedido explícito do usuário pra tela de bloqueio não ficar sempre com a mesma cara.
    // Sorteada de novo a cada vez que a tela liga (ver rotateLockAccentColors, chamado
    // pelo ParentalAccessibilityService ao registrar ACTION_SCREEN_ON); só afeta o
    // LockOverlayService, nunca a Home/Gaveta. ---
    private const val KEY_LOCK_ACCENT_INDEX = "lockAccentThemeIndex"

    /** Cor atual da tela de bloqueio. Sem nenhuma sorteada ainda (app recém-instalado, ou
     * ainda não passou por um ciclo de tela desligar/ligar), cai na mesma cor que o pai
     * escolheu pra Home — assim o primeiro bloqueio depois de instalar não parece aleatório. */
    fun currentLockAccentColors(context: Context): Pair<Int, Int> {
        val prefs = of(context)
        val savedIndex = prefs.getInt(KEY_LOCK_ACCENT_INDEX, -1)
        if (savedIndex in THEME_PRESETS.indices) {
            val (startHex, endHex) = THEME_PRESETS[savedIndex]
            return try {
                Color.parseColor(startHex) to Color.parseColor(endHex)
            } catch (e: IllegalArgumentException) {
                themeColors(context)
            }
        }
        return themeColors(context)
    }

    /** Sorteia uma cor NOVA (diferente da atual, se houver mais de uma opção) pra tela de
     * bloqueio — chamado a cada ACTION_SCREEN_ON. */
    fun rotateLockAccentColors(context: Context) {
        if (THEME_PRESETS.size <= 1) return
        val prefs = of(context)
        val currentIndex = prefs.getInt(KEY_LOCK_ACCENT_INDEX, -1)
        var newIndex: Int
        do {
            newIndex = THEME_PRESETS.indices.random()
        } while (newIndex == currentIndex)
        prefs.edit().putInt(KEY_LOCK_ACCENT_INDEX, newIndex).apply()
    }

    // --- Vídeo de fundo animado da Home (opcional; escolhido pelo usuário) ---

    fun videoWallpaperUri(context: Context): String? =
        of(context).getString(KEY_VIDEO_WALLPAPER_URI, null)

    fun setVideoWallpaperUri(context: Context, uri: String?) {
        val editor = of(context).edit().putString(KEY_VIDEO_WALLPAPER_URI, uri)
        if (uri == null) {
            // Sem vídeo, não faz sentido guardar um enquadramento — volta pro padrão
            // pra não "herdar" um recorte estranho caso outro vídeo seja escolhido depois.
            editor.remove(KEY_VIDEO_CROP_SCALE).remove(KEY_VIDEO_CROP_PAN_X).remove(KEY_VIDEO_CROP_PAN_Y)
        }
        editor.apply()
    }

    // --- Enquadramento (zoom/posição) do vídeo de fundo, escolhido na simulação de recorte ---
    // scale: 1.0 = cobre a tela sem zoom extra (padrão); até MAX_VIDEO_CROP_SCALE de zoom.
    // panX/panY: 0..1, fração de quanto o enquadramento está deslocado dentro da folga que
    // sobra depois do zoom (0.5 = centralizado, que é o comportamento de antes).
    const val MAX_VIDEO_CROP_SCALE = 4f

    fun videoCropScale(context: Context): Float =
        of(context).getFloat(KEY_VIDEO_CROP_SCALE, 1f)

    fun videoCropPanX(context: Context): Float =
        of(context).getFloat(KEY_VIDEO_CROP_PAN_X, 0.5f)

    fun videoCropPanY(context: Context): Float =
        of(context).getFloat(KEY_VIDEO_CROP_PAN_Y, 0.5f)

    fun setVideoCrop(context: Context, scale: Float, panX: Float, panY: Float) {
        of(context).edit()
            .putFloat(KEY_VIDEO_CROP_SCALE, scale.coerceIn(1f, MAX_VIDEO_CROP_SCALE))
            .putFloat(KEY_VIDEO_CROP_PAN_X, panX.coerceIn(0f, 1f))
            .putFloat(KEY_VIDEO_CROP_PAN_Y, panY.coerceIn(0f, 1f))
            .apply()
    }

    private const val KEY_VIDEO_CROP_SCALE = "homeVideoCropScale"
    private const val KEY_VIDEO_CROP_PAN_X = "homeVideoCropPanX"
    private const val KEY_VIDEO_CROP_PAN_Y = "homeVideoCropPanY"

    // --- Estado dos gatinhos do minijogo "Alimente o Gatinho" (ver PixelCatGameView e
    // LockOverlayService.buildCatGameCard) — os 5 já aparecem liberados desde o início (um
    // por cor da paleta), cada um com seu próprio progresso guardado aqui, pra dar pra
    // trocar de gatinho e cuidar de cada um separadamente. Igual um bichinho virtual de
    // verdade, os 4 stats decaem sozinhos com o tempo REAL que passou desde a última vez
    // que aquele gatinho específico esteve selecionado — mesmo com a tela de bloqueio
    // fechada — não só enquanto a criança está com o jogo aberto (ver loadCatState). Sem
    // "game over": os stats só zeram e ficam ali, sem punição nenhuma além do visual (mesma
    // filosofia do resto do minijogo, ver o comentário no topo de PixelCatGameView). ---

    // Precisa ficar igual a PixelCatGameView.FUR_PALETTE.length/CAT_COUNT — cada índice
    // 0..CAT_COUNT-1 aqui é o mesmo índice de cor lá (ver PixelCatGameView.furColorFor).
    const val CAT_COUNT = 5
    private const val KEY_SELECTED_CAT_INDEX = "selectedCatIndex"
    private fun catKey(index: Int, field: String) = "cat_${index}_$field"

    // Decaimento por HORA de mundo real (bem mais lento que os decaimentos por SEGUNDO de
    // PixelCatGameView, que são calibrados pra enquanto a criança está olhando o jogo
    // rodando quadro a quadro) — dá pra um gatinho abandonado ficar necessitado depois de
    // algumas horas sem visita, sem precisar de nenhum serviço rodando em segundo plano só
    // pra isso: o decaimento é calculado de uma vez, na hora de carregar (ver loadCatState).
    private const val ENERGY_IDLE_DECAY_PER_HOUR = 4f
    private const val HYGIENE_IDLE_DECAY_PER_HOUR = 5f
    private const val JOY_IDLE_DECAY_PER_HOUR = 6f
    private const val AFFECTION_IDLE_DECAY_PER_HOUR = 5f

    data class CatState(
        val energy: Float,
        val hygiene: Float,
        val joy: Float,
        val affection: Float,
        val fullness: Int
    )

    fun selectedCatIndex(context: Context): Int =
        of(context).getInt(KEY_SELECTED_CAT_INDEX, 0).coerceIn(0, CAT_COUNT - 1)

    fun setSelectedCatIndex(context: Context, index: Int) {
        of(context).edit().putInt(KEY_SELECTED_CAT_INDEX, index.coerceIn(0, CAT_COUNT - 1)).apply()
    }

    /** Lê o estado de um gatinho já aplicando o decaimento do tempo real passado desde a
     * última vez que foi salvo (ver saveCatState) — nunca escreve nada aqui, só calcula em
     * cima do que está salvo, então dá pra chamar livremente (ex: pra decidir se mostra a
     * bolinha de "precisa de atenção" no seletor de todos os gatinhos, sem afetar o real). */
    fun loadCatState(context: Context, index: Int): CatState {
        val prefs = of(context)
        val now = System.currentTimeMillis()
        val lastUpdatedAt = prefs.getLong(catKey(index, "lastUpdatedAt"), now)
        val elapsedHours = ((now - lastUpdatedAt).coerceAtLeast(0L)) / 3_600_000f

        val energy = prefs.getFloat(catKey(index, "energy"), 100f) - ENERGY_IDLE_DECAY_PER_HOUR * elapsedHours
        val hygiene = prefs.getFloat(catKey(index, "hygiene"), 100f) - HYGIENE_IDLE_DECAY_PER_HOUR * elapsedHours
        val joy = prefs.getFloat(catKey(index, "joy"), 100f) - JOY_IDLE_DECAY_PER_HOUR * elapsedHours
        val affection = prefs.getFloat(catKey(index, "affection"), 100f) - AFFECTION_IDLE_DECAY_PER_HOUR * elapsedHours
        val fullness = prefs.getInt(catKey(index, "fullness"), 0)

        return CatState(
            energy = energy.coerceIn(0f, 100f),
            hygiene = hygiene.coerceIn(0f, 100f),
            joy = joy.coerceIn(0f, 100f),
            affection = affection.coerceIn(0f, 100f),
            fullness = fullness
        )
    }

    /** Chamado ao trocar de gatinho no seletor e ao fechar a sobreposição (ver
     * LockOverlayService.saveActiveCatState) — grava o progresso de verdade e marca "agora"
     * como referência pro próximo decaimento (ver loadCatState). */
    fun saveCatState(context: Context, index: Int, state: CatState) {
        of(context).edit()
            .putFloat(catKey(index, "energy"), state.energy)
            .putFloat(catKey(index, "hygiene"), state.hygiene)
            .putFloat(catKey(index, "joy"), state.joy)
            .putFloat(catKey(index, "affection"), state.affection)
            .putInt(catKey(index, "fullness"), state.fullness)
            .putLong(catKey(index, "lastUpdatedAt"), System.currentTimeMillis())
            .apply()
    }
}
