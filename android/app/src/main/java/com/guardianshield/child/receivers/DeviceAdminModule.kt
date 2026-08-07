package com.guardianshield.child.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DeviceAdminModule : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i("GuardianShieldAdmin", "Administrador do Dispositivo ativado com sucesso!")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        Log.w("GuardianShieldAdmin", "Tentativa de desativar o Administrador do Dispositivo detectada!")
        return "Atenção: A desativação do GuardianShield requer autorização dos pais."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.e("GuardianShieldAdmin", "Administrador desativado.")
    }
}
