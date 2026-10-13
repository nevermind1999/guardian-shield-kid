package com.guardianshield.child

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.guardianshield.child.model.AppEntry
import com.guardianshield.child.util.AppRepository
import com.guardianshield.child.services.ParentalAccessibilityService
import com.guardianshield.child.util.GuardianPrefs
import com.guardianshield.child.util.HomeSnapshot
import com.guardianshield.child.util.PermissionWatchdog
import com.guardianshield.child.util.ServerConfig
import com.guardianshield.child.util.VideoTransform
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * Tela inicial (Home) nativa do GuardianShield: relógio, papel de parede real do aparelho
 * (ou vídeo animado, se o usuário escolher um — toca só aqui, mudo, e é liberado assim que
 * a Home sai de primeiro plano), status de bateria/tempo restante e uma grade com quantos
 * apps o usuário quiser fixar (arrastável pra reordenar, colunas e cores personalizáveis).
 * Arrastar pra cima ou tocar na alça abre a gaveta com todos os apps.
 * 100% Views nativas (sem WebView) — o app Capacitor/React fica só para pareamento/config.
 */
class LauncherHomeActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var apps: List<AppEntry> = emptyList()
    private lateinit var workspace: HomeWorkspace
    // Ícones na grade, por chave ("app:<pacote>" / "folder:<id>") — reaproveitados entre
    // redesenhos em vez de inflar tudo de novo.
    private val iconViews = mutableMapOf<String, View>()
    private var iconMenu: PopupMenu? = null
    private var homeFullWarned = false

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var batteryText: TextView
    private lateinit var remainingTimeText: TextView
    private lateinit var timeProgressBar: android.widget.ProgressBar
    private lateinit var requestTimeButton: Button
    private lateinit var emergencyCallButton: ImageButton
    private lateinit var nextScheduleHint: TextView
    private lateinit var permissionWarningBanner: View
    private lateinit var permissionWarningText: TextView
    private lateinit var drawerHandle: View
    private lateinit var videoWallpaper: TextureView


    // --- Vídeo de fundo: só existe enquanto a Home está em primeiro plano ---
    private var mediaPlayer: MediaPlayer? = null

    // --- Tarefas diárias: widget na Home + captura/envio de foto de comprovação ---
    private lateinit var tasksSectionContainer: View
    private lateinit var tasksSectionTitle: TextView
    private lateinit var tasksRecyclerView: RecyclerView
    private lateinit var taskAdapter: TaskCardAdapter
    private var pendingPhotoTaskId: String? = null
    private var pendingPhotoUri: Uri? = null
    // Tarefas enviadas nesta sessão mas que o próximo poll do ParentalAccessibilityService
    // ainda não confirmou — mostradas como "aguardando" otimisticamente até o cache real
    // (GuardianPrefs) refletir o novo status; evita a criança achar que o toque não fez nada.
    private val locallySubmittedTaskIds = mutableSetOf<String>()
    private val taskSubmitExecutor = Executors.newSingleThreadExecutor()

    // Best-effort: se a criança/pai negar, o PermissionWatchdog simplesmente não consegue
    // mostrar a notificação (sem crash, ver runCheckAndNotify) e o banner pulsante da Home
    // continua sendo o único aviso — não insiste pedindo de novo depois de uma negativa.
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val takeTaskPhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        // De volta da câmera (com foto ou cancelado) — a janela de exceção do bloqueio
        // (GuardianPrefs.setTaskSubmissionAllowedUntil, aberta pela tela de bloqueio em
        // LockOverlayService.startTaskCameraFlow) só existia pra deixar o app de câmera
        // abrir sem ser barrado de volta. Encerra na hora, em vez de esperar o tempo
        // máximo (3min) passar sozinho — enviar a foto NÃO aprova a tarefa, só manda
        // pro pai revisar, então o bloqueio deve voltar a valer imediatamente.
        GuardianPrefs.setTaskSubmissionAllowedUntil(this, 0L)
        val taskId = pendingPhotoTaskId
        val uri = pendingPhotoUri
        pendingPhotoTaskId = null
        pendingPhotoUri = null
        if (success && taskId != null && uri != null) {
            submitTaskPhoto(taskId, uri)
        }
    }

    // Depois de escolher o arquivo, abre a simulação de recorte (zoom/posição) antes de
    // aplicar de verdade — só grava em GuardianPrefs quando o usuário toca em "Salvar" lá.
    private val cropVideoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            setupVideoWallpaper()
        }
    }
    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Alguns provedores não suportam permissão persistente; segue mesmo assim,
                // pode parar de funcionar após reiniciar o app nesse caso raro.
            }
            cropVideoLauncher.launch(
                Intent(this, VideoCropActivity::class.java).putExtra(VideoCropActivity.EXTRA_VIDEO_URI, uri.toString())
            )
        }
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            // Sem isso, "tempo restante" só era recalculado no onCreate/onResume e no
            // batteryReceiver (bateria mudar de %) — enquanto a criança ficava parada na
            // Home, o contador congelava e só "andava" de vez em quando por acidente, em
            // vez de acompanhar o ticker de uso de 1min do ParentalAccessibilityService.
            updateBatteryAndTime()
            clockHandler.postDelayed(this, 30_000)
        }
    }

    // Chaves que mudam o tempo todo em segundo plano mas não aparecem na Home (histórico de
    // apps a enviar, minijogo da tela de bloqueio, cache de servidor…) — antes qualquer
    // gravação dessas redesenhava a Home inteira.
    private val keysIgnoredByHome = setOf(
        "appUsageHistoryQueue", "lastWorkingServerUrl", "selectedCatIndex", "lockAccentThemeIndex",
        "lowTimeWarningShownDate", "taskSubmissionAllowedUntil", "pendingPinUnlockAck", "dismissedUpdateSha",
        "pinOverrideUntilMillis", "deviceToken"
    )

    // Uma sincronização grava várias chaves de uma vez — agrupa num redesenho só.
    private val refreshHomeGridRunnable = Runnable { refreshHomeGrid() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in keysIgnoredByHome || key.startsWith("cat_")) return@OnSharedPreferenceChangeListener
        runOnUiThread {
            when (key) {
                "homeGridColumns" -> applyGridColumns()
                "themeColorStart", "themeColorEnd" -> applyThemeColors()
                "homeVideoWallpaperUri" -> setupVideoWallpaper()
                else -> {
                    clockHandler.removeCallbacks(refreshHomeGridRunnable)
                    clockHandler.postDelayed(refreshHomeGridRunnable, 120)
                }
            }
        }
    }

    // Lista de apps montada fora da thread principal (ver AppRepository.cachedIfFresh).
    private val appLoader = Executors.newSingleThreadExecutor()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryAndTime()
        }
    }

    /**
     * Dispara em qualquer desinstalação no aparelho (pelo menu de contexto acima, pelas
     * Configurações do sistema, ou por qualquer outro caminho) — recarrega a lista real
     * de apps e tira o ícone da Home na hora, em vez de deixar um ícone morto até a
     * criança reabrir o launcher e notar que ele não funciona mais.
     */
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppRepository.invalidate()
            refreshInstalledAppsAndGrid()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_launcher_home)

        prefs = GuardianPrefs.of(this)

        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)
        batteryText = findViewById(R.id.batteryText)
        remainingTimeText = findViewById(R.id.remainingTimeText)
        timeProgressBar = findViewById(R.id.timeProgressBar)
        requestTimeButton = findViewById(R.id.requestTimeButton)
        emergencyCallButton = findViewById(R.id.emergencyCallButton)
        nextScheduleHint = findViewById(R.id.nextScheduleHint)
        permissionWarningBanner = findViewById(R.id.permissionWarningBanner)
        permissionWarningText = findViewById(R.id.permissionWarningText)
        setupPermissionWarningPulse()
        drawerHandle = findViewById(R.id.drawerHandle)
        workspace = findViewById(R.id.homeWorkspace)
        videoWallpaper = findViewById(R.id.videoWallpaper)
        tasksSectionContainer = findViewById(R.id.tasksSectionContainer)
        tasksSectionTitle = findViewById(R.id.tasksSectionTitle)
        tasksRecyclerView = findViewById(R.id.tasksRecyclerView)

        val contentColumn = findViewById<LinearLayout>(R.id.contentColumn)
        // Modo tela cheia (edge-to-edge): o wallpaper ocupa a tela toda, mas o conteúdo
        // precisa respeitar a barra de status e a de navegação pra não ficar cortado.
        ViewCompat.setOnApplyWindowInsetsListener(contentColumn) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, PainelActivity::class.java))
        }
        emergencyCallButton.setOnClickListener { callEmergencyNumber() }
        findViewById<ImageButton>(R.id.paletteButton).setOnClickListener {
            showCustomizeDialog()
        }
        requestTimeButton.setOnClickListener {
            val intent = Intent(this, PainelActivity::class.java)
            intent.putExtra(PainelActivity.EXTRA_ABRIR_PEDIDO_TEMPO, true)
            startActivity(intent)
        }

        // A alça responde tanto a um toque simples quanto a arrastar pra cima. Precisa ser
        // um detector PRÓPRIO (não um OnClickListener separado): ter os dois num mesmo View
        // faz o toque "sumir" — o OnTouchListener consome o ACTION_DOWN pra rastrear o
        // gesto e o clique normal nunca chega a disparar.
        val drawerHandleDetector = GestureDetectorCompat(this, SwipeUpListener(treatTapAsSwipe = true) { openDrawer() })
        drawerHandle.setOnTouchListener { _, event -> drawerHandleDetector.onTouchEvent(event) }
        setupDrawerHandleBounce()

        // Arrastar pra cima em qualquer outro lugar da Home também abre a gaveta — ver
        // dispatchTouchEvent.
        swipeSlopPx = 40 * resources.displayMetrics.density

        setupWorkspace()
        HomeSnapshot.provider = { captureBlurredHome() }

        taskAdapter = TaskCardAdapter(onTap = { task -> launchTaskCamera(task.id) })
        tasksRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        tasksRecyclerView.adapter = taskAdapter

        applyGridColumns()
        applyThemeColors()
        refreshInstalledAppsAndGrid()
        setupVideoWallpaper()
        handleTaskCameraIntentExtra(intent)

        // Liga o watchdog externo de permissões (ver PermissionWatchdog) — redundante de
        // propósito com a chamada em ParentalAccessibilityService.onServiceConnected, pra
        // cobrir também o caso de a Home ser a primeira coisa a abrir depois de uma
        // instalação nova, antes mesmo da Acessibilidade ter sido concedida ainda.
        PermissionWatchdog.scheduleRepeating(this)
        PermissionWatchdog.runCheckAndNotify(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // A Activity é singleTask (ver AndroidManifest), então re-lançamentos (ex: pela
    // tela de bloqueio, ou por bringGuardianShieldToFront no ParentalAccessibilityService)
    // caem aqui em vez de recriar a Activity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTaskCameraIntentExtra(intent)
    }

    /**
     * Disparado pela tela de bloqueio (LockOverlayService.startTaskCameraFlow) quando
     * a criança toca em "Enviar Foto" numa tarefa pendente — abre a câmera direto pra
     * essa tarefa, sem precisar navegar até o widget de tarefas da Home (que fica
     * coberto pela sobreposição de bloqueio enquanto o bloqueio segue ativo). Reusa
     * launchTaskCamera, a mesma função que o toque num card do widget já usa.
     */
    private fun handleTaskCameraIntentExtra(intent: Intent) {
        intent.getStringExtra("openTaskCameraForId")?.let { taskId ->
            intent.removeExtra("openTaskCameraForId")
            launchTaskCamera(taskId)
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val packageFilter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply { addDataScheme("package") }
        registerReceiver(packageChangeReceiver, packageFilter)
        clockHandler.post(clockTick)
        // A lista de apps pode ter mudado enquanto a Home estava em segundo plano (app
        // instalado/desinstalado por fora) — recarrega sempre que a Home volta a aparecer,
        // além de reagir na hora via packageChangeReceiver enquanto está em primeiro plano.
        refreshInstalledAppsAndGrid()
        resumeVideoWallpaper()
        scheduleBackdropCapture()
    }

    private val backdropCaptureRunnable = Runnable {
        if (hasWindowFocus() && !isFinishing) ParentalAccessibilityService.instance?.captureHomeBackdrop()
    }

    /** Pede um print novo da Home (fundo do popup de bloqueio) depois que ela assenta na tela. */
    private fun scheduleBackdropCapture() {
        clockHandler.removeCallbacks(backdropCaptureRunnable)
        clockHandler.postDelayed(backdropCaptureRunnable, 900)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleBackdropCapture()
    }

    override fun onPause() {
        super.onPause()
        workspace.stopWidgetEdit()
        iconMenu?.dismiss()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: IllegalArgumentException) {
            // já estava desregistrado, nada a fazer
        }
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (e: IllegalArgumentException) {
            // já estava desregistrado, nada a fazer
        }
        clockHandler.removeCallbacks(clockTick)
        // O vídeo só deve tocar com a Home em primeiro plano — libera o player assim que
        // sair (abrir a gaveta, o painel de config, ou minimizar), pra não gastar CPU/bateria.
        releaseVideoPlayback()
    }

    // A Home não fecha com o botão Voltar — igual a qualquer launcher de verdade
    override fun onBackPressed() {
        // Voltar só sai do modo de edição de widget; fora isso, nada.
        workspace.stopWidgetEdit()
    }

    override fun onDestroy() {
        super.onDestroy()
        HomeSnapshot.provider = null
        taskSubmitExecutor.shutdown()
        appLoader.shutdown()
    }

    private fun openDrawer() {
        val now = SystemClock.uptimeMillis()
        if (now - lastDrawerOpenAt < 700) return // alça e gesto global no mesmo arrasto
        lastDrawerOpenAt = now
        startActivity(Intent(this, LauncherDrawerActivity::class.java))
        overridePendingTransition(R.anim.slide_up_in, R.anim.stay_still)
    }

    // ============================== GAVETA: gesto de arrastar ==============================

    private var lastDrawerOpenAt = 0L
    private var swipeSlopPx = 0f
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeTracking = false
    private var swipeConsumed = false

    /**
     * Arrastar pra cima em qualquer ponto da Home abre a gaveta, como nos launchers do
     * Android. Fica de fora enquanto a grade está ocupada (toque e segure, ícone sendo
     * arrastado, widget em edição — ver HomeWorkspace.isBusy). Quando o gesto vira
     * "abrir gaveta", os filhos recebem CANCEL — nada de toque fantasma num ícone.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.rawX
                swipeStartY = ev.rawY
                swipeConsumed = false
                swipeTracking = !workspace.isBusy
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeConsumed) return true
                if (swipeTracking && !workspace.isBusy) {
                    val dy = ev.rawY - swipeStartY
                    val dx = ev.rawX - swipeStartX
                    if (dy < -swipeSlopPx && abs(dy) > abs(dx) * 1.5f) {
                        swipeTracking = false
                        swipeConsumed = true
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        openDrawer()
                        return true
                    }
                    if (abs(dx) > swipeSlopPx * 1.5f || dy > swipeSlopPx) swipeTracking = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swipeTracking = false
                if (swipeConsumed) {
                    swipeConsumed = false
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Detecta "arrastar pra cima" tanto por velocidade (fling rápido, gesto real de dedo)
     * quanto por distância acumulada (arrasto mais devagar) — assim funciona tanto num
     * toque humano normal quanto num arrasto mais lento/deliberado. Quando [treatTapAsSwipe]
     * é true (usado na alça da gaveta), um toque simples também dispara [onSwipeUp].
     */
    private class SwipeUpListener(
        private val treatTapAsSwipe: Boolean,
        private val onSwipeUp: () -> Unit
    ) : GestureDetector.SimpleOnGestureListener() {
        private var triggered = false

        override fun onDown(e: MotionEvent): Boolean {
            triggered = false
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e1 == null || triggered) return false
            val deltaY = e2.y - e1.y
            if (deltaY < -120 && abs(deltaY) > abs(e2.x - e1.x)) {
                triggered = true
                onSwipeUp()
                return true
            }
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null || triggered) return false
            val deltaY = e2.y - e1.y
            if (deltaY < -80 && abs(velocityY) > 300 && abs(deltaY) > abs(e2.x - e1.x)) {
                triggered = true
                onSwipeUp()
                return true
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!treatTapAsSwipe || triggered) return false
            triggered = true
            onSwipeUp()
            return true
        }
    }

    /**
     * Abre o diálogo padrão do sistema pra desinstalar — mesmo comportamento de qualquer
     * launcher (Nova, Pixel Launcher, etc). Não desinstala silenciosamente: o sistema
     * sempre exige confirmação explícita do usuário nessa tela antes de remover o app.
     * O ícone some da Home sozinho assim que a desinstalação é confirmada (ver
     * packageChangeReceiver, registrado em onResume).
     */
    private fun uninstallApp(app: AppEntry) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir a desinstalação de ${app.label}.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * A Home desenhada pequena (1/4) e desfocada — fundo do popup da tela de bloqueio (ver
     * HomeSnapshot). Papel de parede: o vídeo, se tiver; senão o do sistema (se o Android
     * deixar ler) ou um degradê escuro. Por cima, os ícones e widgets.
     */
    private fun captureBlurredHome(): Bitmap? {
        val root = findViewById<View>(R.id.contentColumn) ?: return null
        val w = window.decorView.width
        val h = window.decorView.height
        if (w <= 0 || h <= 0) return null
        val scale = 1f / 4f
        val small = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(small)
        canvas.drawColor(0xFF1C1917.toInt())
        canvas.save()
        canvas.scale(scale, scale)
        val video = if (videoWallpaper.visibility == View.VISIBLE && videoWallpaper.isAvailable) videoWallpaper.getBitmap(w / 4, h / 4) else null
        if (video != null) {
            canvas.drawBitmap(video, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        } else {
            try {
                android.app.WallpaperManager.getInstance(this).drawable?.let { d ->
                    // centerCrop
                    val dw = d.intrinsicWidth.coerceAtLeast(1)
                    val dh = d.intrinsicHeight.coerceAtLeast(1)
                    val f = maxOf(w.toFloat() / dw, h.toFloat() / dh)
                    val sw = (dw * f).toInt()
                    val sh = (dh * f).toInt()
                    d.setBounds((w - sw) / 2, (h - sh) / 2, (w - sw) / 2 + sw, (h - sh) / 2 + sh)
                    d.draw(canvas)
                }
            } catch (e: Exception) {
                // O Android não deixa ler o papel de parede do sistema (Android 14+). Um
                // papel de parede bem desfocado é praticamente um degradê das cores dele —
                // e essas cores o Android libera.
                drawWallpaperColors(canvas, w, h)
            }
        }
        try {
            root.draw(canvas)
        } catch (e: Exception) {
            // Algum ícone com bitmap de hardware não desenha em canvas comum — fica só o fundo.
        }
        canvas.restore()
        return HomeSnapshot.boxBlur(small, 1, 2)
    }

    private fun drawWallpaperColors(canvas: android.graphics.Canvas, w: Int, h: Int) {
        val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            android.app.WallpaperManager.getInstance(this).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
        } else null
        val primary = colors?.primaryColor?.toArgb() ?: return
        val secondary = colors.secondaryColor?.toArgb() ?: primary
        val tertiary = colors.tertiaryColor?.toArgb() ?: secondary
        val paint = android.graphics.Paint()
        paint.shader = android.graphics.LinearGradient(0f, 0f, w * 0.3f, h.toFloat(), intArrayOf(primary, secondary, tertiary), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    // ============================== GRADE DA HOME (células) ==============================

    private val widgetViews = mutableMapOf<String, View>()

    companion object {
        private const val WIDGET_CLOCK = "widget:clock"
        private const val WIDGET_STATUS = "widget:status"
        private const val WIDGET_TASKS = "widget:tasks"
    }

    /** Move relógio, status e tarefas pra dentro da grade (cada um num quadro com respiro)
     * e liga os gestos de toque e segure. */
    private fun setupWorkspace() {
        workspace.columns = GuardianPrefs.homeGridColumns(this)
        listOf(
            WIDGET_CLOCK to findViewById<View>(R.id.clockContainer),
            WIDGET_STATUS to findViewById<View>(R.id.statusPill),
            WIDGET_TASKS to tasksSectionContainer
        ).forEach { (key, view) ->
            (view.parent as ViewGroup).removeView(view)
            val frame = FrameLayout(this)
            val pad = 6.dp
            frame.setPadding(pad, pad, pad, pad)
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
            val gravity = if (key == WIDGET_TASKS) Gravity.TOP else Gravity.CENTER_VERTICAL
            val height = if (key == WIDGET_TASKS) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
            frame.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height, gravity))
            val (minW, minH) = widgetMinSpan(key)
            workspace.addView(frame, HomeWorkspace.LayoutParams(defaultWidgetCell(key), key, isWidget = true, minW = minW, minH = minH))
            widgetViews[key] = frame
        }
        // Relógio acompanha o tamanho do widget (maior quando ele cresce, sem cortar quando encolhe).
        widgetViews[WIDGET_CLOCK]?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val hDp = v.height / resources.displayMetrics.density
            val wDp = v.width / resources.displayMetrics.density
            val size = minOf((hDp - 34f) * 0.72f, wDp * 0.22f).coerceIn(26f, 80f)
            if (kotlin.math.abs(clockText.textSize / resources.displayMetrics.scaledDensity - size) > 0.5f) {
                clockText.post { clockText.textSize = size }
            }
            val showDate = hDp >= 70f
            if ((dateText.visibility == View.VISIBLE) != showDate) dateText.post { dateText.visibility = if (showDate) View.VISIBLE else View.GONE }
        }

        workspace.listener = object : HomeWorkspace.Listener {
            override fun onIconLongPress(view: View, key: String) = showIconMenu(view, key)
            override fun onIconDragStarted(key: String) {
                iconMenu?.dismiss()
                iconMenu = null
            }
            override fun onIconDropped(key: String, cellX: Int, cellY: Int, targetKey: String?): Boolean =
                dropIcon(key, cellX, cellY, targetKey)
            override fun onWidgetEditStarted(key: String) {
                Toast.makeText(this@LauncherHomeActivity, "Arraste pra mover, puxe as bolinhas pra mudar o tamanho. Toque fora pra terminar.", Toast.LENGTH_SHORT).show()
            }
            override fun onWidgetChanged(key: String, cell: HomeWorkspace.Cell) {
                val positions = GuardianPrefs.homePositions(this@LauncherHomeActivity).toMutableMap()
                positions[key] = intArrayOf(cell.x, cell.y, cell.w, cell.h)
                GuardianPrefs.setHomePositions(this@LauncherHomeActivity, positions)
                // Ícones que ficaram embaixo do widget vão pra célula livre mais próxima.
                if (apps.isNotEmpty()) renderWorkspace(apps.associateBy { it.packageName }, persist = true)
            }
            override fun onEmptyLongPress() = showCustomizeDialog()
            override fun onLayoutGridChanged(columns: Int, rows: Int) {
                if (apps.isNotEmpty()) renderWorkspace(apps.associateBy { it.packageName }, persist = true)
            }
        }
    }

    private fun widgetMinSpan(key: String): Pair<Int, Int> = when (key) {
        WIDGET_STATUS -> minOf(3, workspace.columns) to 1
        WIDGET_TASKS -> 2 to 2
        else -> 2 to 1
    }

    private fun defaultWidgetCell(key: String): HomeWorkspace.Cell {
        val cols = workspace.columns
        return when (key) {
            WIDGET_CLOCK -> HomeWorkspace.Cell(0, 0, cols, 2)
            WIDGET_STATUS -> HomeWorkspace.Cell(0, 2, cols, 1)
            // 3 linhas cabem o título e os cards inteiros; em tela baixa, 2.
            else -> HomeWorkspace.Cell(0, 3, cols, if (workspace.rows >= 8) 3 else 2)
        }
    }

    private fun keyFor(item: GuardianPrefs.HomeItem): String = when (item) {
        is GuardianPrefs.HomeItem.AppItem -> "app:" + item.packageName
        is GuardianPrefs.HomeItem.FolderItem -> "folder:" + item.id
    }

    /**
     * Encaixa tudo nas células: widgets primeiro (posição salva, ou a padrão), depois os
     * ícones na posição salva — ou na 1ª célula livre se não tiver uma (recém-fixado) ou se
     * a salva estiver ocupada/fora da grade (ex: menos colunas agora). [persist] grava as
     * posições novas; false na 1ª pintura rápida, com a lista de apps ainda parcial.
     */
    private fun renderWorkspace(appsByPackage: Map<String, AppEntry>, persist: Boolean) {
        val cols = workspace.columns
        val rows = workspace.rows
        if (rows == 0) return // ainda não mediu; onLayoutGridChanged chama de novo
        val saved = GuardianPrefs.homePositions(this)
        val positions = saved.toMutableMap()
        val occupied = mutableListOf<HomeWorkspace.Cell>()

        fun clamp(c: HomeWorkspace.Cell, minW: Int, minH: Int): HomeWorkspace.Cell {
            val w = c.w.coerceIn(minOf(minW, cols), cols)
            val h = c.h.coerceIn(minOf(minH, rows), rows)
            return HomeWorkspace.Cell(c.x.coerceIn(0, cols - w), c.y.coerceIn(0, rows - h), w, h)
        }

        for ((key, view) in widgetViews) {
            val lp = view.layoutParams as HomeWorkspace.LayoutParams
            val (minW, minH) = widgetMinSpan(key)
            var cell = clamp(saved[key]?.let { HomeWorkspace.Cell(it[0], it[1], it[2], it[3]) } ?: defaultWidgetCell(key), minW, minH)
            if (occupied.any { it.overlaps(cell) }) cell = clamp(defaultWidgetCell(key), minW, minH)
            lp.cell = cell
            occupied.add(cell)
            positions[key] = intArrayOf(cell.x, cell.y, cell.w, cell.h)
        }

        val blocked = GuardianPrefs.blockedPackages(this)
        val paused = GuardianPrefs.isPauseAllActive(this)
        val alwaysAvailable = GuardianPrefs.alwaysAvailablePackages(this)
        val items = GuardianPrefs.homeLayout(this)
        val liveKeys = mutableSetOf<String>()
        var skipped = 0

        for (item in items) {
            val key = keyFor(item)
            val view = when (item) {
                is GuardianPrefs.HomeItem.AppItem -> {
                    val app = appsByPackage[item.packageName] ?: continue
                    bindAppIcon(key, app, (paused || app.packageName in blocked) && app.packageName !in alwaysAvailable, app.packageName in alwaysAvailable)
                }
                is GuardianPrefs.HomeItem.FolderItem -> {
                    val folderApps = item.packages.mapNotNull { appsByPackage[it] }
                    if (folderApps.isEmpty()) continue
                    bindFolderIcon(key, item, folderApps)
                }
            }
            var cell = saved[key]?.let { HomeWorkspace.Cell(it[0], it[1], 1, 1) }
            if (cell == null || cell.x >= cols || cell.y >= rows || occupied.any { it.overlaps(cell!!) }) {
                val free = workspace.firstFreeCell(occupied)
                if (free == null) {
                    skipped++
                    view.visibility = View.GONE
                    liveKeys.add(key)
                    continue
                }
                cell = HomeWorkspace.Cell(free.first, free.second, 1, 1)
            }
            view.visibility = View.VISIBLE
            (view.layoutParams as HomeWorkspace.LayoutParams).cell = cell
            occupied.add(cell)
            positions[key] = intArrayOf(cell.x, cell.y, 1, 1)
            liveKeys.add(key)
        }

        // Ícones que saíram da Home (desafixados, pasta desfeita, app desinstalado).
        (iconViews.keys - liveKeys).forEach { key ->
            iconViews.remove(key)?.let { workspace.removeView(it) }
            if (persist) positions.remove(key)
        }
        workspace.requestLayout()

        if (persist && positions.keys.any { k -> !saved.containsKey(k) || !saved[k]!!.contentEquals(positions[k]) } || persist && positions.size != saved.size) {
            GuardianPrefs.setHomePositions(this, positions)
        }
        if (persist && skipped > 0 && !homeFullWarned) {
            homeFullWarned = true
            Toast.makeText(this, "A tela inicial está cheia: $skipped app(s) ficaram só na gaveta.", Toast.LENGTH_LONG).show()
        }
        if (skipped == 0) homeFullWarned = false
        if (persist) scheduleBackdropCapture()
    }

    private fun bindAppIcon(key: String, app: AppEntry, blocked: Boolean, alwaysAvailable: Boolean): View {
        val view = iconViews.getOrPut(key) {
            layoutInflater.inflate(R.layout.item_app_icon, workspace, false).also {
                it.tag = HomeGridAdapter.AppViewHolder(it)
                workspace.addView(it, HomeWorkspace.LayoutParams(HomeWorkspace.Cell(0, 0, 1, 1), key, isWidget = false))
            }
        }
        (view.tag as HomeGridAdapter.AppViewHolder).bind(
            app, blocked, alwaysAvailable, false,
            { entry, icon -> AppRepository.launch(this, entry.packageName, icon) }, { _, _ -> }
        )
        disableChildLongPress(view, R.id.iconFrame)
        return view
    }

    private fun bindFolderIcon(key: String, folder: GuardianPrefs.HomeItem.FolderItem, folderApps: List<AppEntry>): View {
        val view = iconViews.getOrPut(key) {
            layoutInflater.inflate(R.layout.item_folder_icon, workspace, false).also {
                it.tag = HomeGridAdapter.FolderViewHolder(it)
                workspace.addView(it, HomeWorkspace.LayoutParams(HomeWorkspace.Cell(0, 0, 1, 1), key, isWidget = false))
            }
        }
        (view.tag as HomeGridAdapter.FolderViewHolder).bind(folder, folderApps, false, { showFolderContents(it) }, { })
        disableChildLongPress(view, R.id.folderFrame)
        return view
    }

    /** Toque e segure é tratado pela grade (HomeWorkspace), não pelo ícone. */
    private fun disableChildLongPress(view: View, frameId: Int) {
        view.setOnLongClickListener(null)
        view.isLongClickable = false
        view.findViewById<View>(frameId)?.let {
            it.setOnLongClickListener(null)
            it.isLongClickable = false
        }
    }

    /** Menu do toque e segure num ícone da Home. Se o dedo andar, o menu fecha e o ícone
     * passa a ser arrastado (ver HomeWorkspace). */
    private fun showIconMenu(view: View, key: String) {
        iconMenu?.dismiss()
        val popup = PopupMenu(this, view)
        if (key.startsWith("app:")) {
            val pkg = key.removePrefix("app:")
            val app = apps.firstOrNull { it.packageName == pkg }
            popup.menu.add(0, 1, 0, "Remover da tela inicial")
            if (app != null) popup.menu.add(0, 2, 1, "Desinstalar")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        GuardianPrefs.unpin(this, pkg)
                        refreshHomeGrid()
                    }
                    2 -> app?.let { uninstallApp(it) }
                }
                true
            }
        } else {
            val folderId = key.removePrefix("folder:")
            val folder = GuardianPrefs.homeLayout(this).filterIsInstance<GuardianPrefs.HomeItem.FolderItem>().find { it.id == folderId }
            popup.menu.add(0, 1, 0, "Abrir")
            popup.menu.add(0, 2, 1, "Desfazer pasta")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> folder?.let { showFolderContents(it) }
                    2 -> dissolveFolder(folderId)
                }
                true
            }
        }
        popup.setOnDismissListener { if (iconMenu === popup) iconMenu = null }
        iconMenu = popup
        popup.show()
    }

    /** Soltou um ícone arrastado: célula vazia = só muda de lugar; em cima de outro ícone =
     * vira pasta (app+app) ou entra na pasta (app+pasta). Pasta em cima de pasta não junta. */
    private fun dropIcon(key: String, cellX: Int, cellY: Int, targetKey: String?): Boolean {
        val positions = GuardianPrefs.homePositions(this).toMutableMap()
        if (targetKey == null) {
            positions[key] = intArrayOf(cellX, cellY, 1, 1)
            GuardianPrefs.setHomePositions(this, positions)
            renderWorkspace(apps.associateBy { it.packageName }, persist = true)
            return true
        }
        val items = GuardianPrefs.homeLayout(this).toMutableList()
        val dragged = items.firstOrNull { keyFor(it) == key } ?: return false
        val target = items.firstOrNull { keyFor(it) == targetKey } ?: return false
        val merged: GuardianPrefs.HomeItem.FolderItem = when {
            target is GuardianPrefs.HomeItem.FolderItem && dragged is GuardianPrefs.HomeItem.AppItem ->
                target.copy(packages = target.packages + dragged.packageName)
            dragged is GuardianPrefs.HomeItem.FolderItem && target is GuardianPrefs.HomeItem.AppItem ->
                dragged.copy(packages = dragged.packages + target.packageName)
            dragged is GuardianPrefs.HomeItem.AppItem && target is GuardianPrefs.HomeItem.AppItem ->
                GuardianPrefs.HomeItem.FolderItem("folder-" + System.currentTimeMillis(), "Pasta", listOf(target.packageName, dragged.packageName))
            else -> return false
        }
        val targetIndex = items.indexOf(target)
        items.remove(dragged)
        items.remove(target)
        items.add(targetIndex.coerceAtMost(items.size), merged)
        positions.remove(key)
        positions.remove(targetKey)
        positions[keyFor(merged)] = intArrayOf(cellX, cellY, 1, 1)
        GuardianPrefs.setHomeLayout(this, items)
        GuardianPrefs.setHomePositions(this, positions)
        renderWorkspace(apps.associateBy { it.packageName }, persist = true)
        if (dragged is GuardianPrefs.HomeItem.AppItem && target is GuardianPrefs.HomeItem.AppItem) {
            Toast.makeText(this, "📁 Pasta criada", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /** Renomeia ou troca os apps de uma pasta; com 1 app ou menos, a pasta se desfaz e o
     * app que sobrou fica no lugar dela. */
    private fun replaceFolder(folderId: String, newName: String? = null, newPackages: List<String>? = null) {
        val items = GuardianPrefs.homeLayout(this).toMutableList()
        val index = items.indexOfFirst { it is GuardianPrefs.HomeItem.FolderItem && it.id == folderId }
        if (index == -1) return
        val current = items[index] as GuardianPrefs.HomeItem.FolderItem
        val packages = newPackages ?: current.packages
        val positions = GuardianPrefs.homePositions(this).toMutableMap()
        val folderPos = positions["folder:$folderId"]
        when {
            packages.isEmpty() -> items.removeAt(index)
            packages.size == 1 -> {
                items[index] = GuardianPrefs.HomeItem.AppItem(packages.first())
                positions.remove("folder:$folderId")
                if (folderPos != null) positions["app:" + packages.first()] = folderPos
            }
            else -> items[index] = current.copy(name = newName ?: current.name, packages = packages)
        }
        GuardianPrefs.setHomeLayout(this, items)
        GuardianPrefs.setHomePositions(this, positions)
        refreshHomeGrid()
    }

    /** Solta todos os apps da pasta: o 1º fica no lugar dela, os outros nas células livres. */
    private fun dissolveFolder(folderId: String) {
        val items = GuardianPrefs.homeLayout(this).toMutableList()
        val index = items.indexOfFirst { it is GuardianPrefs.HomeItem.FolderItem && it.id == folderId }
        if (index == -1) return
        val folder = items.removeAt(index) as GuardianPrefs.HomeItem.FolderItem
        folder.packages.forEachIndexed { i, pkg -> items.add(index + i, GuardianPrefs.HomeItem.AppItem(pkg)) }
        val positions = GuardianPrefs.homePositions(this).toMutableMap()
        positions.remove("folder:$folderId")?.let { pos -> folder.packages.firstOrNull()?.let { positions["app:$it"] = pos } }
        GuardianPrefs.setHomeLayout(this, items)
        GuardianPrefs.setHomePositions(this, positions)
        refreshHomeGrid()
    }

    /**
     * Abre o conteúdo de uma pasta: tocar num app de dentro fecha o diálogo e abre o app;
     * toque e segure oferece tirar da pasta ou desinstalar; "Renomear" pede um novo nome;
     * "Desfazer pasta" solta todos os apps de volta soltos na grade, no lugar da pasta.
     */
    private fun showFolderContents(folder: GuardianPrefs.HomeItem.FolderItem) {
        val view = layoutInflater.inflate(R.layout.dialog_folder_contents, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val titleText = view.findViewById<TextView>(R.id.folderDialogTitle)
        val recyclerView = view.findViewById<RecyclerView>(R.id.folderContentsRecyclerView)
        titleText.text = folder.name

        // Re-lê a pasta atual do adapter a cada chamada (em vez de fechar sobre a cópia
        // recebida no toque) — assim remover um app de dentro e continuar no diálogo
        // reflete o estado real sem precisar fechar e reabrir.
        fun currentFolder(): GuardianPrefs.HomeItem.FolderItem? =
            GuardianPrefs.homeLayout(this).filterIsInstance<GuardianPrefs.HomeItem.FolderItem>().find { it.id == folder.id }

        val appsByPackage = apps.associateBy { it.packageName }
        val contentsAdapter = AppGridAdapter(
            allApps = folder.packages.mapNotNull { appsByPackage[it] },
            onLaunch = { app -> dialog.dismiss(); AppRepository.launch(this, app.packageName) },
            onLongPress = { app, appHolder ->
                val optionsPopup = PopupMenu(this, appHolder.itemView)
                optionsPopup.menu.add(0, 1, 0, "Tirar da pasta")
                optionsPopup.menu.add(0, 2, 1, "Desinstalar")
                optionsPopup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            val remaining = currentFolder()?.packages?.filterNot { it == app.packageName } ?: emptyList()
                            replaceFolder(folder.id, newPackages = remaining)
                            if (remaining.size <= 1) {
                                dialog.dismiss() // a pasta se desfez sozinha (0 ou 1 app não é mais pasta)
                            } else {
                                (recyclerView.adapter as? AppGridAdapter)?.updatePinnedApps(remaining.mapNotNull { appsByPackage[it] })
                            }
                        }
                        2 -> uninstallApp(app)
                    }
                    true
                }
                optionsPopup.show()
            }
        )
        recyclerView.layoutManager = GridLayoutManager(this, 4)
        recyclerView.adapter = contentsAdapter
        contentsAdapter.updateBlockedState(GuardianPrefs.blockedPackages(this), GuardianPrefs.isPauseAllActive(this), GuardianPrefs.alwaysAvailablePackages(this))

        view.findViewById<TextView>(R.id.folderRenameButton).setOnClickListener {
            val input = android.widget.EditText(this)
            input.setText(folder.name)
            input.setTextColor(0xFFFFFFFF.toInt())
            AlertDialog.Builder(this)
                .setTitle("Renomear pasta")
                .setView(input)
                .setPositiveButton("Salvar") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        replaceFolder(folder.id, newName = newName)
                        titleText.text = newName
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        view.findViewById<TextView>(R.id.folderDissolveButton).setOnClickListener {
            dissolveFolder(folder.id)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateClock() {
        val now = Date()
        val locale = Locale("pt", "BR")
        clockText.text = SimpleDateFormat("HH:mm", locale).format(now)
        dateText.text = SimpleDateFormat("EEEE, d 'de' MMMM", locale).format(now)
            .replaceFirstChar { it.uppercase(locale) }
    }

    private fun updateBatteryAndTime() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        batteryText.text = "🔋 $level%"

        val dailyLimit = GuardianPrefs.dailyLimitMinutes(this)
        val used = GuardianPrefs.usedMinutesToday(this)
        val remaining = (dailyLimit - used).coerceAtLeast(0)
        remainingTimeText.text = when {
            // Modo "ganhar tempo": sem tarefa aprovada ainda, o limite é 0 de propósito.
            dailyLimit <= 0 && GuardianPrefs.taskUnlockMode(this) == "earn" -> "⏳ Ganhe tempo com tarefas"
            remaining <= 0 -> "⏳ Tempo de hoje acabou"
            remaining < 60 -> "⏳ ${remaining}min restantes"
            else -> "⏳ ${remaining / 60}h ${remaining % 60}min restantes"
        }

        // Verde -> âmbar -> vermelho conforme o tempo usado hoje se aproxima do limite,
        // pra dar um aviso visual antes do bloqueio por tempo esgotado pegar de surpresa.
        val ratio = if (dailyLimit > 0) (used.toFloat() / dailyLimit.toFloat()).coerceIn(0f, 1f) else 0f
        timeProgressBar.progress = (ratio * timeProgressBar.max).toInt()
        val color = when {
            ratio >= 0.9f -> 0xFFF43F5E.toInt() // vermelho/rosa
            ratio >= 0.7f -> 0xFFEAB308.toInt() // âmbar
            else -> 0xFF10B981.toInt() // verde
        }
        timeProgressBar.progressTintList = android.content.res.ColorStateList.valueOf(color)

        emergencyCallButton.visibility = if (GuardianPrefs.emergencyPhone(this) != null) View.VISIBLE else View.GONE
    }

    /** Mesmo comportamento do botão da tela de bloqueio (LockOverlayService.callEmergencyNumber):
     * abre o discador já preenchido, sem ligar sozinho — não precisa da permissão CALL_PHONE. */
    private fun callEmergencyNumber() {
        val phone = GuardianPrefs.emergencyPhone(this) ?: return
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir o discador.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Recarrega a lista real de apps instalados (PackageManager) e limpa da lista de
     * fixados persistida qualquer pacote que não exista mais — sem isso, um app
     * desinstalado ficaria "fixado" pra sempre em GuardianPrefs (só não aparecia porque
     * refreshHomeGrid já filtra pelo mapa de apps atuais, mas o registro morto continuaria
     * acumulando ali indefinidamente).
     */
    private fun refreshInstalledAppsAndGrid() {
        // Caminho rápido (o normal ao voltar pra Home): nada foi instalado/removido, usa o
        // cache na hora. Senão monta a lista em segundo plano e aplica quando ficar pronta.
        AppRepository.cachedIfFresh(this)?.let {
            applyInstalledApps(it)
            return
        }
        appLoader.execute {
            // 1ª abertura (processo novo): primeiro só os ícones da Home, que são poucos e
            // saem rápido; a lista completa (pra gaveta e limpeza dos fixados) vem depois.
            if (apps.isEmpty()) {
                val homePackages = GuardianPrefs.homeLayout(this).flatMap {
                    when (it) {
                        is GuardianPrefs.HomeItem.AppItem -> listOf(it.packageName)
                        is GuardianPrefs.HomeItem.FolderItem -> it.packages
                    }
                }
                if (homePackages.isNotEmpty()) {
                    val some = AppRepository.loadSome(this, homePackages)
                    runOnUiThread { if (!isDestroyed && apps.isEmpty() && some.isNotEmpty()) showHomeAppsEarly(some) }
                }
            }
            val loaded = AppRepository.loadLaunchableApps(this)
            runOnUiThread { if (!isDestroyed) applyInstalledApps(loaded) }
        }
    }

    /** Desenha a grade com só os apps da Home, sem mexer nos fixados (a lista parcial não
     * pode ser usada pra "limpar" apps que não estão nela). */
    private fun showHomeAppsEarly(homeApps: List<AppEntry>) {
        renderWorkspace(homeApps.associateBy { it.packageName }, persist = false)
    }

    private fun applyInstalledApps(loaded: List<AppEntry>) {
        apps = loaded
        // Primeira vez que a Home abre: fixa os primeiros apps automaticamente pra não
        // começar vazia. Depois disso o usuário controla 100% via toque e segure.
        if (!GuardianPrefs.hasInitializedPinnedApps(this) && apps.isNotEmpty()) {
            GuardianPrefs.setPinnedHomeApps(this, apps.take(8).map { it.packageName })
        }
        val installedPackages = apps.map { it.packageName }.toSet()
        val currentPinned = GuardianPrefs.pinnedHomeApps(this)
        val cleanedPinned = currentPinned.filter { it in installedPackages }
        if (cleanedPinned.size != currentPinned.size) {
            GuardianPrefs.setPinnedHomeApps(this, cleanedPinned)
        }
        refreshHomeGrid()
    }

    private fun refreshHomeGrid() {
        updateBatteryAndTime()
        if (apps.isEmpty()) {
            // Lista de apps ainda sendo montada (1ª abertura) — só os widgets por enquanto,
            // a grade entra assim que applyInstalledApps rodar.
            refreshTasksWidget()
            refreshScheduleHint()
            refreshPermissionWarning()
            return
        }
        // Refaz a tarefa antes da grade: o widget de tarefas pode ter mudado de conteúdo.
        refreshTasksWidget()
        renderWorkspace(apps.associateBy { it.packageName }, persist = true)

        refreshScheduleHint()
        refreshPermissionWarning()
    }

    /**
     * Sem Acessibilidade ou "Aparecer sobre outros apps" concedidas, o bloqueio inteiro
     * falha silenciosamente — a criança abre qualquer coisa achando que está tudo normal,
     * e o pai nem fica sabendo que a proteção não está funcionando. Esse banner insistente
     * avisa direto na Home (única coisa impossível de não ver) em vez de deixar isso
     * invisível, e leva com um toque pra tela de sistema certa pra resolver.
     */
    private fun refreshPermissionWarning() {
        val accessibilityGranted = isAccessibilityServiceEnabled()
        val overlayGranted = Settings.canDrawOverlays(this)

        if (accessibilityGranted && overlayGranted) {
            permissionWarningBanner.visibility = View.GONE
            return
        }

        permissionWarningBanner.visibility = View.VISIBLE
        val missing = mutableListOf<String>()
        if (!accessibilityGranted) missing.add("Acessibilidade")
        if (!overlayGranted) missing.add("Aparecer sobre outros apps")
        permissionWarningText.text = "⚠️ Sem ${missing.joinToString(" e ")}, o bloqueio não funciona. Toque pra corrigir."

        // Acessibilidade primeiro se as duas faltarem — é a mais crítica (sem ela nada é
        // detectado/bloqueado de verdade; sem a de sobreposição "só" a tela de bloqueio
        // não aparece, mas o app ainda é fechado/trocado pela Home).
        permissionWarningBanner.setOnClickListener {
            val intent = if (!accessibilityGranted) {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            } else {
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            }
            startActivity(intent)
        }
    }

    // Movido pra PermissionWatchdog (util compartilhado) — reusado também pelo watchdog
    // externo que roda independente da Home estar aberta, ver PermissionWatchdog.kt.
    private fun isAccessibilityServiceEnabled(): Boolean = PermissionWatchdog.isAccessibilityServiceEnabled(this)

    /** Pulso sutil de opacidade, infinito — pro banner de permissão faltando ser
     * "insistente" o bastante pra não passar despercebido, igual o chevron da gaveta. */
    private fun setupPermissionWarningPulse() {
        val pulse = ObjectAnimator.ofFloat(permissionWarningBanner, "alpha", 1f, 0.55f, 1f)
        pulse.duration = 1200
        pulse.repeatCount = ValueAnimator.INFINITE
        pulse.start()
    }

    /** "Próximo bloqueio agendado" — ver comentário no layout (nextScheduleHint). */
    private fun refreshScheduleHint() {
        if (GuardianPrefs.isScheduledBlockActive(this)) {
            // Bloqueio agendado já ativo agora: a sobreposição de bloqueio está cobrindo
            // a tela nesse exato momento, então mostrar "próximo bloqueio" aqui não faz
            // sentido (a Home nem deveria estar visível por baixo dela).
            nextScheduleHint.visibility = View.GONE
            return
        }
        val label = nextScheduledBlockLabel()
        nextScheduleHint.text = label
        nextScheduleHint.visibility = if (label != null) View.VISIBLE else View.GONE
    }

    /**
     * "🌙 Horário de Dormir às 22:00" (ou Estudo, o que vier primeiro) — usa o relógio do
     * próprio aparelho, só pra dar um aviso prévio; quem decide o bloqueio de verdade é
     * sempre o backend (ver GuardianPrefs.isScheduledBlockActive). Retorna null se nenhum
     * dos dois horários estiver habilitado.
     */
    private fun nextScheduledBlockLabel(): String? {
        val now = java.util.Calendar.getInstance()
        val nowMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)

        fun minutesUntilStart(schedule: GuardianPrefs.ScheduleWindow): Int? {
            if (!schedule.enabled) return null
            val parts = schedule.start.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val startMinutes = hour * 60 + minute
            val diff = startMinutes - nowMinutes
            return if (diff >= 0) diff else diff + 24 * 60 // já passou hoje — é amanhã
        }

        val bedtime = GuardianPrefs.bedtimeSchedule(this)
        val study = GuardianPrefs.studySchedule(this)
        val candidates = listOfNotNull(
            minutesUntilStart(bedtime)?.let { it to ("🌙 Horário de Dormir" to bedtime.start) },
            minutesUntilStart(study)?.let { it to ("📚 Horário de Estudo" to study.start) }
        )
        val (label, time) = candidates.minByOrNull { it.first }?.second ?: return null
        return "$label às $time"
    }

    // ============================== TAREFAS DIÁRIAS ==============================

    /**
     * Lê o cache gravado pelo ParentalAccessibilityService (GuardianPrefs) e atualiza o
     * widget. Tarefas enviadas nesta sessão mas ainda não confirmadas pelo próximo poll
     * aparecem otimisticamente como "aguardando aprovação" (ver [locallySubmittedTaskIds]).
     */
    private fun refreshTasksWidget() {
        val tasks = GuardianPrefs.parsedTodayTasks(this).map { task ->
            if (task.status == "pending" && task.id in locallySubmittedTaskIds) {
                task.copy(status = "submitted")
            } else {
                locallySubmittedTaskIds.remove(task.id) // status real já chegou, otimismo não é mais necessário
                task
            }
        }
        taskAdapter.updateTasks(tasks)
        // O widget fica sempre na grade (a criança escolheu onde e o tamanho dele); sem
        // tarefa hoje, mostra só um aviso curto em vez de sumir e deixar um buraco.
        tasksSectionContainer.visibility = View.VISIBLE
        tasksRecyclerView.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        if (tasks.isEmpty()) {
            tasksSectionTitle.text = "✨ Nenhuma tarefa hoje"
            tasksSectionTitle.setTextColor(0xCCFFFFFF.toInt())
            tasksSectionContainer.background = GradientDrawable().apply {
                cornerRadius = 18.dp.toFloat()
                setColor(0x991C1917.toInt())
            }
            return
        }

        val approvedCount = tasks.count { it.status == "approved" }
        val allDone = approvedCount == tasks.size
        // Âmbar enquanto falta pelo menos 1, verde quando tudo aprovado — mesmo par de
        // cores já usado na barra de tempo (ver updateBatteryAndTime), pra manter a
        // linguagem visual consistente entre os dois indicadores de "atenção" da Home.
        val accentColor = if (allDone) 0xFF10B981.toInt() else 0xFFEAB308.toInt()
        val borderColor = if (allDone) 0x6610B981.toInt() else 0x66EAB308.toInt()

        tasksSectionTitle.text = if (allDone) "✅ Tarefas de hoje concluídas" else "⚠️ Tarefas de hoje ($approvedCount/${tasks.size})"
        tasksSectionTitle.setTextColor(accentColor)

        val cardBackground = GradientDrawable()
        cardBackground.cornerRadius = 18.dp.toFloat()
        cardBackground.setColor(0xB21C1917.toInt())
        cardBackground.setStroke(2.dp, borderColor)
        tasksSectionContainer.background = cardBackground
    }

    /** Toque num card de tarefa pendente/recusada: abre a câmera nativa direto (sem galeria). */
    private fun launchTaskCamera(taskId: String) {
        try {
            val photoFile = File(cacheDir, "task_photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
            pendingPhotoTaskId = taskId
            pendingPhotoUri = uri
            takeTaskPhotoLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir a câmera.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Comprime a foto e envia em background (nunca na main thread) pro backend. */
    private fun submitTaskPhoto(taskId: String, photoUri: Uri) {
        Toast.makeText(this, "Enviando foto...", Toast.LENGTH_SHORT).show()
        taskSubmitExecutor.execute {
            try {
                val bitmap = contentResolver.openInputStream(photoUri)?.use { BitmapFactory.decodeStream(it) }
                if (bitmap == null) {
                    runOnUiThread { Toast.makeText(this, "Não foi possível ler a foto.", Toast.LENGTH_SHORT).show() }
                    return@execute
                }
                val resized = resizeBitmap(bitmap, maxDimension = 800)
                val output = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 70, output)
                val base64Photo = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)

                // Tenta primeiro o servidor que respondeu da última vez (cache do poll de
                // tarefas), e só cai pros outros candidatos se ele não responder agora.
                val candidates = (listOfNotNull(GuardianPrefs.lastWorkingServerUrl(this)) + ServerConfig.SERVER_URL_CANDIDATES).distinct()
                var submitted = false
                for (baseUrl in candidates) {
                    if (trySubmitToServer(baseUrl, taskId, base64Photo)) {
                        GuardianPrefs.setLastWorkingServerUrl(this, baseUrl)
                        submitted = true
                        break
                    }
                }

                runOnUiThread {
                    if (submitted) {
                        Toast.makeText(this, "Foto enviada! Aguardando aprovação.", Toast.LENGTH_LONG).show()
                        locallySubmittedTaskIds.add(taskId)
                        refreshTasksWidget()
                    } else {
                        Toast.makeText(this, "Não foi possível enviar a foto. Tente de novo.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Erro ao processar a foto.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun trySubmitToServer(baseUrl: String, taskId: String, base64Photo: String): Boolean {
        return try {
            val connection = URL("$baseUrl/api/tasks/$taskId/submit").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Content-Type", "application/json")
            GuardianPrefs.deviceToken(this).let { if (!it.isNullOrBlank()) connection.setRequestProperty("X-Device-Token", it) }
            val body = JSONObject().put("photoBase64", base64Photo).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val ok = connection.responseCode == 200
            connection.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** Redimensiona mantendo proporção — a foto não precisa de resolução total pra provar a tarefa. */
    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val ratio = min(min(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height), 1f)
        if (ratio >= 1f) return bitmap
        val width = (bitmap.width * ratio).toInt()
        val height = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun applyGridColumns() {
        workspace.columns = GuardianPrefs.homeGridColumns(this)
        if (apps.isNotEmpty()) renderWorkspace(apps.associateBy { it.packageName }, persist = true)
    }

    private fun applyThemeColors() {
        val (start, end) = GuardianPrefs.themeColors(this)
        val gradient = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end))
        gradient.cornerRadius = 12.dp.toFloat()
        requestTimeButton.background = gradient

        // drawerHandle virou LinearLayout(chevron, barra) — era FrameLayout com só a
        // barra (índice 0) antes do chevron de "arraste pra cima" ser adicionado.
        val handlePill = (drawerHandle as LinearLayout).getChildAt(1)
        val pillDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end))
        pillDrawable.cornerRadius = 999f
        handlePill.background = pillDrawable
    }

    /** Chevron balançando sozinho acima da alça da gaveta, só pra sugerir o gesto (visual). */
    private fun setupDrawerHandleBounce() {
        val chevron = findViewById<View>(R.id.drawerHandleChevron) ?: return
        val bounce = ObjectAnimator.ofFloat(chevron, "translationY", 0f, -6f, 0f)
        bounce.duration = 1200
        bounce.repeatCount = ValueAnimator.INFINITE
        bounce.start()
    }

    // ============================== VÍDEO DE FUNDO ANIMADO ==============================

    /**
     * Prepara (ou esconde) o vídeo de fundo. Leve de propósito: sem som (evita decodificar
     * áudio à toa), em loop, usando TextureView+MediaPlayer nativos do Android (sem
     * biblioteca extra tipo ExoPlayer). Só é chamado quando a Home está visível.
     */
    private fun setupVideoWallpaper() {
        val uriString = GuardianPrefs.videoWallpaperUri(this)
        if (uriString == null) {
            videoWallpaper.visibility = View.GONE
            releaseVideoPlayback()
            return
        }

        videoWallpaper.visibility = View.VISIBLE
        if (videoWallpaper.isAvailable) {
            startVideoPlayback(Uri.parse(uriString))
        } else {
            videoWallpaper.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    startVideoPlayback(Uri.parse(uriString))
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releaseVideoPlayback()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    /** Se a Home volta ao primeiro plano (ex: voltando da gaveta) e há vídeo, retoma. */
    private fun resumeVideoWallpaper() {
        if (mediaPlayer == null && GuardianPrefs.videoWallpaperUri(this) != null) {
            setupVideoWallpaper()
        }
    }

    private fun startVideoPlayback(uri: Uri) {
        releaseVideoPlayback()
        val surfaceTexture = videoWallpaper.surfaceTexture ?: return
        try {
            val player = MediaPlayer()
            player.setSurface(Surface(surfaceTexture))
            player.setDataSource(this, uri)
            player.isLooping = true
            player.setVolume(0f, 0f) // decorativo, sem som — mais leve e não incomoda
            player.setOnPreparedListener { mp ->
                fitVideoTransform(mp.videoWidth, mp.videoHeight)
                mp.start()
            }
            player.setOnErrorListener { _, _, _ ->
                // Arquivo removido/corrompido/sem acesso: desiste silenciosamente
                releaseVideoPlayback()
                videoWallpaper.visibility = View.GONE
                true
            }
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            videoWallpaper.visibility = View.GONE
        }
    }

    /**
     * Aplica o enquadramento (zoom/posição) que o usuário escolheu na simulação de recorte
     * — a mesma conta de VideoTransform usada lá, então o resultado final na Home é
     * exatamente o que foi visto e confirmado na prévia.
     */
    private fun fitVideoTransform(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        videoWallpaper.post {
            VideoTransform.apply(
                videoWallpaper,
                videoWidth,
                videoHeight,
                GuardianPrefs.videoCropScale(this),
                GuardianPrefs.videoCropPanX(this),
                GuardianPrefs.videoCropPanY(this)
            )
        }
    }

    private fun releaseVideoPlayback() {
        mediaPlayer?.apply {
            try {
                stop()
            } catch (e: Exception) {
                // já parado ou em estado inválido, sem problema
            }
            release()
        }
        mediaPlayer = null
    }

    // ============================== DIÁLOGO "PERSONALIZAR TELA INICIAL" ==============================

    private fun showCustomizeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_customize_launcher, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Preview em miniatura do vídeo já aplicado (player próprio do diálogo, separado
        // do da Home — os dois nunca tocam ao mesmo tempo na prática, já que o diálogo só
        // abre por cima da Home). Some sozinho quando o diálogo fecha (ver setOnDismissListener).
        var dialogVideoPlayer: MediaPlayer? = null
        val currentVideoUriForPreview = GuardianPrefs.videoWallpaperUri(this)
        if (currentVideoUriForPreview != null) {
            val previewContainer = view.findViewById<View>(R.id.videoPreviewContainer)
            val previewThumb = view.findViewById<TextureView>(R.id.videoPreviewThumb)
            previewContainer.visibility = View.VISIBLE
            val startPreview = {
                try {
                    val surfaceTexture = previewThumb.surfaceTexture
                    if (surfaceTexture != null) {
                        val player = MediaPlayer()
                        player.setSurface(Surface(surfaceTexture))
                        player.setDataSource(this, Uri.parse(currentVideoUriForPreview))
                        player.isLooping = true
                        player.setVolume(0f, 0f)
                        player.setOnPreparedListener { mp ->
                            VideoTransform.apply(
                                previewThumb, mp.videoWidth, mp.videoHeight,
                                GuardianPrefs.videoCropScale(this), GuardianPrefs.videoCropPanX(this), GuardianPrefs.videoCropPanY(this)
                            )
                            mp.start()
                        }
                        player.prepareAsync()
                        dialogVideoPlayer = player
                    }
                } catch (e: Exception) {
                    previewContainer.visibility = View.GONE
                }
            }
            if (previewThumb.isAvailable) {
                startPreview()
            } else {
                previewThumb.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = startPreview()
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
        dialog.setOnDismissListener {
            dialogVideoPlayer?.apply {
                try { stop() } catch (e: Exception) { /* já parado, sem problema */ }
                release()
            }
        }

        val columnsRow = view.findViewById<LinearLayout>(R.id.columnsRow)
        val currentColumns = GuardianPrefs.homeGridColumns(this)
        GuardianPrefs.GRID_COLUMN_OPTIONS.forEach { columns ->
            val button = Button(this)
            button.text = columns.toString()
            button.textSize = 14f
            button.isAllCaps = false
            button.setTextColor(0xFFFFFFFF.toInt())
            button.background = columnOptionBackground(selected = columns == currentColumns)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            params.marginEnd = 8.dp
            button.layoutParams = params
            button.setOnClickListener {
                GuardianPrefs.setHomeGridColumns(this, columns)
                dialog.dismiss()
            }
            columnsRow.addView(button)
        }

        val colorGrid = view.findViewById<android.widget.GridLayout>(R.id.colorGrid)
        val (currentStart, _) = GuardianPrefs.themeColors(this)
        GuardianPrefs.THEME_PRESETS.forEach { (startHex, endHex) ->
            val swatch = View(this)
            val size = 48.dp
            val params = android.widget.GridLayout.LayoutParams()
            params.width = size
            params.height = size
            params.setMargins(6.dp, 6.dp, 6.dp, 6.dp)
            swatch.layoutParams = params

            // Quadrado arredondado (era círculo) — mesma linguagem visual dos chips de
            // coluna acima e do protótipo (swatches em grid de "cantos vivos").
            val swatchDrawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(android.graphics.Color.parseColor(startHex), android.graphics.Color.parseColor(endHex)))
            swatchDrawable.cornerRadius = 16.dp.toFloat()
            if (android.graphics.Color.parseColor(startHex) == currentStart) {
                swatchDrawable.setStroke(3.dp, 0xFFFFFFFF.toInt())
            }
            swatch.background = swatchDrawable
            swatch.setOnClickListener {
                GuardianPrefs.setThemeColors(this, startHex, endHex)
                dialog.dismiss()
            }
            colorGrid.addView(swatch)
        }

        view.findViewById<Button>(R.id.chooseVideoButton).setOnClickListener {
            pickVideoLauncher.launch(arrayOf("video/*"))
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.removeVideoButton).setOnClickListener {
            GuardianPrefs.setVideoWallpaperUri(this, null)
            setupVideoWallpaper()
            dialog.dismiss()
        }
        // "Ajustar enquadramento" só faz sentido se já existe um vídeo escolhido — reabre
        // a mesma simulação de recorte, começando do zoom/posição já salvos.
        val adjustFramingButton = view.findViewById<Button>(R.id.adjustVideoFramingButton)
        val currentVideoUri = GuardianPrefs.videoWallpaperUri(this)
        if (currentVideoUri != null) {
            adjustFramingButton.visibility = View.VISIBLE
            adjustFramingButton.setOnClickListener {
                cropVideoLauncher.launch(
                    Intent(this, VideoCropActivity::class.java).putExtra(VideoCropActivity.EXTRA_VIDEO_URI, currentVideoUri)
                )
                dialog.dismiss()
            }
        }

        view.findViewById<Button>(R.id.closeDialogButton).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun columnOptionBackground(selected: Boolean): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.cornerRadius = 14.dp.toFloat()
        if (selected) {
            val (start, end) = GuardianPrefs.themeColors(this)
            drawable.orientation = GradientDrawable.Orientation.TL_BR
            drawable.colors = intArrayOf(start, end)
        } else {
            drawable.setColor(0x1AFFFFFF)
        }
        return drawable
    }
}
