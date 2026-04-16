package com.whatsappguard

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Serviço de escuta de notificações que complementa o Accessibility Service.
 * Detecta notificações de chamada do WhatsApp e pode descartá-las.
 */
class CallBlockerService : NotificationListenerService() {

    companion object {
        private const val TAG = "WhatsAppGuard"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"

        private val CALL_NOTIFICATION_TEXTS = listOf(
            "Chamada de voz",
            "Chamada de vídeo",
            "Voice call",
            "Video call",
            "Ligação",
            "Incoming voice call",
            "Incoming video call"
        )

        var isServiceRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.i(TAG, "CallBlockerService criado")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.i(TAG, "CallBlockerService destruído")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName != WHATSAPP_PACKAGE) return

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("block_calls", true)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras

        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val bigText = extras.getString(Notification.EXTRA_BIG_TEXT) ?: ""

        val combinedText = "$title $text $bigText"
        val isCallNotification = CALL_NOTIFICATION_TEXTS.any {
            combinedText.contains(it, ignoreCase = true)
        }

        if (isCallNotification) {
            Log.i(TAG, "📞 Notificação de chamada WhatsApp detectada: $title - $text")

            // Tentar usar a ação "Recusar" da notificação se disponível
            val actions = notification.actions
            if (actions != null) {
                for (action in actions) {
                    val actionTitle = action.title?.toString() ?: ""
                    if (actionTitle.contains("Recusar", ignoreCase = true) ||
                        actionTitle.contains("Decline", ignoreCase = true) ||
                        actionTitle.contains("Rejeitar", ignoreCase = true) ||
                        actionTitle.contains("Desligar", ignoreCase = true)) {
                        try {
                            action.actionIntent.send()
                            Log.i(TAG, "✅ Chamada rejeitada via ação de notificação: $actionTitle")
                            return
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao rejeitar chamada via notificação", e)
                        }
                    }
                }
            }

            // Se tem flag de chamada em andamento, cancelar a notificação
            if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
                cancelNotification(sbn.key)
                Log.i(TAG, "⚠️ Notificação de chamada cancelada")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Não precisamos fazer nada aqui por enquanto
    }
}
