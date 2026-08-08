package com.guardianshield.child.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import com.guardianshield.child.LauncherHomeActivity

class ParentalAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val prefs = getSharedPreferences("GuardianShieldPrefs", Context.MODE_PRIVATE)
            val isPauseAllActive = prefs.getBoolean("isPauseAllActive", false)
            val blockedPackagesSet = prefs.getStringSet("blockedPackagesSet", emptySet()) ?: emptySet()

            Log.d("GuardianShield", "App em 1º plano: $packageName | PausaGeral: $isPauseAllActive | Bloqueados: $blockedPackagesSet")

            // 1. Se a PAUSA GERAL estiver ativa, nenhum app pode ser aberto (exceto o próprio Guardian Shield)
            if (isPauseAllActive) {
                if (packageName != "com.guardianshield.child") {
                    Log.w("GuardianShield", "Pausa Geral Ativa! Forçando sobreposição para $packageName")
                    showOverlay("🔒 DISPOSITIVO BLOQUEADO", "Pausa Geral Ativa ou Tempo Limite Esgotado.\nFale com seus pais para liberar o uso.")
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
