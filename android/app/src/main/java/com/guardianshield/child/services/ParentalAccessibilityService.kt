package com.guardianshield.child.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import com.guardianshield.child.MainActivity

class ParentalAccessibilityService : AccessibilityService() {

    private val defaultBlockedPackages = mutableSetOf(
        "com.zhiliaoapp.musically", // TikTok
        "com.dts.freefireth",       // Free Fire
        "com.instagram.android",   // Instagram
        "com.google.android.youtube" // YouTube
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Intercepta qualquer app aberto em 1º plano
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d("GuardianShield", "App detectado em 1º plano: $packageName")

            // 1. Se a PAUSA GERAL estiver ativa, nenhum outro app pode rodar (exceto o Guardian Shield)
            if (MainActivity.isPauseAllActive && packageName != "com.guardianshield.child") {
                Log.w("GuardianShield", "Pausa geral ativa! Redirecionando $packageName para a tela de bloqueio.")
                bringGuardianShieldToFront()
                return
            }

            // 2. Se o app específico estiver na lista de bloqueados individuais
            if (defaultBlockedPackages.contains(packageName)) {
                Log.w("GuardianShield", "Bloqueando app individual: $packageName")
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
