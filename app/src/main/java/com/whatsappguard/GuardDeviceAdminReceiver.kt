package com.whatsappguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class GuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Desativar o AppGuard permitirá a desinstalação do app. Você tem certeza?"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
