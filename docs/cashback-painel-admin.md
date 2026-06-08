# Programa AquiCash — configuração pelo painel admin

O app dos clientes já implementa **todo o programa da arte oficial**: cashback em
níveis (Bronze/Prata/Ouro), desconto direto por nº de serviços (1ª Fase) e combos
especiais. Todas as **regras** (percentuais, faixas e liga/desliga) são lidas pelo
app de **um único documento no Firestore**, que o **painel admin** precisa editar.

- **Coleção:** `app_config`
- **Documento (ID fixo):** `cashback`
- Caminho completo: `app_config/cashback`

> **Percentuais sempre em número humano:** `5` = 5%, `50` = 50% (não usar `0.05`).
> **Faixas/limites em reais (R$):** `500` = R$ 500,00.
> As mudanças valem **na hora** para os clientes (o app lê o documento sempre que precisa).

---

## 1. Liga/desliga geral e fase ativa

| Campo         | Tipo    | Significado | Padrão |
|---------------|---------|-------------|--------|
| `enabled`     | boolean | Liga/desliga **todo** o programa (cashback e descontos). Se `false`, nada se aplica. | `true` |
| `activePhase` | string  | Fase ativa do programa. `"growth"` = **2ª Fase** (vale o **cashback em níveis**). `"launch"` = **1ª Fase** (vale o **desconto direto** por nº de serviços). | `"growth"` |

> A arte mostra **duas fases, uma de cada vez**: 1ª Fase – Lançamento (desconto
> direto) e 2ª Fase – Crescimento (cashback). O `activePhase` é o que alterna entre elas.
> Os **combos** valem nas **duas** fases (ver seção 4).

---

## 2. Cashback em níveis — 2ª Fase (`activePhase = "growth"`)

O nível é definido pelo **total que o cliente já gastou em serviços** (campo
`cashbackTotalSpent`, acumulado pelo app). A taxa aplicada é a do **nível atual**
do cliente (pelo gasto acumulado antes do pedido) — "quanto mais você usa, mais ganha".

| Campo             | Tipo    | Significado | Padrão |
|-------------------|---------|-------------|--------|
| `tiersEnabled`    | boolean | Liga os níveis Bronze/Prata/Ouro. Se `true`, a % ganha vem do nível e o `earnPercentage` é ignorado. | `true` |
| `bronzeRate`      | number  | % de cashback do nível **Bronze**. | `3` |
| `silverRate`      | number  | % de cashback do nível **Prata**. | `5` |
| `goldRate`        | number  | % de cashback do nível **Ouro**. | `8` |
| `silverThreshold` | number  | Gasto acumulado (R$) a partir do qual o cliente vira **Prata**. | `500` |
| `goldThreshold`   | number  | Gasto acumulado (R$) a partir do qual o cliente vira **Ouro**. | `1500` |
| `earnPercentage`  | number  | % única, usada **só quando `tiersEnabled = false`**. | `5` |

| Nível  | Faixa de gasto acumulado            | Cashback |
|--------|-------------------------------------|----------|
| Bronze | até `silverThreshold` (R$ 500)      | `bronzeRate` (3%) |
| Prata  | de R$ 500 a `goldThreshold`         | `silverRate` (5%) |
| Ouro   | acima de `goldThreshold` (R$ 1.500) | `goldRate` (8%) |

---

## 3. Usar o cashback (resgate)

| Campo                 | Tipo    | Significado | Padrão |
|-----------------------|---------|-------------|--------|
| `allowRedeem`         | boolean | Permite o cliente **usar** o saldo de cashback como desconto no pagamento. | `true` |
| `maxRedeemPercentage` | number  | Teto de quanto de um pedido pode ser pago com cashback. `50` = até 50% do pedido; `100` = pedido inteiro. | `100` |

---

## 4. Desconto direto — 1ª Fase (`activePhase = "launch"`)

Desconto aplicado **no checkout do carrinho**, conforme o **número de serviços**.
Só vale quando a fase ativa é `"launch"`.

| Campo                   | Tipo    | Significado | Padrão |
|-------------------------|---------|-------------|--------|
| `directDiscountEnabled` | boolean | Liga/desliga o desconto direto por quantidade. | `true` |
| `directDiscount2`       | number  | % de desconto com **2** serviços no carrinho. | `5` |
| `directDiscount3`       | number  | % de desconto com **3** serviços. | `10` |
| `directDiscount4Plus`   | number  | % de desconto com **4 ou mais** serviços. | `15` |

