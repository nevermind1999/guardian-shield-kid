package com.guardianshield.child.services

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class VpnFilterService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val builder = Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.3") // DNS da Cloudflare com bloqueio automático de sites adultos e malware
                .setSession("GuardianShieldSafeWeb")

            vpnInterface = builder.establish()
            Log.i("GuardianShieldVPN", "VPN Local de filtro web ativada com sucesso!")
        } catch (e: Exception) {
            Log.e("GuardianShieldVPN", "Erro ao iniciar VPN local", e)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        vpnInterface?.close()
        vpnInterface = null
    }
}
