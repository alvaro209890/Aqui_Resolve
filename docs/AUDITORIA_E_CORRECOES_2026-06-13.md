# Auditoria profunda e correções — 2026-06-13

Análise ponta-a-ponta do AquiResolve (app mobile, painel admin, backend de pagamentos,
variáveis do Render e regras do Firebase) com foco em bugs e segurança. Resumo do que foi
encontrado, o que foi corrigido e o que foi verificado como íntegro.

## 🔒 Correção de segurança — catálogo de serviços gravável por qualquer usuário

**Problema:** as coleções `service_categories`, `service_types` e `service_providers` tinham
`allow write: if isSignedIn()`. Como o app autentica clientes e prestadores via Firebase Auth,
**qualquer usuário autenticado podia criar, editar ou apagar o catálogo global** (integridade do
marketplace exposta). O painel só conseguia gravar nessas coleções por causa dessa regra permissiva
(escrevia via client SDK em `/dashboard/servicos/catalogo-app`).

**Correção:**
1. Nova rota Admin SDK `dashboard_admin/app/api/catalog/route.ts` (`POST` upsert, `DELETE ?id=`),
   gravando em `service_categories` + `service_types`.
2. A página `catalogo-app/page.tsx` passou a **gravar via `fetch('/api/catalog')`** (mantém o
   `onSnapshot` de leitura em tempo real).
3. Regras endurecidas: `service_categories` / `service_types` / `service_providers` →
   `allow read: if isSignedIn()` e **`allow write: if false`** (escrita só Admin SDK). Deployadas.

Sem regressão para o app: a semeadura de exemplo no cliente (`FirebaseServiceManager.populateSampleData`)
só roda por gesto de desenvolvimento e o auto-seed (`populateSampleDataIfNeeded`) já era no-op; o
catálogo real é gerido pelo painel e pelo `scripts/seed-catalog.mjs` (Admin SDK).

## ⚠️ Observação — `isAdmin()` sem custom claims

Verificado via Admin SDK: **nenhum** dos 110 usuários do Firebase Auth tem custom claim
(`role:'admin'` / `admin:true`). Logo, regras que dependem de `isAdmin()` via client SDK não passam.
Isso **não** quebra o painel porque toda escrita privilegiada (status de pedido, verificação de
prestador, bloqueio de usuário, catálogo, reembolso, cashback) passa por **API Routes com Admin SDK**,
que ignoram as regras. `master@aquiresolve.com` existe só no doc `adminmaster/master` (login custom
via `/api/auth/master-login`), não como usuário Firebase Auth. Documentado em `CLAUDE.md`.

## ✅ Verificado íntegro (sem bugs)

- **Backend de pagamentos (Render):** fluxo cartão/PIX/status/webhook, autorização do payload
  (`payment-authorization.service`), sincronização de status (`payment-status-sync.service`,
  `awaiting_payment → distributing`), posse da sessão de pagamento, `toCents` à prova de IEEE-754,
  rate-limit e CORS. Sem bugs.
- **Reembolso** (`/api/orders/[id]/refund`) e **reconciliação `charge.refunded`** no webhook do painel.
- **Chat:** upsert de `chatConversations/{orderId}` no envio de mensagem (Central Operacional).
- **Catálogo dinâmico no app** (`CatalogRepository` + fallback estático).
- **Índices do Firestore:** cobrem as consultas usadas.
- **Builds:** `npm run build` (painel) e `./gradlew :app:assembleDebug` (APK) — ambos verdes.

## 🔧 Variáveis do Render

Estado conferido via API do Render — todas corretas. **Nenhuma alteração necessária.**
`PAGARME_WEBHOOK_SECRET` permanece **ausente de propósito**: defini-lo no Render sem registrar o mesmo
segredo no painel da Pagar.me faria o backend **rejeitar (401)** os webhooks. O polling de 5s do app já
confirma o pagamento; o webhook é só confirmação instantânea. Para ativar com segurança, definir o
segredo nos **dois** lados ao mesmo tempo (passo manual coordenado).

## 📦 Passos manuais que permanecem com o usuário

- Substituir `app/google-services.json` (placeholder de 722 B) pelo arquivo real antes de testar no
  aparelho / publicar.
- (Opcional) `PAGARME_WEBHOOK_SECRET` coordenado Render ↔ painel Pagar.me.
- Submissão na Play Store (conta do Play Console).
