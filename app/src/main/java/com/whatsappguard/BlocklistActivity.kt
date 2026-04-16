package com.whatsappguard

import android.app.role.RoleManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.whatsappguard.databinding.ActivityBlocklistBinding

class BlocklistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlocklistBinding

    private val requestRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateCallScreeningStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlocklistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupSwitch()
        setupTabs()
        setupFab()
        setupCallScreening()
        setupClearLog()
        refreshBlocklist()
    }

    override fun onResume() {
        super.onResume()
        updateCallScreeningStatus()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupSwitch() {
        binding.switchBlocking.isChecked = BlocklistManager.isBlockingEnabled(this)
        binding.switchBlocking.setOnCheckedChangeListener { _, isChecked ->
            BlocklistManager.setBlockingEnabled(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Bloqueio ativado" else "Bloqueio desativado",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("LISTA NEGRA"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("REGISTRO"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        binding.frameBlocklist.visibility = View.VISIBLE
                        binding.frameLog.visibility = View.GONE
                        binding.fabAdd.visibility = View.VISIBLE
                        refreshBlocklist()
                    }
                    1 -> {
                        binding.frameBlocklist.visibility = View.GONE
                        binding.frameLog.visibility = View.VISIBLE
                        binding.fabAdd.visibility = View.GONE
                        refreshLog()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            showAddNumberDialog()
        }
    }

    private fun setupCallScreening() {
        binding.btnCallScreening.setOnClickListener {
            requestCallScreeningRole()
        }
        updateCallScreeningStatus()
    }

    private fun setupClearLog() {
        binding.btnClearLog.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Limpar registro")
                .setMessage("Deseja apagar todo o histórico de chamadas bloqueadas?")
                .setPositiveButton("Limpar") { _, _ ->
                    BlocklistManager.clearLog(this)
                    refreshLog()
                    Toast.makeText(this, "Registro limpo", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (!roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                requestRoleLauncher.launch(intent)
            } else {
                Toast.makeText(this, "Já está ativo!", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Requer Android 10 ou superior", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCallScreeningStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            val isActive = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            binding.txtCallScreeningStatus.text =
                if (isActive) "Permissão concedida" else "Permissão necessária"
            binding.txtCallScreeningStatus.setTextColor(
                if (isActive) getColor(android.R.color.holo_green_dark)
                else getColor(android.R.color.holo_orange_dark)
            )
            binding.btnCallScreening.visibility = if (isActive) View.GONE else View.VISIBLE
        }
    }

    private fun showAddNumberDialog() {
        val input = EditText(this).apply {
            hint = "+55 42 99999-9999"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(60, 40, 60, 20)
        }

        AlertDialog.Builder(this)
            .setTitle("Adicionar número")
            .setMessage("Digite o número de telefone para bloquear:")
            .setView(input)
            .setPositiveButton("Bloquear") { _, _ ->
                val number = input.text.toString().trim()
                if (number.isNotEmpty()) {
                    BlocklistManager.addNumber(this, number)
                    refreshBlocklist()
                    Toast.makeText(this, "Número adicionado!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun refreshBlocklist() {
        val container = binding.listBlockedNumbers
        container.removeAllViews()
        val numbers = BlocklistManager.getBlockedNumbers(this)

        if (numbers.isEmpty()) {
            binding.emptyBlocklist.visibility = View.VISIBLE
            container.visibility = View.GONE
        } else {
            binding.emptyBlocklist.visibility = View.GONE
            container.visibility = View.VISIBLE
        }

        val density = resources.displayMetrics.density

        for (number in numbers) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * density).toInt(), (16 * density).toInt(),
                    (16 * density).toInt(), (16 * density).toInt()
                )
                setBackgroundColor(0xFF1E1E1E.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (2 * density).toInt() }
            }

            // Avatar circular
            val avatarSize = (44 * density).toInt()
            val avatar = TextView(this).apply {
                text = "+5"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                    marginEnd = (16 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF455A64.toInt())
                }
            }

            val numberText = TextView(this).apply {
                text = number
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val deleteBtn = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundColor(0x00000000)
                setColorFilter(0xFFFF4444.toInt())
                setPadding(
                    (8 * density).toInt(), (8 * density).toInt(),
                    (8 * density).toInt(), (8 * density).toInt()
                )
                setOnClickListener {
                    AlertDialog.Builder(this@BlocklistActivity)
                        .setTitle("Remover")
                        .setMessage("Remover $number da lista negra?")
                        .setPositiveButton("Remover") { _, _ ->
                            BlocklistManager.removeNumber(this@BlocklistActivity, number)
                            refreshBlocklist()
                            Toast.makeText(
                                this@BlocklistActivity,
                                "Número removido",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }

            row.addView(avatar)
            row.addView(numberText)
            row.addView(deleteBtn)
            container.addView(row)
        }
    }

    private fun refreshLog() {
        val container = binding.listCallLog
        container.removeAllViews()
        val log = BlocklistManager.getCallLogFormatted(this)

        if (log.isEmpty()) {
            binding.emptyLog.visibility = View.VISIBLE
            container.visibility = View.GONE
            binding.btnClearLog.visibility = View.GONE
        } else {
            binding.emptyLog.visibility = View.GONE
            container.visibility = View.VISIBLE
            binding.btnClearLog.visibility = View.VISIBLE
        }

        val density = resources.displayMetrics.density

        for ((number, time) in log) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * density).toInt(), (14 * density).toInt(),
                    (16 * density).toInt(), (14 * density).toInt()
                )
                setBackgroundColor(0xFF1E1E1E.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (2 * density).toInt() }
            }

            // Indicador bloqueado
            val dot = View(this).apply {
                val dotSize = (8 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = (14 * density).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                }
                setBackgroundResource(R.drawable.status_dot_red)
            }

            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val numberText = TextView(this).apply {
                text = number
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
            }

            val timeText = TextView(this).apply {
                text = time
                setTextColor(0xFF888888.toInt())
                textSize = 12f
            }

            infoLayout.addView(numberText)
            infoLayout.addView(timeText)
            row.addView(dot)
            row.addView(infoLayout)
            container.addView(row)
        }
    }
}
