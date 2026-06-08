# Sistema AquiCash — Cashback, Níveis, Desconto Direto e Combos

Documentação técnica do programa de fidelidade **AquiCash**, implementado no app
dos clientes conforme a arte oficial (`docs/cashback-programa.jpg`).

O programa tem **duas fases** (uma ativa por vez, escolhida pelo painel admin) mais
os **combos** (válidos nas duas):

- **1ª Fase – Lançamento (`activePhase = "launch"`):** desconto direto por número
  de serviços no carrinho (2 → 5%, 3 → 10%, 4+ → 15%).
- **2ª Fase – Crescimento (`activePhase = "growth"`, padrão):** cashback em níveis
  Bronze/Prata/Ouro (3% / 5% / 8%) sobre serviços concluídos.
- **Combos especiais:** desconto por combinação de categorias no carrinho.

Toda a configuração é lida do documento Firestore **`app_config/cashback`** — ver
`docs/cashback-painel-admin.md` para a referência de campos destinada ao painel admin.

---

## Arquivos

| Arquivo | Papel |
|---|---|
| `CashbackManager.kt` | Config (`CashbackConfig`), níveis (`CashbackTier`), resumo (`CashbackSummary`), crédito/resgate e leitura de `app_config/cashback`. |
| `PromotionManager.kt` | Cálculo do desconto direto (1ª Fase) e dos combos a partir das categorias do carrinho. |
| `CashbackActivity.kt` + `res/layout/activity_cashback.xml` | Tela do cliente: saldo, nível, progresso, "como funciona", exemplo, lista de níveis e extrato. |
| `models/CashbackTransaction.kt` | Lançamento do extrato (crédito/resgate). |
| `adapters/CashbackTransactionAdapter.kt` + `res/layout/item_cashback_transaction.xml` | Item do extrato. |
| `FirebaseCartManager.kt` | `prepareCheckout` aplica o desconto e grava `finalPrice`/`cartDiscountPercent` por pedido. |
| `ClientCartActivity.kt` + `res/layout/activity_client_cart.xml` | Calcula/exibe Subtotal/Desconto/Total e repassa o desconto ao checkout. |
| `OrderDetailsActivity.kt` | Credita o cashback (idempotente) quando o cliente abre um pedido concluído. |
| `PaymentActivity.kt` | Resgate do cashback como desconto no pagamento. |
| `ProfileActivity.kt` | Mostra o saldo de cashback no menu do cliente. |

---

## Modelo de dados (Firestore)

**`users/{clientId}`** (escrito pelo próprio cliente — regra `isOwner`):

| Campo | Significado |
|---|---|
| `cashbackBalance` | Saldo disponível para usar. |
| `cashbackTotalEarned` | Total de cashback já creditado (histórico). |
| `cashbackTotalSpent` | **Total gasto em serviços**, que define o nível (Bronze/Prata/Ouro). |

**`users/{clientId}/cashback_transactions/{txId}`** — extrato. Para créditos de
pedido o id é `earn_{orderId}` (idempotência: o mesmo pedido nunca credita duas vezes).

**`orders/{orderId}`** (campos novos, gravados no checkout do carrinho):

| Campo | Significado |
|---|---|
| `finalPrice` | Preço efetivamente cobrado do cliente (já com o desconto do programa). |
| `cartDiscountPercent` | % de desconto aplicado no checkout (0 quando não há). |

> `providerCommission` **não** muda com o desconto: o desconto é custeado pela
> plataforma, não descontado do prestador.

**`app_config/cashback`** — documento único de configuração (ver
`docs/cashback-painel-admin.md`).

---

## 2ª Fase — Cashback em níveis

- O nível vem de `cashbackTotalSpent`:
  Bronze (até `silverThreshold`), Prata (≥ `silverThreshold`), Ouro (≥ `goldThreshold`).
- A taxa do crédito é a do **nível atual** do cliente (gasto acumulado **antes** do
  pedido). Cada pedido eleva o total gasto e pode subir o nível para os próximos.
