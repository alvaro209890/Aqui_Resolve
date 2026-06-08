# AquiResolve — Marketplace de Serviços

Aplicativo Android para conectar clientes a prestadores de serviços. Clientes encontram, contratam e pagam por serviços; prestadores gerenciam pedidos, agenda e recebimentos.

## Stack

| Tecnologia | Versão |
|---|---|
| Kotlin | 1.9.22 |
| Compile/Target SDK | 35 |
| Min SDK | 24 |
| Gradle | 8.8.0 |
| Firebase BOM | 32.7.0 |
| Retrofit | 2.9.0 |
| OkHttp | 4.12.0 |
| Glide | 4.16.0 |
| ZXing | 3.5.2 |
| OSMDroid | 6.1.18 |
| Material Design | 3 |
| Coroutines | 1.7.3 |

## Arquitetura

```
Activities → Managers → Firebase
```

- **Presentation:** ~44 Activities com ViewBinding + coroutines (`lifecycleScope`)
- **Managers:** Toda a lógica de negócio em classes separadas (Firebase e negócio) — inclui FirebaseChecklistManager, FirebaseAuthManager, FirebaseOrderManager, etc.
- **Models:** Anotados com `@PropertyName` do Firestore
- **Adapters:** ~16 RecyclerView adapters
- **Utils:** `PriceFormatter`, `ProtocolGenerator`, `NotificationBadgeHelper`, `TextFormatter`, `LocationPermissionHelper`, `ServiceSearchHelper`, `ServiceNicheCatalog`
- **Views:** Componentes customizados em `views/` (SignaturePad para assinaturas digitais)

## Funcionalidades

### Autenticação
- Cadastro e login para **cliente** e **prestador** (fluxos separados)
- Telefone obrigatório no cadastro de cliente
- Recuperação de senha
- Firebase Authentication

### Pedidos
- Criação de pedidos com categorias de serviço
- Fluxo de status: `pending → distributing → assigned → in_progress → completed`
- Distribuição automática para prestadores disponíveis
- Código de verificação de 6 dígitos na conclusão
- Cancelamento com política de reembolso (5 min)

### Pagamentos (Pagar.me v5)
- **Cartão de crédito:** Validação Luhn, detecção de bandeira
- **PIX:** Geração de QR Code (ZXing), polling automático a cada 5s
- API em `https://aquiresolve.onrender.com/api/payments/`

### Cashback / Fidelidade (AquiCash)
- Programa configurável por **um único documento** Firestore `app_config/cashback`
- **Duas fases** alternadas pelo painel admin (`activePhase`):
  - **Crescimento (padrão):** cashback em **níveis** — Bronze (≤R$500) 3%, Prata (R$500–1.500) 5%, Ouro (>R$1.500) 8%, pelo total gasto acumulado
  - **Lançamento:** desconto direto no carrinho por nº de serviços (2→5%, 3→10%, 4+→15%)
- **Combos especiais** (valem nas duas fases): Elétrica+Hidráulica+Instalações 15%, Elétrica+Hidráulica 10%, Instalações+Hidráulica 10%, Manutenção de veículos 15%; aplica-se o **maior** desconto
- Saldo, extrato e progresso de nível na tela do cliente; resgate como desconto no pagamento
- Crédito idempotente por pedido; valor do prestador não é afetado pelo desconto
- Detalhes técnicos em `docs/SISTEMA_CASHBACK_AQUICASH.md`; campos do painel em `docs/cashback-painel-admin.md`

### Segurança
- Network security config com domains confiáveis
- ProGuard ativado no release build
- reCAPTCHA Enterprise 18.4.0
- Regras de segurança Firestore e Storage versionadas

### Chat
- Tempo real via Firestore listeners
- Bloqueio de acesso de 5 minutos após aceitação do pedido

### Localização
- Google Play Services (atualização a cada 5 min)
- Mapas via OSMDroid (OpenStreetMap)
- GeoPoint no Firestore

### OS Checklist (Ordem de Serviço)
- Checklist de execução com 10 perguntas (chegada ao local + execução do serviço)
- Captura de GPS e timestamp no início do serviço
- 3 categorias de fotos (antes/durante/depois), upload para Firebase Storage
- Assinaturas digitais do prestador e do cliente com desenho à mão livre (SignaturePad)
- Fluxo de status: `checklist_pending → photos_pending → signatures_pending → completed`
- Histórico completo por pedido em OsHistoryActivity
- Coleção `checklists/{orderId}` no Firestore

### Imagens
- Compressão para max 1MB / 1920x1080
- Firebase Storage
- Glide para carregamento
- PhotoView para zoom
- uCrop para recorte de avatar

### Notificações
- Firebase Cloud Messaging
- Múltiplos canais de notificação
- Privacidade na entrega

### Outros
- Agendamento de serviços
- Avaliações
- Histórico de serviços
- Documentos do prestador (upload e verificação)
- Gerenciamento de endereços
- Localização em foreground (ProviderLocationForegroundService)
- Dados bancários do prestador
- Privacidade e exportação de dados (GDPR)
- Favoritos

## Pré-requisitos

- Android Studio Hedgehog ou superior
- Android SDK 35
- JDK 17
- Conta Firebase com projeto configurado
- Firebase CLI (opcional, para deploy de regras/índices)

## Configuração

1. Clone o repositório:
```bash
git clone git@github.com:alvaro209890/AquiResolve.git
```

2. Adicione o arquivo `app/google-services.json` do Firebase Console

3. Configure keystore de release em `keystore/upload-keystore.credentials.txt`
4. (Opcional) Deploy das regras Firebase:
```bash
firebase --project aplicativoservico-143c2 deploy --only firestore:rules,firestore:indexes,storage:rules
```

## Build

```bash
./gradlew assembleDebug          # APK debug
./gradlew installDebug           # Instalar em dispositivo
./gradlew assembleRelease        # APK release (minificado + ofuscado)
./gradlew bundleRelease          # AAB release (Play Store)
./gradlew lint                   # Verificações de lint
./gradlew test                   # Testes unitários
```

## Estrutura do Projeto

```
app/
├── src/main/java/com/aquiresolve/app/
│   ├── adapters/          # RecyclerView adapters
│   ├── api/               # Retrofit (Pagar.me)
│   ├── constants/         # Constantes (códigos de pagamento)
│   ├── models/            # Data classes Firestore
│   │   └── payment/       # Modelos de pagamento
│   ├── payment/           # Lógica Pagar.me
│   ├── utils/             # Helpers (PriceFormatter, ProtocolGenerator, permissões)
│   ├── views/             # Custom views (SignaturePad)
│   ├── *.kt               # Activities + Managers
│   └── AppApplication.kt  # Application class
├── google-services.json   # Config Firebase
├── build.gradle           # Build do módulo
├── proguard-rules.pro     # Regras ProGuard
├── firestore.rules        # Regras Firestore
├── firestore.indexes.json # Índices compostos Firestore
├── storage.rules          # Regras Storage
├── keystore/              # Keystore de release
├── docs/                  # Documentação complementar
├── backend/               # Backend Node.js (Render)
└── web/                   # Página web auxiliar
```

## Firebase

- **Projeto:** `aplicativoservico-143c2`
- **Firestore:** Regras em `firestore.rules`, índices em `firestore.indexes.json`. Coleções: `orders`, `checklists`, `chats`, `carts`, `users` (subcoleção `cashback_transactions`), `app_config` (doc `cashback`), etc.
- **Storage:** Regras em `storage.rules`
- **Realtime Database:** Regras em `database.rules.json`

## Licença

MIT
