package com.guardianshield.child.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import com.guardianshield.child.LauncherHomeActivity
import com.guardianshield.child.util.GuardianPrefs

class ParentalAccessibilityService : AccessibilityService() {

    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        startUsageTicker()
    }

    /**
     * Contador de tempo de tela: este serviço é o único componente do app que fica vivo o
     * tempo todo (a WebView/React só existe quando a criança abre as Configurações, e a Home
     * nativa pode ser destruída pelo sistema) — por isso é aqui, e não no JS, que o "usado
     * hoje" precisa ser contado de verdade. A cada minuto, se a tela estiver ligada, soma 1
     * minuto; ao ultrapassar o limite diário, bloqueia igual à Pausa Geral.
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

    private fun tickUsageOnce() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = powerManager?.isInteractive ?: true
        if (!screenOn) return

        GuardianPrefs.incrementUsedMinutesToday(this)
        if (GuardianPrefs.isDailyLimitExceeded(this) && !GuardianPrefs.isPauseAllActive(this)) {
            Log.w("GuardianShield", "Tempo limite diário esgotado — bloqueando dispositivo.")
            showOverlay("⏳ TEMPO ESGOTADO", "Você já usou todo o tempo de tela de hoje.\nFale com seus pais para liberar mais tempo.")
            bringGuardianShieldToFront()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val prefs = getSharedPreferences("GuardianShieldPrefs", Context.MODE_PRIVATE)
            val isPauseAllActive = prefs.getBoolean("isPauseAllActive", false)
            val blockedPackagesSet = prefs.getStringSet("blockedPackagesSet", emptySet()) ?: emptySet()
            val isTimeExpired = GuardianPrefs.isDailyLimitExceeded(this)

            Log.d("GuardianShield", "App em 1º plano: $packageName | PausaGeral: $isPauseAllActive | TempoEsgotado: $isTimeExpired | Bloqueados: $blockedPackagesSet")

            // 1. Se a PAUSA GERAL estiver ativa OU o tempo diário tiver acabado, nenhum app
            // pode ser aberto (exceto o próprio Guardian Shield)
            if (isPauseAllActive || isTimeExpired) {
                if (packageName != "com.guardianshield.child") {
                    val title: String
                    val message: String
                    if (isTimeExpired && !isPauseAllActive) {
                        title = "⏳ TEMPO ESGOTADO"
                        message = "Você já usou todo o tempo de tela de hoje.\nFale com seus pais para liberar mais tempo."
                    } else {
                        title = "🔒 DISPOSITIVO BLOQUEADO"
                        message = "Pausa Geral Ativa ou Tempo Limite Esgotado.\nFale com seus pais para liberar o uso."
                    }
                    Log.w("GuardianShield", "Bloqueio ativo! Forçando sobreposição para $packageName")
                    showOverlay(title, message)
                    bringGuardianShieldToFront()
                }
                return
            }

            // 2. Se a Pausa Geral NÃO estiver ativa, verifica se o aplicativo em 1º plano está bloqueado individualmente
            val isBlocked = blockedPackagesSet.contains(packageName) || isPackageBlockedFallback(packageName, blockedPackagesSet)

            if (isBlocked && packageName != "com.guardianshield.child") {
                Log.w("GuardianShield", "App Bloqueado Detectado: $packageName. Exibindo tela de bloqueio do app!")
                val appLabel = getAppNameFromPackage(packageName)
                showOverlay("🔒 APLICATIVO BLOQUEADO", "O aplicativo $appLabel foi bloqueado pelos seus pais.")
                bringGuardianShieldToFront()
            } else if (packageName != "com.guardianshield.child") {
                // Se um aplicativo permitido (ou tela inicial) está em 1º plano, remove a sobreposição se estiver visível
                hideOverlay()
            }
        }
    }

    private fun isPackageBlockedFallback(pkg: String, blockedSet: Set<String>): Boolean {
        for (blocked in blockedSet) {
            val bLower = blocked.lowercase()
            val pkgLower = pkg.lowercase()
            if (pkgLower == bLower) return true
            if ((bLower.contains("tiktok") || bLower.contains("musically")) && 
                (pkgLower.contains("musically") || pkgLower.contains("trill") || pkgLower.contains("tiktok"))) return true
            if (bLower.contains("instagram") && pkgLower.contains("instagram")) return true
            if (bLower.contains("youtube") && pkgLower.contains("youtube")) return true
            if (bLower.contains("freefire") && pkgLower.contains("freefire")) return true
        }
        return false
    }

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

    private fun showOverlay(title: String, message: String) {
        val overlayIntent = Intent(this, LockOverlayService::class.java)
        overlayIntent.action = "SHOW_OVERLAY"
        overlayIntent.putExtra("title", title)
        overlayIntent.putExtra("message", message)
        startService(overlayIntent)
    }

    private fun hideOverlay() {
        val overlayIntent = Intent(this, LockOverlayService::class.java)
        overlayIntent.action = "HIDE_OVERLAY"
        startService(overlayIntent)
    }

    private fun bringGuardianShieldToFront() {
        // Traz a Home nativa (LauncherHomeActivity) para frente — é o "lugar seguro"
        // pra onde a criança deve voltar quando um app é bloqueado.
        val intent = Intent(this, LauncherHomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.e("GuardianShield", "Serviço de acessibilidade interrompido.")
    }
}