- **Crédito** (`CashbackManager.creditForCompletedOrder`): roda numa transação
  Firestore, idempotente por `earn_{orderId}`; soma `cashbackBalance`,
  `cashbackTotalEarned` e `cashbackTotalSpent`. Só credita se `enabled` **e**
  `activePhase == "growth"`.
- **Onde credita:** em `OrderDetailsActivity`, quando o cliente abre um pedido
  concluído. (As regras do Firestore só permitem o próprio cliente escrever no seu
  documento; o prestador é quem finaliza o pedido, então o crédito não pode ocorrer
  na mesma transação de conclusão.)
- **Resgate** (`CashbackManager.redeem`, usado em `PaymentActivity`): usa o saldo
  como desconto, limitado por `maxRedeemPercentage`. Independe da fase (`allowRedeem`).
- **Resumo da tela** (`CashbackManager.getSummary`): saldo, total acumulado, nível,
  taxa atual, próximo nível, quanto falta e progresso (0..1).

---

## 1ª Fase — Desconto direto + Combos (`PromotionManager`)

`computeDiscount(niches, subtotal, config)` retorna `DiscountResult(percent, amount, label)`:

1. **Desconto por quantidade** (só na fase `launch` e se `directDiscountEnabled`):
   2 → `directDiscount2`, 3 → `directDiscount3`, 4+ → `directDiscount4Plus`.
2. **Melhor combo** (se `combosEnabled`, vale nas duas fases).
3. Aplica o **maior** entre quantidade e combo.

**Combos e mapeamento de grupos** (sobre o campo `serviceNiche` = nome da categoria):

| Combo | Grupos exigidos | % padrão |
|---|---|---|
| Elétrica + Hidráulica + Instalações | os três grupos | 15% |
| Elétrica + Hidráulica | dois grupos | 10% |
| Instalações + Hidráulica | dois grupos | 10% |
| Manutenção de veículos | 2+ itens de Veículos | 15% |

- **Elétrica:** `Elétrica`
- **Hidráulica:** `Encanador`, `Caixa d'água`, `Desentupimento manual`,
  `Desentupimento com maquinário até 2 m`, `Caça-vazamentos`
- **Instalações:** `Instalação`, `Eletrodomésticos`, `Ar condicionado`
- **Veículos:** `Serviços automotivos` *(categoria já existente — não foi criada nova)*

**Integração no checkout:** `ClientCartActivity` calcula o desconto (config +
categorias do carrinho), mostra Subtotal/Desconto/Total e passa `discountPercent`
para `FirebaseCartManager.prepareCheckout`, que grava `finalPrice`/`cartDiscountPercent`
por pedido e cobra o total descontado. Pedido avulso (1 serviço) nunca recebe desconto.

---

## Regras do Firestore alteradas

`validOrderCreate()` em `firestore.rules` passou a permitir os campos `finalPrice` e
`cartDiscountPercent` na criação de pedido (whitelist `hasOnly`). São **opcionais**
(não entram no `hasAll`), então o pedido avulso continua válido sem eles. A escrita
de `cashbackTotalSpent` em `users/{id}` já era permitida (a regra de update não
restringe campos). **As regras já foram publicadas** em `aplicativoservico-143c2`.

---

## Limitações conhecidas / próximos passos

- **Crédito client-side:** o cashback só é creditado quando o cliente abre o pedido
  concluído. Se nunca abrir, não credita nem soma o gasto (o nível não sobe). O ideal
  é uma **Cloud Function** que credite no backend.
- **Backfill:** `cashbackTotalSpent` começa em 0; clientes antigos caem em Bronze
  mesmo com gasto alto. Avaliar migração via Admin SDK.
- **Benefícios de nível além do %:** "prioridade no atendimento", "atendimento
  dedicado" e "ofertas exclusivas" aparecem como **texto** na tela, mas ainda **não
  têm lógica** por trás.
- **Painel admin:** a tela que edita `app_config/cashback` é de outro projeto
  (web); até existir, o app usa os padrões da arte. Ver `docs/cashback-painel-admin.md`.
