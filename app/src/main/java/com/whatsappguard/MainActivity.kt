package com.whatsappguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.whatsappguard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
    }
    private var isAuthenticated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Esconde conteúdo até autenticação
        binding.mainContent.visibility = View.GONE

        if (!PasswordManager.isPasswordSet(this)) {
            showCreatePasswordDialog()
        } else {
            showLoginDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isAuthenticated) {
            updateServiceStatus()
        }
    }

    private fun onAuthSuccess() {
        isAuthenticated = true
        binding.mainContent.visibility = View.VISIBLE
        setupUI()
        setupDurationSelector()
        updateServiceStatus()
        startGuardService()
    }

    private fun startGuardService() {
        GuardForegroundService.start(this)
    }

    private fun setupUI() {
        // Switch - Proteger Mensagens Temporárias
        binding.switchDisappearing.isChecked = prefs.getBoolean("protect_disappearing", true)
        binding.switchDisappearing.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("protect_disappearing", isChecked).apply()
            binding.durationContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateDescriptionText()
            Toast.makeText(
                this,
                if (isChecked) "Proteção de mensagens temporárias ATIVADA"
                else "Proteção de mensagens temporárias DESATIVADA",
                Toast.LENGTH_SHORT
            ).show()
        }
        // Mostrar/esconder duração conforme switch
        binding.durationContainer.visibility =
            if (prefs.getBoolean("protect_disappearing", true)) View.VISIBLE else View.GONE

        // Switch - Bloquear Chamadas
        binding.switchCalls.isChecked = prefs.getBoolean("block_calls", true)
        binding.switchCalls.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("block_calls", isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "Bloqueio de chamadas ATIVADO"
                else "Bloqueio de chamadas DESATIVADO",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Botão - Ativar Serviço de Acessibilidade
        binding.btnAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // Botão - Ativar Acesso a Notificações
        binding.btnNotificationAccess.setOnClickListener {
            openNotificationListenerSettings()
        }

        // Botão - Otimização de Bateria
        binding.btnBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        // Botão - Ativar Device Admin (anti-desinstalação)
        binding.btnDeviceAdmin.setOnClickListener {
            requestDeviceAdmin()
        }

        // Botão - Abrir lista negra de chamadas telefônicas
        binding.btnOpenBlocklist.setOnClickListener {
            startActivity(Intent(this, BlocklistActivity::class.java))
        }
    }

    private fun setupDurationSelector() {
        val savedDuration = prefs.getString("disappearing_duration", "24h") ?: "24h"
        when (savedDuration) {
            "24h" -> binding.chip24h.isChecked = true
            "7d" -> binding.chip7d.isChecked = true
            "90d" -> binding.chip90d.isChecked = true
        }
        updateDescriptionText()

        binding.chipGroupDuration.setOnCheckedStateChangeListener { _, checkedIds ->
            val duration = when {
                checkedIds.contains(R.id.chip24h) -> "24h"
                checkedIds.contains(R.id.chip7d) -> "7d"
                checkedIds.contains(R.id.chip90d) -> "90d"
                else -> "24h"
            }
            prefs.edit().putString("disappearing_duration", duration).apply()
            updateDescriptionText()

            val label = when (duration) {
                "24h" -> "24 horas"
                "7d" -> "7 dias"
                "90d" -> "90 dias"
                else -> "24 horas"
            }
            Toast.makeText(this, "Duração: $label", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDescriptionText() {
        val duration = prefs.getString("disappearing_duration", "24h") ?: "24h"
        val label = when (duration) {
            "24h" -> "24 horas"
            "7d" -> "7 dias"
            "90d" -> "90 dias"
            else -> "24 horas"
        }
        binding.txtDisappearingDesc.text = "Re-ativa automaticamente para $label quando desativarem"
    }

    private fun updateServiceStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val notificationEnabled = isNotificationListenerEnabled()
        val batteryOptimized = isBatteryOptimized()
        val deviceAdminActive = isDeviceAdminActive()

        // Status Acessibilidade (dot)
        binding.statusAccessibility.setBackgroundResource(
            if (accessibilityEnabled) R.drawable.status_dot_green else R.drawable.status_dot_red
        )
        binding.btnAccessibility.text = if (accessibilityEnabled)
            "Acessibilidade — Ativo" else "Serviço de Acessibilidade"

        // Status Notificações (dot)
        binding.statusNotification.setBackgroundResource(
            if (notificationEnabled) R.drawable.status_dot_green else R.drawable.status_dot_red
        )
        binding.btnNotificationAccess.text = if (notificationEnabled)
            "Notificações — Ativo" else "Acesso a Notificações"

        // Status Bateria (dot)
        binding.statusBattery.setBackgroundResource(
            if (!batteryOptimized) R.drawable.status_dot_green else R.drawable.status_dot_yellow
        )
        binding.btnBattery.text = if (!batteryOptimized)
            "Bateria — Sem restrição" else "Otimização de Bateria"

        // Status Device Admin (dot)
        binding.statusDeviceAdmin.setBackgroundResource(
            if (deviceAdminActive) R.drawable.status_dot_green else R.drawable.status_dot_red
        )
        binding.btnDeviceAdmin.text = if (deviceAdminActive)
            "Anti-Desinstalação — Ativo" else "Anti-Desinstalação"

        // Status geral
        binding.statusGeneral.text = when {
            accessibilityEnabled && notificationEnabled && !batteryOptimized && deviceAdminActive ->
                "Todos os serviços ativos"
            accessibilityEnabled && !deviceAdminActive ->
                "Ative o anti-desinstalação para proteção total"
            accessibilityEnabled && batteryOptimized ->
                "Desative a otimização de bateria"
            accessibilityEnabled ->
                "Ative o acesso a notificações"
            else ->
                "Ative os serviços abaixo para o app funcionar"
        }
    }

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, WhatsAppAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val expectedComponent = ComponentName(this, CallBlockerService::class.java).flattenToString()
        return flat.contains(expectedComponent)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(
            this,
            "Encontre 'AppGuard' e ative o serviço",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
        Toast.makeText(
            this,
            "Ative o 'AppGuard' na lista",
            Toast.LENGTH_LONG
        ).show()
    }

    // ========== PASSWORD DIALOGS ==========

    private fun showCreatePasswordDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 0)
        }
        val inputPassword = EditText(this).apply {
            hint = "Digite sua senha"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val inputConfirm = EditText(this).apply {
            hint = "Confirme sua senha"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(inputPassword)
        layout.addView(inputConfirm)

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle("Criar Senha de Proteção")
            .setMessage("Esta senha protege o app contra acesso não autorizado. Armazenada como hash SHA-256.")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Criar") { _, _ ->
                val pw = inputPassword.text.toString()
                val confirm = inputConfirm.text.toString()
                when {
                    pw.length < 4 -> {
                        Toast.makeText(this, "A senha deve ter pelo menos 4 caracteres", Toast.LENGTH_SHORT).show()
                        showCreatePasswordDialog()
                    }
                    pw != confirm -> {
                        Toast.makeText(this, "As senhas não coincidem", Toast.LENGTH_SHORT).show()
                        showCreatePasswordDialog()
                    }
                    else -> {
                        PasswordManager.createPassword(this, pw)
                        Toast.makeText(this, "Senha criada com sucesso", Toast.LENGTH_SHORT).show()
                        onAuthSuccess()
                    }
                }
            }
            .show()
    }

    private fun showLoginDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 0)
        }
        val inputPassword = EditText(this).apply {
            hint = "Digite sua senha"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(inputPassword)

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle("AppGuard")
            .setMessage("Digite a senha para acessar")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Entrar") { _, _ ->
                val pw = inputPassword.text.toString()
                if (PasswordManager.verifyPassword(this, pw)) {
                    onAuthSuccess()
                } else {
                    Toast.makeText(this, "Senha incorreta", Toast.LENGTH_SHORT).show()
                    showLoginDialog()
                }
            }
            .setNegativeButton("Sair") { _, _ ->
                finishAffinity()
            }
            .show()
    }

    // ========== DEVICE ADMIN ==========

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(this, GuardDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    private fun requestDeviceAdmin() {
        val adminComponent = ComponentName(this, GuardDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Ative para impedir a desinstalação do AppGuard sem autorização."
            )
        }
        startActivity(intent)
    }
}
