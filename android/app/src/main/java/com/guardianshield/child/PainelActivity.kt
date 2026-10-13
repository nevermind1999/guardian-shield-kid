package com.guardianshield.child

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.guardianshield.child.util.AppRepository
import com.guardianshield.child.util.AppUpdater
import com.guardianshield.child.util.GuardianPrefs
import com.guardianshield.child.util.NetworkHelper
import com.guardianshield.child.util.PermissionWatchdog
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Painel do GuardianShield no celular do filho — 100% nativo (substitui a antiga tela em
 * React dentro de um WebView/Capacitor, que demorava pra abrir e duplicava a lógica que o
 * lado nativo já tinha). Mostra, conforme o estado:
 * - pareamento (só antes do 1º pareamento): código GS-XXXX digitado → POST /api/pair/verify;
 * - tempo restante hoje + motivo de bloqueio ativo + "Pedir mais tempo";
 * - checklist do que falta configurar pra proteção funcionar de verdade (Acessibilidade,
 *   sobreposição, tela inicial, localização, notificações, bateria), cada item com um
 *   toque que leva direto pra tela certa do sistema;
 * - aviso de versão nova (AppUpdater).
 * Tudo lido de GuardianPrefs (o ParentalAccessibilityService mantém atualizado) — a tela
 * reage na hora a mudanças via OnSharedPreferenceChangeListener.
 */
class PainelActivity : AppCompatActivity() {

    companion object {
        /** Extra usado pela Home/tela de bloqueio pra abrir já no pedido de tempo extra. */
        const val EXTRA_ABRIR_PEDIDO_TEMPO = "open_request_modal"
    }

    private lateinit var subtitulo: TextView
    private lateinit var statusSync: TextView
    private lateinit var cardPareamento: View
    private lateinit var campoCodigo: EditText
    private lateinit var erroPareamento: TextView
    private lateinit var botaoParear: Button
    private lateinit var cardTempo: View
    private lateinit var tempoRotulo: TextView
    private lateinit var tempoValor: TextView
    private lateinit var tempoBarra: ProgressBar
    private lateinit var tempoDetalhe: TextView
    private lateinit var botaoPedirTempo: Button
    private lateinit var cardChecklist: View
    private lateinit var checklistResumo: TextView
    private lateinit var checklistBarra: ProgressBar
    private lateinit var checklistItens: LinearLayout
    private lateinit var bannerAtualizacao: View
    private lateinit var notasAtualizacao: TextView
    private lateinit var rodape: TextView

    private val rede = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var downloadId = -1L
    private var pareando = false

    private val permissaoLocalizacao = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { atualizarTudo() }
    private val permissaoSimples = registerForActivityResult(ActivityResultContracts.RequestPermission()) { atualizarTudo() }