---

## 5. Combos especiais (valem nas duas fases)

Desconto por **combinação de categorias** no carrinho. Quando mais de um se aplica,
vale o **maior**. Também concorre com o desconto direto: o app aplica o **maior** entre eles.

| Campo                                | Combo (categorias)                          | Padrão |
|--------------------------------------|---------------------------------------------|--------|
| `combosEnabled`                      | Liga/desliga todos os combos (boolean).     | `true` |
| `comboEletricaHidraulicaInstalacoes` | Elétrica + Hidráulica + Instalações         | `15` |
| `comboEletricaHidraulica`            | Elétrica + Hidráulica                        | `10` |
| `comboInstalacoesHidraulica`         | Instalações + Hidráulica                     | `10` |
| `comboVeiculos`                      | Manutenção de veículos (2+ serviços automotivos) | `15` |

**Como o app mapeia os grupos de categoria** (campo `serviceNiche` do pedido):

- **Elétrica:** `Elétrica`
- **Hidráulica:** `Encanador`, `Caixa d'água`, `Desentupimento manual`,
  `Desentupimento com maquinário até 2 m`, `Caça-vazamentos`
- **Instalações:** `Instalação`, `Eletrodomésticos`, `Ar condicionado`
- **Veículos:** `Serviços automotivos` *(já existe no app — abertura de portas de
  veículos, troca/remendo de pneu, partida elétrica, pane seca etc.; não foi
  preciso criar categoria nova)*

---

## Exemplo completo do documento (JSON)

```json
{
  "enabled": true,
  "activePhase": "growth",

  "tiersEnabled": true,
  "bronzeRate": 3,
  "silverRate": 5,
  "goldRate": 8,
  "silverThreshold": 500,
  "goldThreshold": 1500,
  "earnPercentage": 5,

  "allowRedeem": true,
  "maxRedeemPercentage": 100,

  "directDiscountEnabled": true,
  "directDiscount2": 5,
  "directDiscount3": 10,
  "directDiscount4Plus": 15,

  "combosEnabled": true,
  "comboEletricaHidraulicaInstalacoes": 15,
  "comboEletricaHidraulica": 10,
  "comboInstalacoesHidraulica": 10,
  "comboVeiculos": 15
}
```

Se o documento **não existir** ou faltar algum campo, o app usa exatamente esses
padrões (todos os da arte oficial). Ou seja, o painel pode criar o documento só
com o que quiser mudar — mas o ideal é criar/exibir com todos os valores atuais.

---

## Permissões (Firestore Rules)

As regras do app são:

```
match /app_config/{configId} {
  allow read: if isSignedIn();   // o app dos clientes só LÊ
  allow write: if false;         // ninguém escreve pelo app
}
```

O painel admin deve escrever via **Firebase Admin SDK** (backend), que **ignora as
regras de segurança**. **Não** dar permissão de escrita a usuários comuns. Se o
painel for escrever pelo **SDK cliente** (web logado como admin), avise que ajusto
a regra para liberar escrita só para o admin.

---

## Como o app usa cada campo (contexto)

- **Ganhar cashback** (fase `growth`): ao concluir um serviço, o cliente ganha um %
  do valor pago. Com `tiersEnabled = true`, o % é o do **nível**; senão, é o `earnPercentage`.
- **Usar cashback:** na tela de pagamento, se `enabled` e `allowRedeem` e houver saldo,
  aparece "Usar meu cashback"; o desconto é limitado pelo saldo e por `maxRedeemPercentage`.
- **Desconto direto** (fase `launch`): no carrinho, aplica `directDiscount2/3/4Plus`
  conforme a quantidade de serviços.
- **Combos:** no carrinho, detecta as categorias e aplica o **maior** combo elegível
  (em qualquer fase). Entre desconto direto e combo, vale o **maior**.

---

## Resumo do que o painel precisa entregar

1. Tela para editar o documento `app_config/cashback` com **todos os campos** acima
   (geral, fase, níveis, resgate, desconto direto e combos).
2. Salvar via **Admin SDK** (backend).
3. **Validar:** percentuais entre 0 e 100; faixas em reais ≥ 0; `activePhase` só
   aceita `"growth"` ou `"launch"`; números, não texto.
