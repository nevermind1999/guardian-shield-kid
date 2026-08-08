package com.guardianshield.child.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import com.guardianshield.child.MainActivity

class ParentalAccessibilityService : AccessibilityService() {

    private val defaultBlockedPackages = mutableSetOf(
        "com.zhiliaoapp.musically",   // TikTok
        "com.dts.freefireth",         // Free Fire
        "com.instagram.android",     // Instagram
        "com.google.android.youtube" // YouTube
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val prefs = getSharedPreferences("GuardianShieldPrefs", Context.MODE_PRIVATE)
            val isPauseAllActive = prefs.getBoolean("isPauseAllActive", false)

            Log.d("GuardianShield", "App em 1º plano: $packageName | PausaGeral: $isPauseAllActive")

            // 1. Se a PAUSA GERAL estiver ativa no SharedPreferences, força o retorno imediato para o Guardian Shield
            if (isPauseAllActive && packageName != "com.guardianshield.child") {
                Log.w("GuardianShield", "Pausa Geral ativa! Forçando retorno de $packageName para a tela de bloqueio.")
                bringGuardianShieldToFront()
                return
            }

            // 2. Se o app específico estiver na lista de bloqueados individuais
            if (defaultBlockedPackages.contains(packageName)) {
                Log.w("GuardianShield", "App bloqueado individualmente: $packageName")
                bringGuardianShieldToFront()
            }
        }
    }

    private fun bringGuardianShieldToFront() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.e("GuardianShield", "Serviço de acessibilidade interrompido.")
    }
}
