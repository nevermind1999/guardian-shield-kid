package com.guardianshield.child

import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
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
 * a lista de bloqueio enquanto essa tela estiver aberta.
 */
class LauncherDrawerActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: AppGridAdapter

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refreshBlockedState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_launcher_drawer)

        val root = findViewById<LinearLayout>(R.id.drawerRoot)
        val basePadding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(basePadding, bars.top + basePadding, basePadding, bars.bottom + basePadding)
            insets
        }

        prefs = GuardianPrefs.of(this)
        val allApps: List<AppEntry> = AppRepository.loadLaunchableApps(this)

        val recyclerView = findViewById<RecyclerView>(R.id.appsRecyclerView)
        val density = resources.displayMetrics.density
        val spanCount = ((resources.displayMetrics.widthPixels / density) / 84).toInt().coerceAtLeast(3)
        recyclerView.layoutManager = GridLayoutManager(this, spanCount)

        adapter = AppGridAdapter(allApps) { app -> AppRepository.launch(this, app.packageName) }
        recyclerView.adapter = adapter

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        findViewById<EditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        refreshBlockedState()
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun refreshBlockedState() {
        adapter.updateBlockedState(GuardianPrefs.blockedPackages(this), GuardianPrefs.isPauseAllActive(this))
    }
}
