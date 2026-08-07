package com.guardianshield.child.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class ParentalAccessibilityService : AccessibilityService() {

    private val blockedPackages = mutableSetOf(
        "com.zhiliaoapp.musically", // TikTok
        "com.dts.freefireth"        // Free Fire
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Intercepta quando um app é aberto
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d("GuardianShield", "App detectado em 1º plano: $packageName")

            if (blockedPackages.contains(packageName)) {
                Log.w("GuardianShield", "Bloqueando app não autorizado: $packageName")
                blockAppAndShowOverlay(packageName)
            }
        }
    }

    private fun blockAppAndShowOverlay(packageName: String) {
        // Redireciona a criança para a tela inicial (Home)
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)
    }

    override fun onInterrupt() {
        Log.e("GuardianShield", "Serviço de acessibilidade interrompido.")
    }
}
