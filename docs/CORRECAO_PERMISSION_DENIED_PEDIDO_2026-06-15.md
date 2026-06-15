# Correção: `PERMISSION_DENIED` ao criar pedido + Workflow de APK (2026-06-15)

> Documento de referência da investigação e correção do erro `PERMISSION_DENIED`
> que aparecia ao tocar em **"fazer pedido"** no app mobile, e do workflow de
> geração de APK no GitHub Actions criado na mesma sessão.

---

## 1. Sintoma

Cliente preenchia o pedido, tocava em **"fazer pedido"** e, em vez de ir para a
tela de pagamento, recebia:

```
❌ Erro ao processar pedido: PERMISSION_DENIED: Missing or insufficient permissions.
```

O pedido era criado e logo **revertido** (sumia), porque o app faz rollback
(`cleanupPendingOrder`) quando qualquer etapa do checkout falha.

---

## 2. Causa raiz

A investigação encontrou **duas camadas**. A segunda é a que persistia mesmo com
o APK atualizado.

### 2.1 Camada 1 — payload legado (APK antigo)

APKs anteriores ao refactor de 18/05 ("centralize payment confirmation in
backend") criavam o pedido serializando o **`OrderData` inteiro**:
`status='distributing'`, `id`, `priority`, `distributionStartedAt`,
`adminNotes`, flags de conclusão… e **sem** `paymentStatus='awaiting_payment'`.

A regra `validOrderCreate` (modelo *pay-before-distribution*, com `hasOnly`/
`hasAll` e exigindo `status=='awaiting_payment'`) **nega** esse payload — de
propósito, para impor "pagar antes de distribuir".

➡️ Corrigido no código (o fluxo real já usava um *map* enxuto; os helpers
legados foram alinhados — ver §3.1).

### 2.2 Camada 2 — bug na regra do `update("images")` (causa real)

Mesmo com o APK novo o erro continuava **quando o pedido tinha fotos**. O fluxo
em `CreateOrderActivity.startSingleOrderCheckout` é:

```
1. orderRef.set(payload enxuto)          → CREATE   (status=awaiting_payment)
2. upload das fotos no Storage           → Pedidos/{orderId}/...
3. orderRef.update("images", urls)        → UPDATE   ◄── NEGAVA AQUI
4. navigateToPayment(...)
```

A regra de update do cliente (`validClientOrderUpdate`) chamava o guard:

```
function orderSensitiveAssignmentFieldsUnchanged() {
  return request.resource.data.assignedProvider == resource.data.assignedProvider
    && request.resource.data.assignedProviderName == resource.data.assignedProviderName
    && request.resource.data.clientVerificationCode == resource.data.clientVerificationCode
    && request.resource.data.providerVerificationCode == resource.data.providerVerificationCode;
}
```

**O problema:** `validOrderCreate` **proíbe** esses campos no pedido (não estão
no `hasOnly`). Logo, um pedido recém-criado **nunca os tem**. Acessá-los direto
(`resource.data.assignedProvider`) num documento que não os possui fazia a
avaliação da regra falhar → `update("images")` **negado** → rollback →
`PERMISSION_DENIED` para o cliente.

Efeito colateral: também quebrava **cancelar um pedido ainda não atribuído**
(mesmo guard).

---

## 3. Correções aplicadas

### 3.1 Código do app (commit `1266500`)

| Arquivo | Mudança |
|---|---|
| `FirebaseOrderManager.createOrder()` | Não serializa mais o `OrderData` inteiro; monta *map* enxuto só com campos da allowlist e `status/paymentStatus=awaiting_payment`. |
| `CreateOrderActivity.ensureDraftOrderId()` | Gera o ID de rascunho **localmente** (`.document().id`) em vez de gravar um doc `status='draft'` (que a regra nega). |

> Esses dois eram *dead code*/landmines (não no caminho real do checkout), mas
> ficavam inconsistentes com a regra. Alinhados para não regredir num build futuro.

### 3.2 Regra do Firestore (commit `94d9136`) — **a correção que resolveu**

`firestore.rules` → `orderSensitiveAssignmentFieldsUnchanged()` passou a usar
`get(campo, null)`:

```
function orderSensitiveAssignmentFieldsUnchanged() {
  return request.resource.data.get('assignedProvider', null) == resource.data.get('assignedProvider', null)
    && request.resource.data.get('assignedProviderName', null) == resource.data.get('assignedProviderName', null)
    && request.resource.data.get('clientVerificationCode', null) == resource.data.get('clientVerificationCode', null)
    && request.resource.data.get('providerVerificationCode', null) == resource.data.get('providerVerificationCode', null);
}
```

- **Ausente == ausente** vira `null == null` → passa (libera o `update("images")`
  e o cancelamento de pedido não atribuído).
- **Alterar** qualquer um desses campos continua **bloqueado** (segurança intacta).

> Como é correção de **regra (servidor)**, vale para o **APK já instalado** — não
> exige novo APK.

---

## 4. Verificação ao vivo (metodologia)

As regras foram testadas **contra o Firebase de produção**, com request
autenticado (regras de fato aplicadas), usando a REST API do Firestore com um
**ID token** do usuário de teste (`cliente.teste`). Resultado final
(ruleset publicado `c4770cb9`):

| Cenário | Esperado | Resultado |
|---|---|---|
| CREATE do pedido (payload do app) | ALLOW | ✅ 200 |
| Upload das fotos no Storage `Pedidos/{id}/` | ALLOW | ✅ 200 |
| UPDATE `images` (o que negava) | ALLOW | ✅ 200 |
| 🔒 Cliente tentar setar `assignedProvider` | **DENY** | ✅ 403 |
| Cliente cancelar pedido não atribuído | ALLOW | ✅ 200 |
| Cliente avaliar pedido concluído | ALLOW | ✅ 200 |
| Cliente confirmar conclusão | ALLOW | ✅ 200 |

---

## 5. Operar regras SEM o Firebase CLI (esta máquina não tem)

Tudo via REST + **service account** (`FIREBASE_SERVICE_ACCOUNT` do
`dashboard_admin/.env.local`), minando o token com o `crypto` nativo do Node.
Scripts de referência ficaram em `/tmp` durante a sessão:

| Objetivo | Como |
|---|---|
| **Ler** as regras publicadas | `GET firebaserules.googleapis.com/v1/projects/{P}/releases` → acha `cloud.firestore` → `GET /v1/{rulesetName}` |
| **Publicar** regra nova | `POST /v1/projects/{P}/rulesets` (compila/valida) → `PATCH /v1/projects/{P}/releases/cloud.firestore?updateMask=rulesetName` |
| **Testar** uma escrita com a regra valendo | minta **custom token** (SA) → troca por **ID token** em `identitytoolkit … :signInWithCustomToken?key=WEB_API_KEY` → faz a operação na REST do Firestore com `Authorization: Bearer <ID_TOKEN>` (regras aplicadas: 403 = negado, 200 = permitido) |
| **Semear** estado arbitrário (bypassa regra) | REST do Firestore com **OAuth token da SA** (scope `datastore`) — admin, ignora regras |

> A SA `firebase-adminsdk-fbsvc@…` **pode** criar ruleset + apontar a release
> (deploy), mas **não** tem `firebaserules.rulesets.test` (a API oficial de teste
> dá 403) — por isso o teste é feito com ID token na REST do Firestore.

**Rollback de regra:** repontar a release para o ruleset anterior.
Antes desta correção: `10c84c88-0696-4b1a-9523-5381e0518dd3`.
Depois: `c4770cb9-8420-48b9-ae47-210143fab283`.

---

## 6. Workflow de geração de APK (GitHub Actions)

Arquivo: `.github/workflows/build-apk.yml`.

- **Gatilhos:** `workflow_dispatch` (botão *Run workflow*, com toggle "gerar
  release") **e** push de tag `v*`.
- **O que faz:** restaura `google-services.json` e o keystore a partir de
  secrets, builda `assembleDebug` (sempre) e `assembleRelease` (quando há
  keystore), e publica os `.apk` como **artifacts** (retidos 30 dias).

### Secrets necessários (Settings → Secrets and variables → Actions)

| Secret | Conteúdo | Para |
|---|---|---|
| `GOOGLE_SERVICES_JSON_BASE64` | base64 de `app/google-services.json` | qualquer build |
| `UPLOAD_KEYSTORE_JKS_BASE64` | base64 de `keystore/upload-keystore.jks` | release assinado |
| `UPLOAD_KEYSTORE_CREDENTIALS_BASE64` | base64 de `keystore/upload-keystore.credentials.txt` | release assinado |

> Esses 3 arquivos são **gitignored** (segredos/chaves). Para (re)gerar um secret:
> `base64 -w0 <arquivo> | gh secret set <NOME> -R alvaro209890/AquiResolve`.

### Gerar e baixar

```bash
# disparar manualmente
gh workflow run "Build APK" -f build_release=true
# ou por versão
git tag v1.2.8 && git push origin v1.2.8

# baixar o artifact de um run
gh run download <run-id> -n aquiresolve-release-apk   # ou aquiresolve-debug-apk
```

- **release** (`assembleRelease`, ~4–6 MB): minificado (R8) + `shrinkResources` +
  assinado com o upload-keystore → é o que distribui/publica.
- **debug** (`assembleDebug`, ~13–15 MB): chave de debug, sem otimização → só
  para iteração rápida.

### Gotcha de instalação

Android bloqueia instalar por cima se a **assinatura** diferir da já instalada.
Ao trocar entre debug/release (ou de um build antigo para o novo), **desinstale**
o app antes. Dados (login/pedidos) ficam no Firebase, não se perdem.

---

## 7. Resumo executivo

- **Erro:** `PERMISSION_DENIED` ao fazer pedido (com fotos) → causa real era a
  regra de update bloqueando o `update("images")` de um pedido recém-criado.
- **Fix:** `get(campo, null)` no guard `orderSensitiveAssignmentFieldsUnchanged`
  (`firestore.rules`, commit `94d9136`) — **publicado ao vivo**.
- **Impacto no APK:** **nenhum** — correção é server-side; o APK já instalado
  passa a funcionar.
- **Bônus:** workflow de APK no GitHub Actions + alinhamento de *dead code* do
  pedido.