    // Várias chaves mudam juntas a cada sincronização — agrupa num redesenho só.
    private val redesenho = Runnable { atualizarTudo() }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.removeCallbacks(redesenho)
        ui.postDelayed(redesenho, 120)
    }

    // O "tempo restante" anda sozinho (o serviço conta minuto a minuto), sem evento nenhum
    // na tela — revalida de tempos em tempos enquanto o painel está aberto.
    private val relogio = object : Runnable {
        override fun run() {
            atualizarTempo()
            ui.postDelayed(this, 20_000)
        }
    }

    private val downloadConcluido = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id != -1L && id == downloadId) instalarAtualizacao()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(this, R.color.painelFundo)
        setContentView(R.layout.activity_painel)

        val conteudo = findViewById<View>(R.id.painelConteudo)
        ViewCompat.setOnApplyWindowInsetsListener(conteudo) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, barras.top, v.paddingRight, barras.bottom + dp(28))
            insets
        }

        subtitulo = findViewById(R.id.painelSubtitulo)
        statusSync = findViewById(R.id.painelStatusSync)
        cardPareamento = findViewById(R.id.painelPareamento)
        campoCodigo = findViewById(R.id.painelCodigo)
        erroPareamento = findViewById(R.id.painelPareamentoErro)
        botaoParear = findViewById(R.id.painelParearBotao)
        cardTempo = findViewById(R.id.painelTempo)
        tempoRotulo = findViewById(R.id.painelTempoRotulo)
        tempoValor = findViewById(R.id.painelTempoValor)
        tempoBarra = findViewById(R.id.painelTempoBarra)
        tempoDetalhe = findViewById(R.id.painelTempoDetalhe)
        botaoPedirTempo = findViewById(R.id.painelPedirTempoBotao)
        cardChecklist = findViewById(R.id.painelChecklist)
        checklistResumo = findViewById(R.id.painelChecklistResumo)
        checklistBarra = findViewById(R.id.painelChecklistBarra)
        checklistItens = findViewById(R.id.painelChecklistItens)
        bannerAtualizacao = findViewById(R.id.painelAtualizacao)
        notasAtualizacao = findViewById(R.id.painelAtualizacaoNotas)
        rodape = findViewById(R.id.painelRodape)

        campoCodigo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                erroPareamento.visibility = View.GONE
                botaoParear.isEnabled = !pareando && (s?.length ?: 0) == 4
                botaoParear.alpha = if (botaoParear.isEnabled) 1f else 0.45f
                // Os 4 dígitos já bastam — conecta sozinho, sem precisar achar o botão.
                if ((s?.length ?: 0) == 4 && !pareando) parear()
            }
        })
        campoCodigo.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && campoCodigo.text.length == 4) { parear(); true } else false
        }
        botaoParear.alpha = 0.45f
        botaoParear.setOnClickListener { parear() }
        botaoPedirTempo.setOnClickListener { abrirPedidoTempo() }

        rodape.text = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · versão ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"

        verificarAtualizacao()
        if (intent.getBooleanExtra(EXTRA_ABRIR_PEDIDO_TEMPO, false)) {
            intent.removeExtra(EXTRA_ABRIR_PEDIDO_TEMPO)
            ui.post { abrirPedidoTempo() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ABRIR_PEDIDO_TEMPO, false)) abrirPedidoTempo()
    }

    override fun onResume() {
        super.onResume()
        GuardianPrefs.of(this).registerOnSharedPreferenceChangeListener(prefsListener)
        // Volta das telas de sistema (Acessibilidade, sobreposição…) cai aqui — o checklist
        // precisa refletir na hora o que acabou de ser ligado.
        atualizarTudo()
        ui.post(relogio)
    }

    override fun onPause() {
        super.onPause()
        GuardianPrefs.of(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        ui.removeCallbacks(relogio)
        ui.removeCallbacks(redesenho)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (downloadId != -1L) try { unregisterReceiver(downloadConcluido) } catch (e: Exception) { }
        rede.shutdown()
    }

    // ============================== estado geral ==============================

    private fun atualizarTudo() {
        val pareado = GuardianPrefs.isPaired(this)
        cardPareamento.visibility = if (pareado) View.GONE else View.VISIBLE
        cardTempo.visibility = if (pareado) View.VISIBLE else View.GONE
        subtitulo.text = if (pareado) "Conectado ao celular dos pais" else "Ainda não conectado"
        val servidorOk = GuardianPrefs.lastWorkingServerUrl(this) != null
        statusSync.text = when {
            !pareado -> "⚪ Sem conexão"
            servidorOk -> "🟢 Sincronizado"
            else -> "🟡 Aguardando"
        }
        atualizarTempo()
        atualizarChecklist()
    }

    private fun atualizarTempo() {
        if (!GuardianPrefs.isPaired(this)) return
        val limite = GuardianPrefs.dailyLimitMinutes(this)
        val usado = GuardianPrefs.usedMinutesToday(this)
        val bloqueio = GuardianPrefs.currentGlobalBlockLabel(this)
        val liberacao = GuardianPrefs.parentTimeGrantRemainingMinutes(this)
        val restante = (limite - usado).coerceAtLeast(0)

        if (liberacao > 0 && GuardianPrefs.isParentTimeGrantActive(this)) {
            tempoRotulo.text = "LIBERADO PELOS SEUS PAIS"
            tempoValor.text = formatarMinutos(liberacao)
        } else {
            tempoRotulo.text = "TEMPO RESTANTE HOJE"
            tempoValor.text = formatarMinutos(restante)
        }

        val proporcaoUsada = if (limite > 0) (usado.toFloat() / limite).coerceIn(0f, 1f) else 1f
        tempoBarra.progress = ((1f - proporcaoUsada) * tempoBarra.max).toInt()
        val cor = when {
            bloqueio != null || proporcaoUsada >= 0.9f -> ContextCompat.getColor(this, R.color.painelAlerta)
            proporcaoUsada >= 0.7f -> ContextCompat.getColor(this, R.color.painelAtencao)
            else -> ContextCompat.getColor(this, R.color.painelOk)
        }
        tempoBarra.progressTintList = ColorStateList.valueOf(cor)
        tempoValor.setTextColor(if (bloqueio != null) cor else ContextCompat.getColor(this, R.color.painelTexto))

        tempoDetalhe.text = if (bloqueio != null) {
            "🔒 $bloqueio"
        } else {
            "Usou ${formatarMinutos(usado)} de ${formatarMinutos(limite)} hoje"
        }
    }

    private fun formatarMinutos(minutos: Int): String {
        val h = minutos / 60
        val m = minutos % 60
        return when {
            h == 0 -> "${m}min"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}min"
        }
    }

    // ============================== checklist ==============================

    private data class ItemChecklist(
        val titulo: String,
        val descricao: String,
        val ok: Boolean,
        val essencial: Boolean,
        val acao: () -> Unit
    )

    private fun itensChecklist(): List<ItemChecklist> {
        val itens = mutableListOf<ItemChecklist>()
        itens += ItemChecklist(
            "Serviço de Acessibilidade",
            "Sem ele nenhum app é bloqueado. Se a opção estiver cinza, abra Info do app › ⋮ › Permitir configurações restritas.",
            PermissionWatchdog.isAccessibilityServiceEnabled(this), true
        ) { abrir(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        itens += ItemChecklist(
            "Aparecer sobre outros apps",
            "Necessário pra tela de bloqueio aparecer.",
            Settings.canDrawOverlays(this), true
        ) { abrir(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        itens += ItemChecklist(
            "Tela inicial padrão",
            "Mostra os apps bloqueados apagados e as tarefas do dia.",
            ehTelaInicialPadrao(), false
        ) { abrirConfiguracaoTelaInicial() }
        itens += ItemChecklist(
            "Localização",
            "Pros seus pais saberem onde o celular está.",
            temLocalizacao(), false
        ) { pedirLocalizacao() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            itens += ItemChecklist(
                "Notificações",
                "Avisos de tempo acabando e de respostas dos seus pais.",
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, false
            ) { permissaoSimples.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        itens += ItemChecklist(
            "Sem economia de bateria",
            "Evita o Android desligar a proteção em segundo plano.",
            ignoraOtimizacaoBateria(), false
        ) { pedirSemOtimizacaoBateria() }
        return itens
    }

    private fun atualizarChecklist() {
        val itens = itensChecklist()
        val prontos = itens.count { it.ok }
        checklistResumo.text = "$prontos de ${itens.size}"
        checklistBarra.max = itens.size
        checklistBarra.progress = prontos
        val faltaEssencial = itens.any { it.essencial && !it.ok }
        checklistBarra.progressTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (faltaEssencial) R.color.painelAlerta else if (prontos < itens.size) R.color.painelAtencao else R.color.painelOk)
        )
        checklistItens.removeAllViews()
        // Pendentes primeiro (essenciais no topo); os já resolvidos descem pro fim.
        itens.sortedWith(compareBy({ it.ok }, { !it.essencial })).forEach { checklistItens.addView(linhaChecklist(it)) }
    }

    private fun linhaChecklist(item: ItemChecklist): View {
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            if (!item.ok) {
                isClickable = true
                isFocusable = true
                foreground = obterFundoClicavel()
                setOnClickListener { item.acao() }
            }
        }
        val corStatus = ContextCompat.getColor(
            this,
            when {
                item.ok -> R.color.painelOk
                item.essencial -> R.color.painelAlerta
                else -> R.color.painelAtencao
            }
        )
        linha.addView(TextView(this).apply {
            text = if (item.ok) "✓" else "!"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(corStatus) }
        }, LinearLayout.LayoutParams(dp(24), dp(24)))

        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        textos.addView(TextView(this).apply {
            text = item.titulo
            setTextColor(ContextCompat.getColor(this@PainelActivity, R.color.painelTexto))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (!item.ok) {
            textos.addView(TextView(this).apply {
                text = item.descricao
                setTextColor(ContextCompat.getColor(this@PainelActivity, R.color.painelTextoSuave))
                textSize = 13f
            })
        }
        linha.addView(textos, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!item.ok) {
            linha.addView(TextView(this).apply {
                text = "Ativar"
                setTextColor(corStatus)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        return linha
    }

    private fun obterFundoClicavel() =
        obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { a -> a.getDrawable(0).also { a.recycle() } }

    private fun abrir(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Não deu pra abrir essa configuração neste aparelho.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ehTelaInicialPadrao(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    private fun abrirConfiguracaoTelaInicial() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            // Alguns fabricantes não têm a tela de "app de início padrão" — o seletor do
            // próprio Android resolve igual.
            abrir(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun temLocalizacao(): Boolean {
        val fina = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fundo = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fina && fundo
    }

    private fun pedirLocalizacao() {
        val fina = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fina) {
            permissaoLocalizacao.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // "Permitir o tempo todo" só pode ser escolhido nas configurações do app (Android 11+).
            AlertDialog.Builder(this)
                .setTitle("Localização o tempo todo")
                .setMessage("Na próxima tela, toque em Permissões › Localização › “Permitir o tempo todo”.")
                .setPositiveButton("Abrir") { _, _ ->
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        permissaoSimples.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        abrir(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                }
                .setNegativeButton("Agora não", null)
                .show()
        }
    }

    private fun ignoraOtimizacaoBateria(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun pedirSemOtimizacaoBateria() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            abrir(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ============================== pareamento ==============================

    @SuppressLint("HardwareIds")
    private fun parear() {
        val digitos = campoCodigo.text.toString().filter { it.isDigit() }
        if (digitos.length != 4 || pareando) return
        pareando = true
        botaoParear.isEnabled = false
        botaoParear.text = "Conectando…"
        erroPareamento.visibility = View.GONE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(campoCodigo.windowToken, 0)

        // Mesmo identificador que a versão React usava (Capacitor Device.getId = ANDROID_ID):
        // reparear este aparelho atualiza o mesmo registro no backend em vez de criar outro.
        val idAparelho = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "child-${System.currentTimeMillis()}"
        val nome = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
        val bateria = (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val corpo = JSONObject()
            .put("code", "GS-$digitos")
            .put("deviceInfo", JSONObject().put("id", idAparelho).put("name", nome).put("model", nome).put("batteryLevel", bateria))
            .toString()

        rede.execute {
            val resposta = NetworkHelper.postJsonResponse(this, "/api/pair/verify", corpo)
            val json = resposta?.second?.let { runCatching { JSONObject(it) }.getOrNull() }
            val token = json?.optString("deviceToken").orEmpty()
            if (resposta != null && resposta.first == 200 && token.isNotEmpty()) {
                GuardianPrefs.setDeviceToken(this, token)
                enviarAppsInstalados()
                ui.post {
                    pareando = false
                    Toast.makeText(this, "✅ Conectado ao celular dos pais!", Toast.LENGTH_LONG).show()
                    atualizarTudo()
                }
            } else {
                val mensagem = when {
                    resposta == null -> "Sem internet ou servidor fora do ar. Confira a conexão e tente de novo."
                    else -> json?.optString("message")?.takeIf { it.isNotBlank() } ?: "Código inválido ou expirado."
                }
                ui.post {
                    pareando = false
                    botaoParear.text = "Conectar"
                    botaoParear.isEnabled = campoCodigo.text.length == 4
                    botaoParear.alpha = if (botaoParear.isEnabled) 1f else 0.45f
                    erroPareamento.text = mensagem
                    erroPareamento.visibility = View.VISIBLE
                    campoCodigo.selectAll()
                }
            }
        }
    }

    /** Logo depois de parear, o pai já vê a lista de apps — sem esperar o ciclo de 5min do serviço. */
    private fun enviarAppsInstalados() {
        val apps = JSONArray()
        AppRepository.loadLaunchableApps(this).forEach {
            apps.put(JSONObject().put("package", it.packageName).put("name", it.label).put("category", it.category))
        }
        NetworkHelper.postJson(this, "/api/device/apps-sync", JSONObject().put("installedApps", apps).toString())
    }

    // ============================== pedido de tempo extra ==============================

    private fun abrirPedidoTempo() {
        if (!GuardianPrefs.isPaired(this)) {
            Toast.makeText(this, "Conecte ao celular dos pais primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        val opcoes = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hora")
        var escolhido = 15
        val (corInicio, corFim) = GuardianPrefs.themeColors(this)

        val conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }
        conteudo.addView(TextView(this).apply {
            text = "Quanto tempo?"
            setTextColor(ContextCompat.getColor(this@PainelActivity, R.color.painelTextoSuave))
            textSize = 13f
        })
        val fileira = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(16))
        }
        val chips = mutableListOf<TextView>()
        fun pintar() {
            chips.forEachIndexed { i, chip ->
                val sel = opcoes[i].first == escolhido
                chip.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    if (sel) { colors = intArrayOf(corInicio, corFim); orientation = GradientDrawable.Orientation.TL_BR } else setColor(Color.parseColor("#292524"))
                }
                chip.setTextColor(if (sel) Color.WHITE else ContextCompat.getColor(this, R.color.painelTextoSuave))
            }
        }
        opcoes.forEachIndexed { i, (minutos, rotulo) ->
            val chip = TextView(this).apply {
                text = rotulo
                gravity = Gravity.CENTER
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener { escolhido = minutos; pintar() }
            }
            chips += chip
            fileira.addView(chip, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        pintar()
        conteudo.addView(fileira)
        val motivo = EditText(this).apply {
            hint = "Pra quê? (ex: terminar a lição)"
            setHintTextColor(Color.parseColor("#78716C"))
            setTextColor(ContextCompat.getColor(this@PainelActivity, R.color.painelTexto))
            background = ContextCompat.getDrawable(this@PainelActivity, R.drawable.bg_painel_input)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            textSize = 15f
            isSingleLine = true
        }
        conteudo.addView(motivo)

        val dialogo = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pedir mais tempo")
            .setView(conteudo)
            .setPositiveButton("Enviar pedido", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { botao ->
                botao.isEnabled = false
                val corpo = JSONObject()
                    .put("requestedMinutes", escolhido)
                    .put("reason", motivo.text.toString().trim().ifEmpty { "Preciso de mais tempo" })
                    .toString()
                rede.execute {
                    val ok = NetworkHelper.postJson(this, "/api/device/request-extra-time", corpo)
                    ui.post {
                        if (ok) {
                            dialogo.dismiss()
                            Toast.makeText(this, "📨 Pedido enviado! Seus pais vão receber agora.", Toast.LENGTH_LONG).show()
                        } else {
                            botao.isEnabled = true
                            Toast.makeText(this, "Não foi possível enviar. Confira a internet.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialogo.show()
    }

    // ============================== atualização ==============================

    private fun verificarAtualizacao() {
        rede.execute {
            val update = AppUpdater.check(this) ?: return@execute
            ui.post {
                notasAtualizacao.text = update.notes
                bannerAtualizacao.visibility = View.VISIBLE
                findViewById<View>(R.id.painelAtualizacaoFechar).setOnClickListener {
                    AppUpdater.dismiss(this, update.version)
                    bannerAtualizacao.visibility = View.GONE
                }
                findViewById<View>(R.id.painelAtualizarBotao).setOnClickListener {
                    if (downloadId == -1L) {
                        ContextCompat.registerReceiver(
                            this, downloadConcluido, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED
                        )
                    }
                    downloadId = AppUpdater.startDownload(this, update.apkUrl)
                    AppUpdater.dismiss(this, update.version)
                    bannerAtualizacao.visibility = View.GONE
                    Toast.makeText(this, "Baixando a atualização…", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun instalarAtualizacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            // Concedido uma vez só; a notificação do download continua lá pra tocar depois.
            abrir(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return
        abrir(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()
}
