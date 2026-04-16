# 🛡️ WhatsApp Guard

App Android para proteção automática de privacidade no WhatsApp.

## Funcionalidades

### 🔒 Proteção de Mensagens Temporárias
- Quando alguém entra na tela de "Mensagens temporárias" e seleciona "Desativadas", o app automaticamente re-seleciona "24 horas"
- Funciona monitorando a interface do WhatsApp via Accessibility Service

### 📵 Bloqueio de Chamadas WhatsApp
- Rejeita automaticamente chamadas de voz e vídeo recebidas
- Funciona via Accessibility Service + Notification Listener

## Como Instalar e Configurar

### 1. Abrir no Android Studio
1. Abra o Android Studio
2. `File > Open` → selecione a pasta `WhatsAppGuard`
3. Aguarde o Gradle sincronizar

### 2. Instalar no celular
1. Conecte o celular via USB com Depuração USB ativada
2. Clique em `Run` (▶️) no Android Studio
3. Selecione seu dispositivo

### 3. Ativar permissões (OBRIGATÓRIO)
Após instalar, você precisa ativar 2 permissões:

#### Serviço de Acessibilidade (Essencial)
1. No app, toque em "Ativar Serviço de Acessibilidade"
2. Nas configurações do Android, encontre "WhatsApp Guard"
3. Ative o serviço
4. Confirme o popup de aviso

#### Acesso a Notificações (Para bloqueio de chamadas reforçado)
1. No app, toque em "Ativar Acesso a Notificações"
2. Nas configurações, ative "WhatsApp Guard"

### 4. Pronto!
O app funciona em segundo plano automaticamente.

## Como funciona tecnicamente

### Accessibility Service
O Android oferece o `AccessibilityService` que permite que apps de acessibilidade monitorem e interajam com a interface de outros apps. O WhatsApp Guard usa isso para:

1. **Detectar a tela de mensagens temporárias** → procura por textos como "Mensagens temporárias", "Desativadas"
2. **Clicar em "24 horas"** → quando detecta que "Desativadas" está selecionada
3. **Detectar tela de chamada** → procura por textos como "Chamada de voz", "Chamada de vídeo"
4. **Clicar em "Recusar"** → rejeita a chamada automaticamente

### NotificationListenerService
Complementa o Accessibility Service detectando notificações de chamada do WhatsApp e usando as ações da notificação para rejeitar.

## Estrutura do Projeto
```
app/src/main/
├── java/com/whatsappguard/
│   ├── MainActivity.kt              # Tela principal com controles
│   ├── WhatsAppAccessibilityService.kt # Monitora e interage com WhatsApp
│   ├── CallBlockerService.kt         # Bloqueia chamadas via notificações
│   └── BootReceiver.kt               # Reinicia serviços após boot
├── res/
│   ├── layout/activity_main.xml      # Layout da tela principal
│   ├── values/                       # Strings, cores, temas
│   └── xml/accessibility_service_config.xml
└── AndroidManifest.xml
```

## Observações Importantes

- ⚠️ O app precisa do **Serviço de Acessibilidade** ativado para funcionar
- ⚠️ Alguns fabricantes (Xiaomi, Samsung, Huawei) podem matar o serviço em background. Vá em Configurações > Bateria > WhatsApp Guard > "Sem restrição"
- ⚠️ O WhatsApp pode mudar a interface em atualizações. Se parar de funcionar, os textos de detecção no código podem precisar de ajuste
- O app NÃO lê suas mensagens — apenas monitora a interface visual do WhatsApp

## Requisitos
- Android 8.0+ (API 26+)
- WhatsApp instalado
- Android Studio para compilar
