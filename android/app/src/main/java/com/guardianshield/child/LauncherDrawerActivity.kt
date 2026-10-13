package com.guardianshield.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.guardianshield.child.model.AppEntry
import com.guardianshield.child.util.AppRepository
import com.guardianshield.child.util.GuardianPrefs

/**
 * Gaveta de apps nativa: todos os apps do aparelho, com busca. Mesmo estilo de bloqueio
 * (cinza, apagado, sem click) usado no dock da Home — refeito ao vivo se os pais mudarem
 * a lista de bloqueio enquanto essa tela estiver aberta. O fundo usa a mesma cor de tema
 * escolhida em "Personalizar tela inicial", como um brilho suave vindo de cima.
 */
class LauncherDrawerActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: AppGridAdapter
    private lateinit var root: LinearLayout
    private lateinit var emptyDrawerHint: TextView
    private lateinit var categoryChipsRow: LinearLayout
    private var allApps: List<AppEntry> = emptyList()
    private var selectedCategory: String? = null // null = "Todos"
    // Alfabético é o padrão sempre que a gaveta abre de novo (não persiste entre
    // sessões de propósito — é um jeito de "achar rápido agora", não uma preferência
    // permanente que valha a pena lembrar).
    private var sortByRecent = false

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    /** sortedBy é estável, então dentro do mesmo installedAt (ex: vários 0 de apps sem
     * PackageInfo legível) a ordem alfabética original de allApps continua valendo. */
    private fun sortedApps(): List<AppEntry> =
        if (sortByRecent) allApps.sortedByDescending { it.installedAt } else allApps

    private fun toggleSortMode(button: TextView) {
        sortByRecent = !sortByRecent
        button.text = if (sortByRecent) "Recentes" else "A-Z"
        adapter.updatePinnedApps(sortedApps())
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        runOnUiThread {
            if (key == "themeColorStart" || key == "themeColorEnd") {
                applyThemeBackground()
            } else {
                refreshBlockedState()
            }
        }
    }

    /** Dispara em qualquer desinstalação — recarrega a lista real na hora, pra um app
     * removido não continuar aparecendo (e não crashar ao tentar abrir um pacote morto)
     * caso a gaveta já estivesse aberta quando a desinstalação aconteceu. */
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppRepository.invalidate()
            reloadAppsInBackground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_launcher_drawer)

        root = findViewById(R.id.drawerRoot)
        applyThemeBackground()

        val basePadding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(basePadding, bars.top + basePadding, basePadding, bars.bottom + basePadding)
            insets
        }

        prefs = GuardianPrefs.of(this)
        // Normalmente já está em cache (a Home montou a lista) — abre na hora. Se não
        // estiver, abre vazia e preenche assim que a lista ficar pronta em segundo plano.
        val cachedApps = AppRepository.cachedIfFresh(this)
        allApps = cachedApps ?: emptyList()
        emptyDrawerHint = findViewById(R.id.emptyDrawerHint)
        categoryChipsRow = findViewById(R.id.categoryChipsRow)
        setupCategoryChips()

        val recyclerView = findViewById<RecyclerView>(R.id.appsRecyclerView)
        val density = resources.displayMetrics.density
        val spanCount = ((resources.displayMetrics.widthPixels / density) / 84).toInt().coerceAtLeast(3)
        recyclerView.layoutManager = GridLayoutManager(this, spanCount)

        adapter = AppGridAdapter(
            allApps = allApps,
            // Só na gaveta os bloqueados vão pro final da lista — na Home a ordem é a que
            // o usuário escolheu arrastando, então lá isso ficaria estranho.
            sortBlockedLast = true,
            onLaunch = { app -> AppRepository.launch(this, app.packageName) },
            onLongPress = { app, _ ->
                val nowPinned = GuardianPrefs.togglePinned(this, app.packageName)
                val message = if (nowPinned) "${app.label} fixado na tela inicial" else "${app.label} removido da tela inicial"
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        )
        recyclerView.adapter = adapter

        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            finish()
            overridePendingTransition(R.anim.stay_still, R.anim.slide_down_out)
        }

        findViewById<TextView>(R.id.sortToggleButton).setOnClickListener { toggleSortMode(it as TextView) }

        findViewById<EditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
                updateEmptyState()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        updateEmptyState()
        if (cachedApps == null) reloadAppsInBackground()
    }

    private val appLoader = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onDestroy() {
        super.onDestroy()
        appLoader.shutdown()
    }

    private fun reloadAppsInBackground() {
        appLoader.execute {
            val loaded = AppRepository.loadLaunchableApps(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                allApps = loaded
                adapter.updatePinnedApps(sortedApps())
                setupCategoryChips()
                adapter.filterByCategory(selectedCategory)
                updateEmptyState()
                refreshBlockedState()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        val packageFilter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply { addDataScheme("package") }
        registerReceiver(packageChangeReceiver, packageFilter)
        refreshBlockedState()
        applyThemeBackground()
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (e: IllegalArgumentException) {
            // já estava desregistrado, nada a fazer
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.stay_still, R.anim.slide_down_out)
    }

    /** "Nenhum app encontrado" só quando a busca realmente não bate com nada — não quando
     * a lista está vazia por falta de apps instalados (caso raro, quase nunca acontece). */
    private fun updateEmptyState() {
        emptyDrawerHint.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    /**
     * Chips de filtro por categoria ("Todos" + as que existirem de verdade entre os apps
     * instalados — AppEntry.category já vem pronto do AppRepository). Reconstrói do zero
     * (em vez de só reestilizar) sempre que a lista de apps pode ter mudado, porque uma
     * categoria pode ter deixado de existir (ex: o único jogo instalado foi desinstalado).
     */
    private fun setupCategoryChips() {
        val preferredOrder = listOf("Aplicativos", "Jogos", "Sistema")
        val categoriesPresent = allApps.map { it.category }.distinct()
        val orderedCategories = preferredOrder.filter { it in categoriesPresent } +
            categoriesPresent.filterNot { it in preferredOrder }
        val chipLabels = listOf<String?>(null) + orderedCategories // null = "Todos"

        if (selectedCategory != null && selectedCategory !in orderedCategories) {
            selectedCategory = null // categoria escolhida sumiu (ex: desinstalou o último jogo)
        }

        categoryChipsRow.removeAllViews()
        chipLabels.forEach { category ->
            val chip = TextView(this)
            chip.text = category ?: "Todos"
            chip.textSize = 13f
            chip.setTextColor(0xFFFFFFFF.toInt())
            chip.setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            chip.gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.marginEnd = 8.dp
            chip.layoutParams = params
            chip.background = chipBackground(selected = category == selectedCategory)
            chip.setOnClickListener {
                selectedCategory = category
                adapter.filterByCategory(category)
                updateEmptyState()
                restyleCategoryChips()
            }
            categoryChipsRow.addView(chip)
        }
    }

    /** Só troca o fundo de cada chip existente (sem recriar) — usado ao trocar a seleção. */
    private fun restyleCategoryChips() {
        for (i in 0 until categoryChipsRow.childCount) {
            val chip = categoryChipsRow.getChildAt(i) as TextView
            val category = if (chip.text == "Todos") null else chip.text.toString()
            chip.background = chipBackground(selected = category == selectedCategory)
        }
    }

    private fun chipBackground(selected: Boolean): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.cornerRadius = 999f
        if (selected) {
            val (start, end) = GuardianPrefs.themeColors(this)
            drawable.orientation = GradientDrawable.Orientation.TL_BR
            drawable.colors = intArrayOf(start, end)
        } else {
            drawable.setColor(0x1AFFFFFF)
        }
        return drawable
    }

    private fun refreshBlockedState() {
        adapter.updateBlockedState(GuardianPrefs.blockedPackages(this), GuardianPrefs.isPauseAllActive(this), GuardianPrefs.alwaysAvailablePackages(this))
    }

    /** Mesmo fundo escuro de sempre, com um brilho radial sutil na cor de tema — igual ao
     * degradê do painel de configurações (React), pra Home e Gaveta combinarem. */
    private fun applyThemeBackground() {
        val (start, _) = GuardianPrefs.themeColors(this)
        val tinted = ColorUtils.setAlphaComponent(start, 90)
        val gradient = GradientDrawable()
        gradient.gradientType = GradientDrawable.RADIAL_GRADIENT
        gradient.gradientRadius = resources.displayMetrics.widthPixels.toFloat() * 1.1f
        gradient.setGradientCenter(0.5f, 0f)
        gradient.colors = intArrayOf(tinted, 0xFF0B132B.toInt())
        root.background = gradient
    }
}
