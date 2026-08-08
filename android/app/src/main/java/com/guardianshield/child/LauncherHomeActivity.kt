package com.guardianshield.child

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.guardianshield.child.model.AppEntry
import com.guardianshield.child.util.AppRepository
import com.guardianshield.child.util.GuardianPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

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
    private lateinit var homeAdapter: AppGridAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var batteryText: TextView
    private lateinit var remainingTimeText: TextView
    private lateinit var requestTimeButton: Button
    private lateinit var drawerHandle: View
    private lateinit var homeGridRecyclerView: RecyclerView
    private lateinit var emptyHomeHint: View
    private lateinit var videoWallpaper: TextureView

    private lateinit var swipeUpDetector: GestureDetectorCompat

    // --- Vídeo de fundo: só existe enquanto a Home está em primeiro plano ---
    private var mediaPlayer: MediaPlayer? = null
    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Alguns provedores não suportam permissão persistente; segue mesmo assim,
                // pode parar de funcionar após reiniciar o app nesse caso raro.
            }
            GuardianPrefs.setVideoWallpaperUri(this, uri.toString())
            setupVideoWallpaper()
        }
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        runOnUiThread {
            when (key) {
                "homeGridColumns" -> applyGridColumns()
                "themeColorStart", "themeColorEnd" -> applyThemeColors()
                "homeVideoWallpaperUri" -> setupVideoWallpaper()
                else -> refreshHomeGrid()
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryAndTime()
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
        requestTimeButton = findViewById(R.id.requestTimeButton)
        drawerHandle = findViewById(R.id.drawerHandle)
        homeGridRecyclerView = findViewById(R.id.homeGridRecyclerView)
        emptyHomeHint = findViewById(R.id.emptyHomeHint)
        videoWallpaper = findViewById(R.id.videoWallpaper)

        val contentColumn = findViewById<LinearLayout>(R.id.contentColumn)
        // Modo tela cheia (edge-to-edge): o wallpaper ocupa a tela toda, mas o conteúdo
        // precisa respeitar a barra de status e a de navegação pra não ficar cortado.
        ViewCompat.setOnApplyWindowInsetsListener(contentColumn) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<ImageButton>(R.id.paletteButton).setOnClickListener {
            showCustomizeDialog()
        }
        requestTimeButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("open_request_modal", true)
            startActivity(intent)
        }

        // A alça responde tanto a um toque simples quanto a arrastar pra cima. Precisa ser
        // um detector PRÓPRIO (não um OnClickListener separado): ter os dois num mesmo View
        // faz o toque "sumir" — o OnTouchListener consome o ACTION_DOWN pra rastrear o
        // gesto e o clique normal nunca chega a disparar.
        val drawerHandleDetector = GestureDetectorCompat(this, SwipeUpListener(treatTapAsSwipe = true) { openDrawer() })
        drawerHandle.setOnTouchListener { _, event -> drawerHandleDetector.onTouchEvent(event) }

        // Relógio e pílula de status só respondem a arrastar pra cima (toque simples neles
        // não faz nada além do que já fazem, ex: o botão "Tempo extra" dentro da pílula).
        swipeUpDetector = GestureDetectorCompat(this, SwipeUpListener(treatTapAsSwipe = false) { openDrawer() })
        val swipeTouchListener = View.OnTouchListener { _, event -> swipeUpDetector.onTouchEvent(event) }
        findViewById<View>(R.id.clockContainer).setOnTouchListener(swipeTouchListener)
        findViewById<View>(R.id.statusPill).setOnTouchListener(swipeTouchListener)

        apps = AppRepository.loadLaunchableApps(this)
        // Primeira vez que a Home abre: fixa os primeiros apps automaticamente pra não
        // começar vazia. Depois disso o usuário controla 100% via toque e segure.
        if (!GuardianPrefs.hasInitializedPinnedApps(this) && apps.isNotEmpty()) {
            GuardianPrefs.setPinnedHomeApps(this, apps.take(8).map { it.packageName })
        }
        homeAdapter = AppGridAdapter(
            allApps = emptyList(),
            onLaunch = { app -> AppRepository.launch(this, app.packageName) },
            onLongPress = { app, holder -> showAppOptionsMenu(app, holder) }
        )
        homeGridRecyclerView.adapter = homeAdapter
        setupDragToReorder()
        applyGridColumns()
        applyThemeColors()
        refreshHomeGrid()
        setupVideoWallpaper()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        clockHandler.post(clockTick)
        refreshHomeGrid()
        resumeVideoWallpaper()
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        try {
            unregisterReceiver(batteryReceiver)
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
        // no-op
    }

    private fun openDrawer() {
        startActivity(Intent(this, LauncherDrawerActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ============================== GAVETA: gesto de arrastar ==============================

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

    // ============================== GRID DA HOME: mover / remover ==============================

    /** Arrastar um ícone (chamado explicitamente via menu "Mover") reordena a grade ao vivo. */
    private fun setupDragToReorder() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            // Desligado: o arrastar só começa quando o usuário escolhe "Mover" no menu do
            // toque e segure (showAppOptionsMenu), não automaticamente em qualquer toque longo.
            override fun isLongPressDragEnabled() = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                homeAdapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Não usamos swipe-para-remover — só arrastar pra reordenar.
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                GuardianPrefs.setPinnedHomeApps(this@LauncherHomeActivity, homeAdapter.currentPackageOrder())
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(homeGridRecyclerView)
    }

    /** Toque e segure num app da Home: mostra as opções em vez de simplesmente sumir com ele. */
    private fun showAppOptionsMenu(app: AppEntry, holder: RecyclerView.ViewHolder) {
        val popup = PopupMenu(this, holder.itemView)
        popup.menu.add(0, 1, 0, "Mover")
        popup.menu.add(0, 2, 1, "Remover da tela inicial")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> itemTouchHelper.startDrag(holder)
                2 -> {
                    GuardianPrefs.unpin(this, app.packageName)
                    refreshHomeGrid()
                }
            }
            true
        }
        popup.show()
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
        remainingTimeText.text = "⏳ ${remaining / 60}h ${remaining % 60}m restantes"
    }

    private fun refreshHomeGrid() {
        updateBatteryAndTime()
        val pinnedOrder = GuardianPrefs.pinnedHomeApps(this)
        val appsByPackage = apps.associateBy { it.packageName }
        val pinnedApps = pinnedOrder.mapNotNull { appsByPackage[it] }
        homeAdapter.updatePinnedApps(pinnedApps)
        homeAdapter.updateBlockedState(GuardianPrefs.blockedPackages(this), GuardianPrefs.isPauseAllActive(this))

        val isEmpty = pinnedApps.isEmpty()
        homeGridRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyHomeHint.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun applyGridColumns() {
        homeGridRecyclerView.layoutManager = GridLayoutManager(this, GuardianPrefs.homeGridColumns(this))
    }

    private fun applyThemeColors() {
        val (start, end) = GuardianPrefs.themeColors(this)
        val gradient = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end))
        gradient.cornerRadius = 10.dp.toFloat()
        requestTimeButton.background = gradient

        val handlePill = (drawerHandle as FrameLayout).getChildAt(0)
        val pillDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end))
        pillDrawable.cornerRadius = 999f
        handlePill.background = pillDrawable
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

    /** Ajusta a matriz do TextureView pra cobrir a tela sem distorcer (igual a centerCrop). */
    private fun fitVideoTransform(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        videoWallpaper.post {
            val viewWidth = videoWallpaper.width.toFloat()
            val viewHeight = videoWallpaper.height.toFloat()
            if (viewWidth <= 0f || viewHeight <= 0f) return@post

            val scale = max(viewWidth / videoWidth, viewHeight / videoHeight)
            val scaledWidth = videoWidth * scale
            val scaledHeight = videoHeight * scale

            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate((viewWidth - scaledWidth) / 2f, (viewHeight - scaledHeight) / 2f)
            videoWallpaper.setTransform(matrix)
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

            val swatchDrawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(android.graphics.Color.parseColor(startHex), android.graphics.Color.parseColor(endHex)))
            swatchDrawable.shape = GradientDrawable.OVAL
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

        view.findViewById<Button>(R.id.closeDialogButton).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun columnOptionBackground(selected: Boolean): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.cornerRadius = 10.dp.toFloat()
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
