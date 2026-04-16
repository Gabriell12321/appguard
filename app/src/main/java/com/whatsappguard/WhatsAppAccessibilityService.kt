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
        // Tela de Privacidade Avançada (per-conversation)
        private val ADVANCED_PRIVACY_SCREEN_INDICATORS = listOf(
            "Privacidade avançada", "Advanced privacy",
            "Privacidade avancada"
        )
        // Toggles de privacidade avançada que devem ficar LIGADOS
        private val ADVANCED_PRIVACY_TOGGLE_TEXTS = listOf(
            // Restringir exportação
            "Restringir exportação de conversa", "Restrict exporting chat",
            "Restringir exportação", "Restrict export",
            // Bloquear download de mídia
            "Bloquear download de mídia", "Block downloading media",
            "Bloquear downloads", "Block downloads",
            // Bloquear uso de IA
            "Bloquear mensagens de IA", "Block AI messages",
            "Bloquear IA", "Block AI"
        )
        // Toggles que NÃO devem ser tocados (trancar/ocultar conversa)
        private val ADVANCED_PRIVACY_EXCLUDE_TEXTS = listOf(
            "Trancar", "Lock", "Ocultar", "Hide",
            "Trancar e ocultar", "Lock and hide",
            "Trancar conversa", "Lock chat",
            "Ocultar conversa", "Hide chat"
        )

        // Textos no chat que indicam desativação de mensagens temporárias por outra pessoa
        private val CHAT_DEACTIVATION_TEXTS = listOf(
            "desativou as mensagens temporárias",
            "desativou as mensagens temporarias",
            "disabled disappearing messages",
            "turned off disappearing messages",
            "desligou as mensagens temporárias",
            "desligou as mensagens temporarias"
        )

        // Textos clicáveis dentro da mensagem do sistema
        private val CHAT_TAP_TO_CHANGE_TEXTS = listOf(
            "Toque para mudar",
            "Tap to change",
            "Toque para alterar"
        )

        // Textos que indicam que mensagens temporárias foram ATIVADAS (para evitar re-clique)
        private val CHAT_ACTIVATION_TEXTS = listOf(
            "Você ativou as mensagens temporárias",
            "Voce ativou as mensagens temporarias",
            "You turned on disappearing messages",
            "You enabled disappearing messages",
            "ativou as mensagens temporárias",
            "ativou as mensagens temporarias",
            "turned on disappearing messages",
            "enabled disappearing messages"
        )

        var isServiceRunning = false
            private set
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    // Cooldowns separados para chamadas, mensagens e privacidade avançada
    private var lastCallActionTime = 0L
    private var lastMsgActionTime = 0L
    private var lastAdvPrivacyActionTime = 0L
    private val CALL_COOLDOWN = 2000L
    private val MSG_COOLDOWN = 1500L
    private val ADV_PRIVACY_COOLDOWN = 2000L

    // Retry: quando detecta "Desativada", tenta múltiplas vezes
    private var retryCount = 0
    private val MAX_RETRIES = 5
    private var pendingRetryRunnable: Runnable? = null

    // Cooldown para detecção de desativação no chat (evita re-clique em mensagens antigas)
    private var lastChatDeactivationTime = 0L
    private val CHAT_DEACTIVATION_COOLDOWN = 10000L

    // Estado de navegação: rastreia se acabamos de clicar na mensagem e estamos esperando
    // a tela de configurações abrir para agir automaticamente
    private var waitingForSettingsScreen = false
    private var waitingForSettingsTimestamp = 0L
    private val WAITING_FOR_SETTINGS_TIMEOUT = 8000L

    // Re-scan: após navegar, agenda verificações adicionais
    private var pendingRescanRunnable: Runnable? = null

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
        pendingRescanRunnable?.let { handler.removeCallbacks(it) }
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

                // Se estamos esperando a tela de configurações abrir após navegar do chat,
                // verificar com prioridade máxima se já abriu
                if (waitingForSettingsScreen) {
                    if (now - waitingForSettingsTimestamp > WAITING_FOR_SETTINGS_TIMEOUT) {
                        waitingForSettingsScreen = false
                        Log.w(TAG, "⏰ Timeout esperando tela de configurações, desistindo")
                    } else if (prefs.getBoolean("protect_disappearing", true)) {
                        // Tenta agir na tela de configurações imediatamente
                        handleDisappearingMessages(rootNode)
                        handleDefaultTimerScreen(rootNode)
                    }
                }

                // Detectar desativação de mensagens temporárias no chat
                // (clica automaticamente na mensagem "Toque para mudar")
                if (prefs.getBoolean("protect_disappearing", true)) {
                    handleDeactivationInChat(rootNode)
                }

                // Verificar privacidade avançada
                if (prefs.getBoolean("protect_advanced_privacy", true) &&
                    now - lastAdvPrivacyActionTime >= ADV_PRIVACY_COOLDOWN
                ) {
                    handleAdvancedPrivacy(rootNode)
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

        // Se estávamos esperando essa tela após navegação do chat, marcar como encontrada
        if (waitingForSettingsScreen) {
            waitingForSettingsScreen = false
            Log.i(TAG, "✅ Tela de configurações encontrada após navegação automática!")
        }

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
    // PRIVACIDADE AVANÇADA — POR CONVERSA
    // Detecta a tela "Privacidade avançada" e liga os toggles desligados
    // =========================================================================

    private fun handleAdvancedPrivacy(rootNode: AccessibilityNodeInfo) {
        val isAdvancedPrivacyScreen = ADVANCED_PRIVACY_SCREEN_INDICATORS.any { indicator ->
            findNodesByText(rootNode, indicator).isNotEmpty()
        }

        if (!isAdvancedPrivacyScreen) return

        Log.i(TAG, "Tela de Privacidade Avançada detectada!")

        var activated = false

        // Procurar switches/toggles que estão desligados
        val allSwitches = collectNodesRecursive(rootNode) { node ->
            val className = node.className?.toString() ?: ""
            className.contains("Switch") || className.contains("ToggleButton") || className.contains("CompoundButton")
        }

        for (switchNode in allSwitches) {
            if (!switchNode.isChecked) {
                // Verificar se esse switch pertence a uma das opções de privacidade
                val isPrivacyToggle = isRelatedToAdvancedPrivacy(switchNode, rootNode)
                if (isPrivacyToggle) {
                    if (clickNode(switchNode)) {
                        Log.i(TAG, "Toggle de privacidade avançada ATIVADO: ${switchNode.text ?: switchNode.contentDescription ?: "switch"}")
                        activated = true
                    }
                }
            }
        }

        // Fallback: procurar por texto das opções e clicar nos containers
        if (!activated) {
            for (toggleText in ADVANCED_PRIVACY_TOGGLE_TEXTS) {
                val nodes = findNodesByText(rootNode, toggleText)
                for (node in nodes) {
                    // Verificar se há um switch irmão/filho desligado
                    val parent = node.parent ?: continue
                    val switchInParent = findSwitchInContainer(parent)
                    if (switchInParent != null && !switchInParent.isChecked) {
                        if (clickNode(switchInParent)) {
                            Log.i(TAG, "Toggle de privacidade avançada ATIVADO via texto: $toggleText")
                            activated = true
                        }
                    }
                    // Tentar clicar no container inteiro (o WhatsApp alterna o switch)
                    if (!activated) {
                        val clickTarget = findClickableParent(parent) ?: parent
                        if (clickTarget.isClickable) {
                            clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.i(TAG, "Toggle de privacidade avançada ATIVADO via container: $toggleText")
                            activated = true
                        }
                    }
                    parent.recycle()
                }
            }
        }

        if (activated) {
            lastAdvPrivacyActionTime = System.currentTimeMillis()
        }
    }

    private fun isRelatedToAdvancedPrivacy(switchNode: AccessibilityNodeInfo, rootNode: AccessibilityNodeInfo): Boolean {
        // Primeiro: verificar se é um toggle EXCLUÍDO (trancar/ocultar)
        val desc = switchNode.contentDescription?.toString() ?: ""
        val txt = switchNode.text?.toString() ?: ""

        if (ADVANCED_PRIVACY_EXCLUDE_TEXTS.any { desc.contains(it, ignoreCase = true) }) {
            Log.i(TAG, "Toggle excluído (desc): $desc")
            return false
        }
        if (ADVANCED_PRIVACY_EXCLUDE_TEXTS.any { txt.contains(it, ignoreCase = true) }) {
            Log.i(TAG, "Toggle excluído (txt): $txt")
            return false
        }

        // Verificar se o pai/container tem texto de exclusão
        if (hasExcludedTextInParent(switchNode)) {
            return false
        }

        // Verificar se o switch tem contentDescription relacionada ao que queremos
        if (ADVANCED_PRIVACY_TOGGLE_TEXTS.any { desc.contains(it, ignoreCase = true) }) return true
        if (ADVANCED_PRIVACY_TOGGLE_TEXTS.any { txt.contains(it, ignoreCase = true) }) return true

        // Verificar se o pai/container tem texto relacionado ao que queremos
        var parent = switchNode.parent
        var depth = 0
        while (parent != null && depth < 3) {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                val siblingText = sibling.text?.toString() ?: ""
                // Se o irmão tem texto excluído, não ativar esse switch
                if (ADVANCED_PRIVACY_EXCLUDE_TEXTS.any { siblingText.contains(it, ignoreCase = true) }) {
                    Log.i(TAG, "Toggle excluído (sibling): $siblingText")
                    sibling.recycle()
                    parent.recycle()
                    return false
                }
                if (ADVANCED_PRIVACY_TOGGLE_TEXTS.any { siblingText.contains(it, ignoreCase = true) }) {
                    sibling.recycle()
                    parent.recycle()
                    return true
                }
                sibling.recycle()
            }
            val grandParent = parent.parent
            parent.recycle()
            parent = grandParent
            depth++
        }

        // Não conseguiu confirmar — NÃO clicar por segurança
        Log.i(TAG, "Toggle não identificado, ignorando por segurança")
        return false
    }

    private fun hasExcludedTextInParent(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                val childText = child.text?.toString() ?: ""
                val childDesc = child.contentDescription?.toString() ?: ""
                if (ADVANCED_PRIVACY_EXCLUDE_TEXTS.any { childText.contains(it, ignoreCase = true) || childDesc.contains(it, ignoreCase = true) }) {
                    child.recycle()
                    parent.recycle()
                    return true
                }
                child.recycle()
            }
            val grandParent = parent.parent
            parent.recycle()
            parent = grandParent
            depth++
        }
        return false
    }

    private fun findSwitchInContainer(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return searchNodeRecursive(container) { node ->
            val className = node.className?.toString() ?: ""
            className.contains("Switch") || className.contains("ToggleButton") || className.contains("CompoundButton")
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable) return current
            val parent = current.parent
            current.recycle()
            current = parent
            depth++
        }
        return null
    }

    // =========================================================================
    // DETECÇÃO DE DESATIVAÇÃO NO CHAT — NAVEGA AUTOMATICAMENTE PARA CONFIGURAÇÕES
    // Quando alguém desativa mensagens temporárias, a mensagem no chat contém
    // "Toque para mudar". Este método detecta e clica automaticamente.
    // 
    // Estratégias (em ordem):
    // 0. Busca direta por "Toque para mudar" (link clicável)
    // 1. Clique direto no nó da mensagem de desativação
    // 2. Clique via hierarquia de parents clicáveis
    // 3. Gesture tap em múltiplas posições na mensagem
    // 4. Busca recursiva por nós clicáveis dentro do container da mensagem
    // =========================================================================

    private fun handleDeactivationInChat(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastChatDeactivationTime < CHAT_DEACTIVATION_COOLDOWN) return

        // Procurar mensagens de desativação no chat
        val deactivationNodes = mutableListOf<AccessibilityNodeInfo>()
        for (text in CHAT_DEACTIVATION_TEXTS) {
            deactivationNodes.addAll(findNodesByText(rootNode, text))
        }
        if (deactivationNodes.isEmpty()) return

        // Verificar se já houve reativação mais recente (evitar loop)
        if (hasRecentActivation(rootNode, deactivationNodes)) return

        Log.i(TAG, "\uD83D\uDD0D Desativação de mensagens temporárias detectada no chat! (${deactivationNodes.size} mensagens)")

        // Usar o último nó de desativação (mais recente, geralmente mais abaixo na tela)
        val targetNode = deactivationNodes.maxByOrNull { node ->
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            bounds.bottom
        } ?: return

        // === ESTRATÉGIA 0: Buscar diretamente "Toque para mudar" ===
        for (tapText in CHAT_TAP_TO_CHANGE_TEXTS) {
            val tapNodes = findNodesByText(rootNode, tapText)
            for (tapNode in tapNodes) {
                if (tapNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    onChatNavigationSuccess(now, "texto '$tapText' clicável")
                    return
                }
                if (clickNode(tapNode)) {
                    onChatNavigationSuccess(now, "parent de '$tapText'")
                    return
                }
            }
        }

        // === ESTRATÉGIA 1: Clique direto no nó de desativação ===
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            onChatNavigationSuccess(now, "clique direto na mensagem")
            return
        }

        // === ESTRATÉGIA 2: Buscar nós clicáveis dentro do container ===
        val container = findMessageContainer(targetNode)
        if (container != null) {
            val clickables = collectNodesRecursive(container) { it.isClickable }
            for (clickable in clickables) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                onChatNavigationSuccess(now, "nó clicável dentro do container")
                return
            }
        }

        // === ESTRATÉGIA 3: Clicar via hierarquia de pais clicáveis ===
        if (clickNode(targetNode)) {
            onChatNavigationSuccess(now, "parent clicável")
            return
        }

        // === ESTRATÉGIA 4: Gesture taps em múltiplas posições ===
        val bounds = android.graphics.Rect()
        targetNode.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            // Tentar vários pontos: "Toque para mudar" pode estar em posições diferentes
            val tapPositions = listOf(
                // Parte inferior da mensagem (onde geralmente fica "Toque para mudar")
                Pair(bounds.centerX().toFloat(), bounds.bottom - bounds.height() * 0.1f),
                // Centro da mensagem
                Pair(bounds.centerX().toFloat(), bounds.centerY().toFloat()),
                // Parte mais abaixo (quase no final)
                Pair(bounds.centerX().toFloat(), bounds.bottom - 10f),
                // Levemente à esquerda (o link pode estar alinhado à esquerda)
                Pair(bounds.left + bounds.width() * 0.3f, bounds.bottom - bounds.height() * 0.15f)
            )
            for ((tapX, tapY) in tapPositions) {
                if (tapX > 0 && tapY > 0 && performTapGesture(tapX, tapY)) {
                    onChatNavigationSuccess(now, "gesture tap (${tapX.toInt()}, ${tapY.toInt()})")
                    return
                }
            }
        }

        // === ESTRATÉGIA 5: Long click para ver se abre menu de contexto ===
        targetNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        Log.w(TAG, "\u26A0\uFE0F Tentou long-click como último recurso")

        lastChatDeactivationTime = now
    }

    /**
     * Verifica se mensagens de ativação mais recentes existem (evita loop).
     * Compara posição Y na tela: mensagem mais abaixo = mais recente.
     */
    private fun hasRecentActivation(
        rootNode: AccessibilityNodeInfo,
        deactivationNodes: List<AccessibilityNodeInfo>
    ): Boolean {
        val activationNodes = mutableListOf<AccessibilityNodeInfo>()
        for (text in CHAT_ACTIVATION_TEXTS) {
            activationNodes.addAll(findNodesByText(rootNode, text))
        }
        if (activationNodes.isEmpty()) return false

        val lastDeactivationBottom = deactivationNodes.maxOf { node ->
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            bounds.bottom
        }
        val lastActivationBottom = activationNodes.maxOf { node ->
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            bounds.bottom
        }
        if (lastActivationBottom >= lastDeactivationBottom) {
            return true
        }
        return false
    }

    /**
     * Chamado quando a navegação do chat para configurações teve sucesso.
     * Ativa o estado de espera e agenda re-scans para confirmar.
     */
    private fun onChatNavigationSuccess(timestamp: Long, strategy: String) {
        lastChatDeactivationTime = timestamp
        lastMsgActionTime = timestamp
        waitingForSettingsScreen = true
        waitingForSettingsTimestamp = timestamp
        Log.i(TAG, "\u2705 Navegando para configurações via $strategy")

        // Agendar re-scans para agir quando a tela de configurações abrir
        scheduleRescan(500)
        scheduleRescan(1500)
        scheduleRescan(3000)
        scheduleRescan(5000)
    }

    /**
     * Agenda um re-scan da tela para detectar e agir na tela de configurações.
     */
    private fun scheduleRescan(delayMs: Long) {
        val runnable = Runnable {
            if (!waitingForSettingsScreen) return@Runnable
            val root = rootInActiveWindow ?: return@Runnable
            if (prefs.getBoolean("protect_disappearing", true)) {
                handleDisappearingMessages(root)
                handleDefaultTimerScreen(root)
                // Se conseguiu agir (a tela de configurações abriu), para de esperar
                val isSettingsScreen = TEMP_MSG_SCREEN_INDICATORS.any { text ->
                    findNodesByText(root, text).isNotEmpty()
                }
                if (isSettingsScreen) {
                    waitingForSettingsScreen = false
                    Log.i(TAG, "\u2705 Tela de configurações detectada após navegação do chat!")
                }
            }
            root.recycle()
        }
        handler.postDelayed(runnable, delayMs)
    }

    /**
     * Encontra o container de mensagem mais próximo (sobe na hierarquia até um ViewGroup de tamanho razoável).
     */
    private fun findMessageContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            val bounds = android.graphics.Rect()
            current.getBoundsInScreen(bounds)
            // Container de mensagem geralmente tem largura > 200px e é um ViewGroup
            if (bounds.width() > 200 && current.childCount > 0) {
                return current
            }
            val parent = current.parent
            current.recycle()
            current = parent
            depth++
        }
        return null
    }

    private fun performTapGesture(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
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
                    // Múltiplas confirmações com intervalos variados
                    scheduleConfirmation(300)
                    scheduleConfirmation(800)
                    scheduleConfirmation(1500)
                    scheduleConfirmation(2500)
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
                scheduleConfirmation(300)
                scheduleConfirmation(800)
                scheduleConfirmation(1500)
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
                    scheduleConfirmation(300)
                    scheduleConfirmation(800)
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

    private fun scheduleConfirmation(delayMs: Long) {
        handler.postDelayed({ confirmIfNeeded() }, delayMs)
    }

    private fun confirmIfNeeded() {
        val rootNode = rootInActiveWindow ?: return
        val confirmTexts = listOf(
            "OK", "Salvar", "Confirmar", "Save", "Done",
            "SALVAR", "CONFIRMAR", "DONE", "Ok", "Pronto"
        )
        for (text in confirmTexts) {
            val nodes = findNodesByText(rootNode, text)
            for (node in nodes) {
                if (node.isClickable || hasClickableParent(node)) {
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
