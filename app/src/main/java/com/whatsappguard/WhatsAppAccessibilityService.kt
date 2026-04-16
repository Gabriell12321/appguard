package com.whatsappguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager

/**
 * Serviço de Acessibilidade que monitora o WhatsApp para:
 * 1. Re-ativar mensagens temporárias quando desativadas (por conversa E duração padrão)
 * 2. Rejeitar chamadas recebidas automaticamente
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAppGuard"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"

        // Textos que indicam que mensagens temporárias estão OFF
        private val DISAPPEARING_OFF_TEXTS = listOf(
            "Desativadas", "Desligadas", "Off", "Desativado", "Desativada"
        )
        // Mapeamento duração -> textos no WhatsApp
        private val DURATION_TEXTS = mapOf(
            "24h" to listOf("24 horas", "24 hours"),
            "7d" to listOf("7 dias", "7 days"),
            "90d" to listOf("90 dias", "90 days")
        )
        // Textos da tela de chamada recebida
        private val CALL_SCREEN_INDICATORS = listOf(
            "Chamada de voz do WhatsApp",
            "Chamada de vídeo do WhatsApp",
            "WhatsApp voice call",
            "WhatsApp video call"
        )
        private val DECLINE_BUTTON_TEXTS = listOf(
            "Recusar", "Decline", "Rejeitar"
        )
        private val DECLINE_CONTENT_DESCRIPTIONS = listOf(
            "Recusar chamada", "Decline call", "Rejeitar chamada",
            "Recusar", "Decline"
        )
        // Tela de mensagens temporárias POR CONVERSA
        private val TEMP_MSG_SCREEN_INDICATORS = listOf(
            "Mensagens temporárias", "Disappearing messages",
            "Mensagens temporarias"
        )
        // Tela de DURAÇÃO PADRÃO (configuração global que afeta novas conversas)
        private val DEFAULT_TIMER_SCREEN_INDICATORS = listOf(
            "Duração padrão", "Default message timer",
            "Default timer", "Duracao padrao",
            "Duração padrão das mensagens"
        )
        // Descrição na tela de duração padrão
        private val DEFAULT_TIMER_CONTEXT_TEXTS = listOf(
            "Inicie novas conversas com a duração",
            "Start new conversations with",
            "novas conversas individuais desaparecerão",
            "new individual chats will disappear"
        )

        var isServiceRunning = false
            private set
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    // Cooldowns separados para chamadas e mensagens
    private var lastCallActionTime = 0L
    private var lastMsgActionTime = 0L
    private val CALL_COOLDOWN = 2000L
    private val MSG_COOLDOWN = 1500L

    // Retry: quando detecta "Desativada", tenta múltiplas vezes
    private var retryCount = 0
    private val MAX_RETRIES = 5
    private var pendingRetryRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        isServiceRunning = true
        Log.i(TAG, "WhatsAppGuard Accessibility Service criado")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        pendingRetryRunnable?.let { handler.removeCallbacks(it) }
        Log.i(TAG, "WhatsAppGuard Accessibility Service destruído")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, "WhatsAppGuard Accessibility Service conectado")
        GuardForegroundService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName != WHATSAPP_PACKAGE) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val rootNode = rootInActiveWindow ?: return

                // Verificar chamadas
                if (prefs.getBoolean("block_calls", true) &&
                    now - lastCallActionTime >= CALL_COOLDOWN
                ) {
                    handleIncomingCall(rootNode)
                }

                // Verificar mensagens temporárias (por conversa + duração padrão)
                if (prefs.getBoolean("protect_disappearing", true) &&
                    now - lastMsgActionTime >= MSG_COOLDOWN
                ) {
                    handleDisappearingMessages(rootNode)
                    handleDefaultTimerScreen(rootNode)
                }

                rootNode.recycle()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "WhatsAppGuard Accessibility Service interrompido")
    }

    // =========================================================================
    // BLOQUEIO DE CHAMADAS
    // =========================================================================

    private fun handleIncomingCall(rootNode: AccessibilityNodeInfo) {
        var declineFound = false

        for (text in DECLINE_BUTTON_TEXTS) {
            val nodes = findNodesByText(rootNode, text)
            for (node in nodes) {
                if (node.isClickable || hasClickableParent(node)) {
                    declineFound = true
                    if (clickNode(node)) {
                        lastCallActionTime = System.currentTimeMillis()
                        Log.i(TAG, "✅ Chamada rejeitada via botão: $text")
                        return
                    }
                }
            }
        }

        val declineNode = findNodeByContentDescription(rootNode, DECLINE_CONTENT_DESCRIPTIONS)
        if (declineNode != null) {
            declineFound = true
            if (clickNode(declineNode)) {
                lastCallActionTime = System.currentTimeMillis()
                Log.i(TAG, "✅ Chamada rejeitada via content description")
                return
            }
        }

        if (!declineFound) {
            val isCallScreen = CALL_SCREEN_INDICATORS.any { indicator ->
                findNodesByText(rootNode, indicator).isNotEmpty()
            }
            if (isCallScreen) {
                Log.i(TAG, "📞 Tela de chamada detectada mas sem botão de recusar encontrado")
            }
        }
    }

    private fun hasClickableParent(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable) {
                current.recycle()
                return true
            }
            val parent = current.parent
            current.recycle()
            current = parent
            depth++
        }
        return false
    }

    // =========================================================================
    // PROTEÇÃO DE MENSAGENS TEMPORÁRIAS — POR CONVERSA
    // =========================================================================

    private fun handleDisappearingMessages(rootNode: AccessibilityNodeInfo) {
        val isDisappearingScreen = TEMP_MSG_SCREEN_INDICATORS.any { indicator ->
            findNodesByText(rootNode, indicator).isNotEmpty()
        }

        if (!isDisappearingScreen) return

        val isDisabled = checkIfDisappearingIsOff(rootNode)

        if (isDisabled) {
            Log.i(TAG, "🔒 Mensagens temporárias desativadas detectado! Re-ativando... (tentativa ${retryCount + 1})")
            val activated = activateDisappearingMessages(rootNode)
            if (!activated && retryCount < MAX_RETRIES) {
                scheduleRetry()
            } else {
                retryCount = 0
            }
        } else {
            // Tela aberta mas não está desativada — reset retry
            retryCount = 0
        }
    }

    // =========================================================================
    // PROTEÇÃO DE MENSAGENS TEMPORÁRIAS — DURAÇÃO PADRÃO (GLOBAL)
    // Essa tela aparece em: WhatsApp > Configurações > Privacidade > Duração padrão
    // Se "Desativada" estiver marcada, conversas novas NÃO terão mensagens temporárias
    // =========================================================================

    private fun handleDefaultTimerScreen(rootNode: AccessibilityNodeInfo) {
        // Verificar se estamos na tela "Duração padrão"
        val isDefaultTimerScreen = DEFAULT_TIMER_SCREEN_INDICATORS.any { indicator ->
            findNodesByText(rootNode, indicator).isNotEmpty()
        }

        if (!isDefaultTimerScreen) return

        // Confirmação extra: procurar texto contextual da tela
        val hasContextText = DEFAULT_TIMER_CONTEXT_TEXTS.any { text ->
            findNodesByText(rootNode, text).isNotEmpty()
        }

        if (!hasContextText) return

        Log.i(TAG, "📋 Tela de Duração Padrão detectada!")

        val isDisabled = checkIfDisappearingIsOff(rootNode)

        if (isDisabled) {
            Log.i(TAG, "🔒 Duração padrão está DESATIVADA! Re-ativando...")
            val activated = activateDisappearingMessages(rootNode)
            if (!activated && retryCount < MAX_RETRIES) {
                scheduleRetry()
            } else {
                retryCount = 0
            }
        }
    }

    // =========================================================================
    // LÓGICA COMPARTILHADA DE DETECÇÃO E ATIVAÇÃO
    // =========================================================================

    private fun checkIfDisappearingIsOff(rootNode: AccessibilityNodeInfo): Boolean {
        // Método 1: procurar texto "Desativada(s)" que esteja checked
        for (text in DISAPPEARING_OFF_TEXTS) {
            val nodes = findNodesByText(rootNode, text)
            for (node in nodes) {
                if (node.isChecked) return true

                // Verificar se o item está selecionado (selected, não apenas checked)
                if (node.isSelected) return true

                val parent = node.parent
                if (parent != null) {
                    if (parent.isChecked || parent.isSelected) {
                        parent.recycle()
                        return true
                    }
                    // Verificar irmãos radio buttons
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChild(i) ?: continue
                        val className = child.className?.toString() ?: ""
                        if ((className.contains("RadioButton") || className.contains("CheckedTextView"))
                            && child.isChecked
                        ) {
                            val childText = child.text?.toString() ?: ""
                            if (DISAPPEARING_OFF_TEXTS.any { childText.contains(it, ignoreCase = true) }) {
                                child.recycle()
                                parent.recycle()
                                return true
                            }
                        }
                        child.recycle()
                    }
                    parent.recycle()
                }
            }
        }

        // Método 2: buscar todos os radio buttons/checked text views
        val checkedRadios = findCheckedRadioButtons(rootNode)
        for (radio in checkedRadios) {
            val radioText = radio.text?.toString() ?: ""
            if (DISAPPEARING_OFF_TEXTS.any { radioText.contains(it, ignoreCase = true) }) {
                return true
            }
            // Verificar content description do radio
            val desc = radio.contentDescription?.toString() ?: ""
            if (DISAPPEARING_OFF_TEXTS.any { desc.contains(it, ignoreCase = true) }) {
                return true
            }
        }

        // Método 3: procurar nós com checked=true dentro de containers que contém texto "Desativada"
        val allNodes = collectNodesRecursive(rootNode) { node ->
            val txt = node.text?.toString() ?: ""
            DISAPPEARING_OFF_TEXTS.any { txt.contains(it, ignoreCase = true) }
        }
        for (node in allNodes) {
            // Verificar se esse nó ou algum ancestral está "selecionado" visualmente
            if (isVisuallySelected(node)) return true
        }

        return false
    }

    private fun isVisuallySelected(node: AccessibilityNodeInfo): Boolean {
        if (node.isChecked || node.isSelected) return true
        var current = node.parent
        var depth = 0
        while (current != null && depth < 3) {
            if (current.isChecked || current.isSelected) {
                current.recycle()
                return true
            }
            // Verificar se o container tem algum filho checked (como um radio button irmão)
            for (i in 0 until current.childCount) {
                val sibling = current.getChild(i) ?: continue
                val cls = sibling.className?.toString() ?: ""
                if ((cls.contains("RadioButton") || cls.contains("CompoundButton") || cls.contains("CheckedTextView"))
                    && sibling.isChecked
                ) {
                    sibling.recycle()
                    current.recycle()
                    return true
                }
                sibling.recycle()
            }
            val parent = current.parent
            current.recycle()
            current = parent
            depth++
        }
        return false
    }

    private fun activateDisappearingMessages(rootNode: AccessibilityNodeInfo): Boolean {
        val selectedDuration = prefs.getString("disappearing_duration", "24h") ?: "24h"
        val targetTexts = DURATION_TEXTS[selectedDuration] ?: DURATION_TEXTS["24h"]!!

        // Tentar clicar na opção configurada
        for (text in targetTexts) {
            val nodes = findNodesByText(rootNode, text)
            for (node in nodes) {
                if (clickNode(node)) {
                    lastMsgActionTime = System.currentTimeMillis()
                    Log.i(TAG, "✅ Mensagens temporárias re-ativadas para: $text")
                    handler.postDelayed({ confirmIfNeeded() }, 500)
                    handler.postDelayed({ confirmIfNeeded() }, 1200)
                    return true
                }
            }
        }

        // Fallback: clicar em qualquer radio que não seja "Desativada"
        val allClickable = collectNodesRecursive(rootNode) { node ->
            val txt = node.text?.toString() ?: ""
            val isOff = DISAPPEARING_OFF_TEXTS.any { txt.contains(it, ignoreCase = true) }
            !isOff && txt.isNotEmpty() && DURATION_TEXTS.values.flatten().any {
                txt.contains(it, ignoreCase = true)
            }
        }
        for (node in allClickable) {
            if (clickNode(node)) {
                lastMsgActionTime = System.currentTimeMillis()
                Log.i(TAG, "✅ Mensagens temporárias re-ativadas para: ${node.text} (fallback texto)")
                handler.postDelayed({ confirmIfNeeded() }, 500)
                return true
            }
        }

        // Fallback 2: qualquer radio button não-checked
        val allRadios = findAllRadioButtons(rootNode)
        for (radio in allRadios) {
            val radioText = radio.text?.toString() ?: ""
            val isOffOption = DISAPPEARING_OFF_TEXTS.any { radioText.contains(it, ignoreCase = true) }
            if (!isOffOption && !radio.isChecked && radioText.isNotEmpty()) {
                if (clickNode(radio)) {
                    lastMsgActionTime = System.currentTimeMillis()
                    Log.i(TAG, "✅ Mensagens temporárias re-ativadas para: $radioText (fallback radio)")
                    handler.postDelayed({ confirmIfNeeded() }, 500)
                    return true
                }
            }
        }

        Log.w(TAG, "⚠️ Não conseguiu clicar em nenhuma opção de duração")
        return false
    }

    private fun scheduleRetry() {
        pendingRetryRunnable?.let { handler.removeCallbacks(it) }
        retryCount++
        val delay = 800L * retryCount // 800ms, 1600ms, 2400ms...
        Log.i(TAG, "🔄 Agendando retry #$retryCount em ${delay}ms")
        val runnable = Runnable {
            val root = rootInActiveWindow ?: return@Runnable
            if (prefs.getBoolean("protect_disappearing", true)) {
                handleDisappearingMessages(root)
                handleDefaultTimerScreen(root)
            }
            root.recycle()
        }
        pendingRetryRunnable = runnable
        handler.postDelayed(runnable, delay)
    }

    private fun confirmIfNeeded() {
        val rootNode = rootInActiveWindow ?: return
        val confirmTexts = listOf("OK", "Salvar", "Confirmar", "Save", "Done", "SALVAR", "CONFIRMAR")
        for (text in confirmTexts) {
            val nodes = findNodesByText(rootNode, text)
            for (node in nodes) {
                if (node.isClickable) {
                    clickNode(node)
                    Log.i(TAG, "✅ Confirmação clicada: $text")
                    rootNode.recycle()
                    return
                }
            }
        }
        rootNode.recycle()
    }

    // =========================================================================
    // UTILITÁRIOS DE BUSCA NA ÁRVORE DE ACESSIBILIDADE
    // =========================================================================

    private fun findNodesByText(
        rootNode: AccessibilityNodeInfo,
        text: String
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        if (nodes != null) {
            results.addAll(nodes)
        }
        return results
    }

    private fun findNodeByContentDescription(
        rootNode: AccessibilityNodeInfo,
        descriptions: List<String>
    ): AccessibilityNodeInfo? {
        return searchNodeRecursive(rootNode) { node ->
            val desc = node.contentDescription?.toString() ?: ""
            descriptions.any { desc.contains(it, ignoreCase = true) }
        }
    }

    private fun findCheckedRadioButtons(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        collectNodesRecursive(rootNode) { node ->
            val className = node.className?.toString() ?: ""
            (className.contains("RadioButton") || className.contains("CheckedTextView")) && node.isChecked
        }.let { results.addAll(it) }
        return results
    }

    private fun findAllRadioButtons(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        return collectNodesRecursive(rootNode) { node ->
            val className = node.className?.toString() ?: ""
            className.contains("RadioButton") || className.contains("CheckedTextView")
        }
    }

    private fun searchNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = searchNodeRecursive(child, predicate)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun collectNodesRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        if (predicate(node)) results.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            results.addAll(collectNodesRecursive(child, predicate))
        }
        return results
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // Tentar clicar diretamente
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // Se não é clicável, tentar o pai
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current.recycle()
                return result
            }
            val parent = current.parent
            current.recycle()
            current = parent
            depth++
        }

        return false
    }
}
