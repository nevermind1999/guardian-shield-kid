package com.guardianshield.child

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.guardianshield.child.model.AppEntry
import com.guardianshield.child.util.AppRepository
import com.guardianshield.child.util.GuardianPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Tela inicial (Home) nativa do GuardianShield: relógio, papel de parede real do aparelho
 * (desenhado pelo sistema via tema Launcher — ver styles.xml), status de bateria/tempo
 * restante e uma grade com quantos apps o usuário quiser fixar (colunas e cores
 * personalizáveis). Arrastar pra cima ou tocar na alça abre a gaveta com todos os apps.
 * 100% Views nativas (sem WebView) — o app Capacitor/React fica só para pareamento/config.
 */
class LauncherHomeActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var apps: List<AppEntry> = emptyList()
    private lateinit var homeAdapter: AppGridAdapter

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

    private lateinit var swipeUpDetector: GestureDetectorCompat

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

        swipeUpDetector = GestureDetectorCompat(this, SwipeUpListener { openDrawer() })
        drawerHandle.setOnClickListener { openDrawer() }
        val swipeTouchListener = View.OnTouchListener { _, event -> swipeUpDetector.onTouchEvent(event) }
        drawerHandle.setOnTouchListener(swipeTouchListener)
        findViewById<View>(R.id.clockContainer).setOnTouchListener(swipeTouchListener)
        findViewById<View>(R.id.statusPill).setOnTouchListener(swipeTouchListener)

        apps = AppRepository.loadLaunchableApps(this)
        // Primeira vez que a Home abre: fixa os primeiros apps automaticamente pra não
        // começar vazia. Depois disso o usuário controla 100% via toque e segure.
        if (!prefs.contains("pinnedHomeApps") && apps.isNotEmpty()) {
            GuardianPrefs.setPinnedHomeApps(this, apps.take(8).map { it.packageName }.toSet())
        }
        homeAdapter = AppGridAdapter(
            allApps = emptyList(),
            onLaunch = { app -> AppRepository.launch(this, app.packageName) },
            onLongPress = { app -> GuardianPrefs.togglePinned(this, app.packageName); refreshHomeGrid() }
        )
        homeGridRecyclerView.adapter = homeAdapter
        applyGridColumns()
        applyThemeColors()
        refreshHomeGrid()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        clockHandler.post(clockTick)
        refreshHomeGrid()
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
    }

    // A Home não fecha com o botão Voltar — igual a qualquer launcher de verdade
    override fun onBackPressed() {
        // no-op
    }

    private fun openDrawer() {
        startActivity(Intent(this, LauncherDrawerActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** Detecta um "flick" pra cima (arrastar rápido) pra abrir a gaveta de apps. */
    /**
     * Detecta "arrastar pra cima" tanto por velocidade (fling rápido, gesto real de dedo)
     * quanto por distância acumulada (arrasto mais devagar) — assim funciona tanto num
     * toque humano normal quanto num arrasto mais lento/deliberado.
     */
    private class SwipeUpListener(private val onSwipeUp: () -> Unit) : GestureDetector.SimpleOnGestureListener() {
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

        override fun onSingleTapUp(e: MotionEvent): Boolean = false
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
        val pinned = GuardianPrefs.pinnedHomeApps(this)
        val pinnedApps = apps.filter { it.packageName in pinned }
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

        val handlePill = (drawerHandle as android.widget.FrameLayout).getChildAt(0)
        val pillDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end))
        pillDrawable.cornerRadius = 999f
        handlePill.background = pillDrawable
    }

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
