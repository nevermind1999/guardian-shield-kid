package com.guardianshield.child

import android.Manifest
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.guardianshield.child.model.AppEntry
import com.guardianshield.child.util.AppRepository
import com.guardianshield.child.util.GuardianPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela inicial (Home) nativa do GuardianShield: relógio, papel de parede real do aparelho,
 * status de bateria/tempo restante e um dock com apps favoritos + acesso à gaveta completa.
 * 100% Views nativas (sem WebView) — o app Capacitor/React fica só para pareamento/config,
 * aberto pelo botão de engrenagem ou por "Tempo extra".
 */
class LauncherHomeActivity : AppCompatActivity() {

    companion object {
        private const val WALLPAPER_PERMISSION_REQUEST_CODE = 4821
    }

    private lateinit var prefs: SharedPreferences
    private var apps: List<AppEntry> = emptyList()

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var batteryText: TextView
    private lateinit var remainingTimeText: TextView
    private lateinit var dockContainer: LinearLayout

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refreshDock() }
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
        dockContainer = findViewById(R.id.dockContainer)

        val contentColumn = findViewById<LinearLayout>(R.id.contentColumn)
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        // Modo tela cheia (edge-to-edge): o papel de parede/scrim ocupam a tela toda,
        // mas o conteúdo (relógio, dock, botão de config) precisa respeitar a barra de
        // status e a barra de navegação para não ficar cortado por trás delas.
        ViewCompat.setOnApplyWindowInsetsListener(contentColumn) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, 0, view.paddingRight, bars.bottom + 24.dp)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(settingsButton) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.topMargin = bars.top + 16.dp
            view.layoutParams = params
            insets
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.requestTimeButton).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("open_request_modal", true)
            startActivity(intent)
        }
        findViewById<FrameLayout>(R.id.drawerButtonFrame).setOnClickListener {
            startActivity(Intent(this, LauncherDrawerActivity::class.java))
        }

        loadWallpaper()
        apps = AppRepository.loadLaunchableApps(this)
        buildDock()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        clockHandler.post(clockTick)
        refreshDock()
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

    private fun loadWallpaper() {
        // A partir do Android 13, ler o papel de parede exige a permissão em tempo de
        // execução READ_EXTERNAL_STORAGE (não basta declarar no Manifest).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                WALLPAPER_PERMISSION_REQUEST_CODE
            )
            return
        }
        applyWallpaper()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WALLPAPER_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            applyWallpaper()
        }
        // Se negado, mantém o fundo gradiente padrão do layout — não insiste nem bloqueia a Home.
    }

    private fun applyWallpaper() {
        val wallpaperImage = findViewById<ImageView>(R.id.wallpaperImage)
        try {
            val drawable = WallpaperManager.getInstance(this).drawable
            if (drawable != null) wallpaperImage.setImageDrawable(drawable)
        } catch (e: Exception) {
            // Alguns aparelhos/versões do Android (13+) bloqueiam a leitura do papel de
            // parede para apps de terceiros mesmo com a permissão concedida. Sem problema:
            // mantém o fundo gradiente padrão do layout.
        }
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

    private fun buildDock() {
        dockContainer.removeAllViews()
        val blocked = GuardianPrefs.blockedPackages(this)
        val paused = GuardianPrefs.isPauseAllActive(this)
        val favorites = apps.filter { !paused && it.packageName !in blocked }.take(4)

        val inflater = LayoutInflater.from(this)
        favorites.forEach { app ->
            dockContainer.addView(createAppIconView(inflater, app, blocked, paused))
        }
    }

    private fun refreshDock() {
        updateBatteryAndTime()
        buildDock()
    }

    private fun createAppIconView(
        inflater: LayoutInflater,
        app: AppEntry,
        blocked: Set<String>,
        paused: Boolean
    ): View {
        val view = inflater.inflate(R.layout.item_app_icon, dockContainer, false)
        val isBlocked = paused || app.packageName in blocked

        val iconImage = view.findViewById<ImageView>(R.id.iconImage)
        val lockIcon = view.findViewById<ImageView>(R.id.lockIcon)
        val labelText = view.findViewById<TextView>(R.id.labelText)
        val iconFrame = view.findViewById<FrameLayout>(R.id.iconFrame)

        iconImage.setImageDrawable(app.icon)
        labelText.text = app.label
        lockIcon.visibility = if (isBlocked) View.VISIBLE else View.GONE
        iconFrame.alpha = if (isBlocked) 0.35f else 1f

        if (isBlocked) {
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            iconImage.colorFilter = ColorMatrixColorFilter(matrix)
            iconFrame.setOnClickListener(null)
            iconFrame.isClickable = false
        } else {
            iconImage.colorFilter = null
            iconFrame.isClickable = true
            iconFrame.setOnClickListener { AppRepository.launch(this, app.packageName) }
        }
        return view
    }
}
