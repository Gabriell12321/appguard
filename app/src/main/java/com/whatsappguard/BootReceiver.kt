package com.whatsappguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver para reiniciar os serviços após boot do dispositivo.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("WhatsAppGuard", "Boot completo - iniciando foreground service")
            GuardForegroundService.start(context)
            // O NotificationListenerService também é reiniciado automaticamente
        }
    }
}
