package com.guardianshield.child.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * Ponto único de leitura do SharedPreferences "GuardianShieldPrefs" — o mesmo arquivo que
 * o app Capacitor (MainActivity/PauseModule) grava a partir do estado sincronizado por
 * Socket.IO. A Home/Gaveta nativas (LauncherHomeActivity/LauncherDrawerActivity) e o
 * ParentalAccessibilityService leem daqui, então mudanças de chave só precisam ser feitas
 * neste arquivo.
 */
object GuardianPrefs {
    private const val PREFS_NAME = "GuardianShieldPrefs"
    private const val KEY_PINNED_ORDERED = "pinnedHomeAppsOrdered"
    private const val KEY_PINNED_LEGACY = "pinnedHomeApps" // Set<String> sem ordem (versão anterior)
    private const val ORDER_DELIMITER = "|||"
    private const val KEY_VIDEO_WALLPAPER_URI = "homeVideoWallpaperUri"

    // Paleta de temas disponível para o usuário escolher em "Personalizar tela inicial".
    // Cada par é [corInicial, corFinal] de um degradê.
    val THEME_PRESETS: List<Pair<String, String>> = listOf(
        "#3A86FF" to "#8338EC", // azul/roxo (padrão)
        "#06D6A0" to "#118AB2", // verde/azul
        "#FF006E" to "#FB5607", // rosa/laranja
        "#FFBE0B" to "#FB5607", // amarelo/laranja
        "#8338EC" to "#FF006E", // roxo/rosa
        "#118AB2" to "#06D6A0", // azul/verde
        "#EF476F" to "#8338EC", // vermelho/roxo
        "#495057" to "#212529"  // cinza monocromático
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

    fun isDailyLimitExceeded(context: Context): Boolean =
        usedMinutesToday(context) >= dailyLimitMinutes(context)

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
    // o backend é a fonte da verdade (o pai edita tudo isso pelo app dele). O nativo NÃO tem
    // cliente socket.io (só a WebView tem, e ela raramente está aberta — a criança usa a
    // Home/Gaveta nativas no dia a dia) — por isso o ParentalAccessibilityService consulta
    // GET /api/tasks/sync por HTTP simples a cada ciclo do seu ticker (~60s) e grava o
    // resultado aqui, pra tudo isso valer mesmo se a criança nunca abrir a WebView. A Home
    // nativa e o próprio serviço leem só daqui, nunca da rede diretamente, então continuam
    // funcionando com o último valor conhecido mesmo se o próximo poll falhar (sem internet,
    // servidor fora do ar, etc). A WebView também grava essas mesmas chaves quando está
    // aberta (ver MainActivity.PauseModule) — os dois caminhos escrevem os mesmos valores
    // que o backend manda, então não há conflito entre eles. ---

    private const val KEY_TASK_UNLOCK_MODE = "taskUnlockMode"
    private const val KEY_TASKS_SYNC_JSON = "tasksSyncJson"

    data class TaskItem(
        val id: String,
        val title: String,
        val icon: String,
        val rewardMinutes: Int,
        val status: String, // "pending" | "submitted" | "approved" | "rejected"
        val rejectedReason: String?
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
        rawTasksJson: String
    ) {
        of(context).edit()
            .putString(KEY_TASK_UNLOCK_MODE, unlockMode)
            .putString(KEY_TASKS_SYNC_JSON, rawTasksJson)
            .putInt("dailyLimitMinutes", dailyLimitMinutes)
            .putBoolean("isPauseAllActive", isPauseAllActive)
            .putStringSet("blockedPackagesSet", blockedPackages)
            .apply()
    }

    fun parsedTodayTasks(context: Context): List<TaskItem> {
        val raw = of(context).getString(KEY_TASKS_SYNC_JSON, null) ?: return emptyList()
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
                    rejectedReason = statusObj?.let { if (it.isNull("rejectedReason")) null else it.optString("rejectedReason") }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** true se o modo "tudo ou nada" estiver ativo e ainda faltar pelo menos 1 tarefa aprovada hoje. */
    fun isTaskGateBlocking(context: Context): Boolean {
        if (taskUnlockMode(context) != "all_or_nothing") return false
        val tasks = parsedTodayTasks(context)
        if (tasks.isEmpty()) return false // sem tarefa cadastrada, não há o que travar
        return tasks.any { it.status != "approved" }
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
}
