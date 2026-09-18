package com.guardianshield.child.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.guardianshield.child.util.PermissionWatchdog

/**
 * Dois gatilhos, mesma checagem (ver PermissionWatchdog.runCheckAndNotify):
 *
 * 1. ACTION_BOOT_COMPLETED — alarmes do AlarmManager NÃO sobrevivem a um reboot sozinhos,
 *    então isso reagenda o ciclo (scheduleRepeating) toda vez que o aparelho liga, além de
 *    rodar uma checagem imediata na hora (em vez de esperar até 15min pelo primeiro aviso
 *    se a Acessibilidade tiver sido desligada por algum otimizador de bateria durante o
 *    boot, como acontece em alguns fabricantes).
 * 2. PermissionWatchdog.ACTION_CHECK — disparado pelo próprio AlarmManager a cada ciclo
 *    (~15min), enviado como intent explícita direto pra este componente, por isso não
 *    precisa de <intent-filter> pra essa ação no manifest.
 */
class ProtectionWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                PermissionWatchdog.scheduleRepeating(context)
                PermissionWatchdog.runCheckAndNotify(context)
            }
            PermissionWatchdog.ACTION_CHECK -> {
                PermissionWatchdog.runCheckAndNotify(context)
            }
        }
    }
}
