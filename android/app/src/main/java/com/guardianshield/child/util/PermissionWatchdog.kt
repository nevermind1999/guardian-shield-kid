package com.guardianshield.child.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.guardianshield.child.R
import com.guardianshield.child.receivers.ProtectionWatchdogReceiver
import com.guardianshield.child.services.ParentalAccessibilityService

/**
 * Checagem de "a proteção ainda está funcionando de verdade" (Acessibilidade + permissão de
 * sobreposição) que roda de propósito FORA do ParentalAccessibilityService — se é justamente
 * esse serviço que caiu (revogado manualmente, ou um otimizador de bateria agressivo de
 * fabricante desligando ele sozinho), ele não tem como se autodenunciar. O relógio daqui é
 * o AlarmManager (ver scheduleRepeating), acordado pelo sistema independente de o app estar
 * vivo — cobre o caso descrito na pendência original: "a proteção falha silenciosamente e o
 * pai só descobre se a criança voltar pra Home e reparar no banner".
 *
 * A Home (LauncherHomeActivity.refreshPermissionWarning) continua sendo o aviso PRINCIPAL,
 * sempre visível pra quem está com o aparelho na mão — este watchdog é só a rede de
 * segurança pro caso de a criança nunca voltar pra Home sozinha (ficar presa dentro de um
 * app "sempre disponível", por exemplo) ou passar um tempo longo sem tocar no aparelho.
 */
object PermissionWatchdog {
    /** Ação do broadcast interno disparado pelo AlarmManager a cada ciclo — ver
     * ProtectionWatchdogReceiver. Não precisa de <intent-filter> no manifest porque é
     * sempre enviado como intent EXPLÍCito (endereçado direto ao componente). */
    const val ACTION_CHECK = "com.guardianshield.child.action.CHECK_PROTECTION"

    private const val ALARM_REQUEST_CODE = 4242
    private const val NOTIFICATION_ID = 9001
    private const val CHANNEL_ID = "guardian_protection_watchdog"

    /**
     * Agenda (ou reagenda, é idempotente — mesmo PendingIntent sempre substitui o anterior)
     * a checagem periódica. Chamado em 3 pontos, de propósito redundantes entre si, pra não
     * depender de um único gatilho: ParentalAccessibilityService.onServiceConnected (assim
     * que a Acessibilidade é ligada), LauncherHomeActivity.onCreate (toda vez que a Home
     * abre) e ProtectionWatchdogReceiver ao reiniciar o aparelho (o alarme NÃO sobrevive
     * a um reboot sozinho, precisa ser refeito).
     */
    fun scheduleRepeating(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                checkPendingIntent(context)
            )
        } catch (e: Exception) {
            // Sem alarme agendado esta rodada — o próximo ponto de chamada (Home abrir de
            // novo, próximo boot) tenta de novo; não há o que fazer além disso aqui.
        }
    }

    private fun checkPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ProtectionWatchdogReceiver::class.java).setAction(ACTION_CHECK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }

    /**
     * O mesmo cálculo que LauncherHomeActivity.refreshPermissionWarning usava sozinho antes
     * — movido pra cá pra virar a fonte única, reusada tanto pelo banner da Home quanto por
     * este watchdog (ver isAccessibilityServiceEnabled em LauncherHomeActivity, agora só um
     * repasse pra esta função).
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${ParentalAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * Roda a checagem de verdade e mostra (ou esconde, se já resolvido) a notificação de
     * sistema. Chamada tanto pelo ciclo do alarme quanto uma vez, na hora, ao religar o
     * aparelho — pra não esperar até 15min pelo primeiro aviso depois de um boot.
     */
    fun runCheckAndNotify(context: Context) {
        val accessibilityGranted = isAccessibilityServiceEnabled(context)
        val overlayGranted = Settings.canDrawOverlays(context)
        val notificationManager = NotificationManagerCompat.from(context)

        if (accessibilityGranted && overlayGranted) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }

        val missing = mutableListOf<String>()
        if (!accessibilityGranted) missing.add("Acessibilidade")
        if (!overlayGranted) missing.add("Aparecer sobre outros apps")

        ensureChannel(context)
        val notification = buildNotification(context, missing, accessibilityFirst = !accessibilityGranted)
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS não concedida (Android 13+) — sem crash. O banner
            // pulsante da Home continua sendo o aviso principal nesse caso.
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Proteção comprometida",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avisa quando a Acessibilidade ou a permissão de sobreposição do " +
                "GuardianShield são desligadas e o bloqueio para de funcionar de verdade."
        }
        manager.createNotificationChannel(channel)
    }

    /** Toque na notificação leva direto pra tela de Configurações certa — mesma prioridade
     * do banner da Home (Acessibilidade primeiro, é a mais crítica das duas). */
    private fun buildNotification(context: Context, missing: List<String>, accessibilityFirst: Boolean): android.app.Notification {
        val targetAction = if (accessibilityFirst) Settings.ACTION_ACCESSIBILITY_SETTINGS else Settings.ACTION_MANAGE_OVERLAY_PERMISSION
        val settingsIntent = Intent(targetAction).apply {
            if (targetAction == Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
                data = Uri.parse("package:${context.packageName}")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, settingsIntent, piFlags)

        val text = "Sem ${missing.joinToString(" e ")}, o bloqueio não funciona de verdade. Toque pra corrigir agora."
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_hero)
            .setContentTitle("⚠️ Proteção do GuardianShield comprometida")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
    }
}
