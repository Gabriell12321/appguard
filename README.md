# AppGuard

App Android para proteção automática de privacidade no WhatsApp e bloqueio de chamadas telefônicas.

## Funcionalidades

### Proteção de Mensagens Temporárias
- Monitora a interface do WhatsApp via Accessibility Service
- Quando alguém desativa as mensagens temporárias em uma conversa, o app re-ativa automaticamente
- Monitora também a tela "Duração padrão" em Configurações > Privacidade
- Durações configuráveis: 24 horas, 7 dias ou 90 dias
- Mecanismo de retry (até 5 tentativas) para garantir ativação

### Privacidade Avançada
- Detecta a tela "Privacidade avançada" nas conversas do WhatsApp
- Ativa automaticamente os toggles de proteção:
  - Restringir exportação de conversa
  - Bloquear download de mídia
  - Bloquear mensagens de IA
- Exclui automaticamente o toggle "Trancar e ocultar conversa" para evitar ações indesejadas

### Bloqueio de Chamadas WhatsApp
- Rejeita automaticamente chamadas de voz e vídeo recebidas no WhatsApp
- Funciona via Accessibility Service + Notification Listener

### Bloqueio de Chamadas Telefônicas
- Lista negra de números telefônicos (substitui apps como "Calls Blacklist")
- Call Screening Service nativo do Android
- Normalização de números (últimos 9 dígitos)
- Registro de chamadas bloqueadas com histórico (até 200 entradas)
- Interface com abas: Lista Negra + Registro

### Segurança
- Senha de proteção com hash SHA-256 + salt aleatório de 32 bytes
- Anti-desinstalação via Device Admin
- Serviço em primeiro plano persistente com notificação
- Reinício automático após boot do dispositivo
- Bypass de otimização de bateria

## Instalação

### Via Android Studio
1. Clone o repositório:
   ```
   git clone https://github.com/Gabriell12321/appguard.git
   ```
2. Abra no Android Studio: `File > Open` > selecione a pasta `WhatsAppGuard`
3. Aguarde o Gradle sincronizar
4. Conecte o celular via USB com Depuração USB ativada
5. Clique em `Run`

### Via ADB (linha de comando)
```
gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Configuração (obrigatório)

Após instalar, ative as permissões pelo app:

1. **Serviço de Acessibilidade** — Essencial para todas as funcionalidades WhatsApp
2. **Acesso a Notificações** — Reforça o bloqueio de chamadas
3. **Otimização de Bateria** — Desative para que o serviço não seja morto
4. **Anti-Desinstalação** — Ativa Device Admin para impedir remoção do app

Na primeira execução, o app pedirá para criar uma senha de acesso.

## Estrutura do Projeto

```
app/src/main/
├── java/com/whatsappguard/
│   ├── MainActivity.kt                  # Tela principal com controles e autenticação
│   ├── WhatsAppAccessibilityService.kt  # Monitora e interage com WhatsApp
│   ├── CallBlockerService.kt            # Bloqueia chamadas via notificações
│   ├── GuardForegroundService.kt        # Serviço em primeiro plano persistente
│   ├── PhoneCallScreeningService.kt     # Bloqueio de chamadas telefônicas
│   ├── BlocklistActivity.kt             # Gerenciamento da lista negra
│   ├── BlocklistManager.kt              # Armazenamento JSON de números bloqueados
│   ├── PasswordManager.kt               # Hash SHA-256 com salt
│   ├── GuardDeviceAdminReceiver.kt      # Device Admin anti-desinstalação
│   └── BootReceiver.kt                  # Reinicia serviços após boot
├── res/
│   ├── layout/
│   │   ├── activity_main.xml            # Tela principal (tema escuro)
│   │   └── activity_blocklist.xml       # Gerenciamento de lista negra
│   ├── drawable/                        # Status dots, ícones
│   ├── color/                           # Seletores de cor (chips)
│   ├── values/                          # Strings, cores, temas
│   └── xml/
│       ├── accessibility_service_config.xml
│       └── device_admin_policies.xml
└── AndroidManifest.xml
```

## Como funciona

### Accessibility Service
Monitora eventos da interface do WhatsApp (`typeWindowStateChanged`, `typeWindowContentChanged`, `typeViewClicked`, `typeViewSelected`) e interage com elementos da UI:

- **Mensagens temporárias**: Detecta textos "Desativadas"/"Off" e clica na duração configurada
- **Privacidade avançada**: Detecta toggles desligados de exportação/download/IA e os ativa
- **Chamadas**: Detecta botão "Recusar" e clica automaticamente

### Call Screening Service
Usa a API nativa `CallScreeningService` do Android para interceptar e rejeitar chamadas de números na lista negra, sem necessidade de permissão de telefone.

## Observações

- Alguns fabricantes (Xiaomi, Samsung, Huawei) podem matar o serviço em background. Desative a otimização de bateria para o app
- O WhatsApp pode mudar a interface em atualizações. Os textos de detecção no código podem precisar de ajuste
- O app NÃO lê mensagens — monitora apenas a interface visual

## Requisitos

- Android 8.0+ (API 26+)
- WhatsApp instalado
- compileSdk 34, buildToolsVersion 36.0.0
