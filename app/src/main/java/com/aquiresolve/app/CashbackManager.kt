package com.aquiresolve.app

import android.util.Log
import com.aquiresolve.app.models.CashbackTransaction
import com.aquiresolve.app.models.OrderData
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

/**
 * Gerencia o cashback dos clientes.
 *
 * Modelo de dados (tudo dentro do próprio documento do cliente, então as
 * escritas respeitam a regra `isOwner(userId)` do Firestore):
 *  - `users/{clientId}.cashbackBalance`      -> saldo disponível
 *  - `users/{clientId}.cashbackTotalEarned`  -> total acumulado (histórico)
 *  - `users/{clientId}/cashback_transactions/{txId}` -> extrato
 *
 * Crédito: feito no lado do cliente (idempotente via id `earn_{orderId}`)
 * quando ele visualiza um pedido concluído. Como o prestador é quem finaliza
 * o pedido (digitando o código do cliente), não dá para creditar na mesma
 * transação de conclusão sem violar as regras de permissão.
 */
class CashbackManager {

    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    companion object {
        private const val TAG = "CashbackManager"
        private const val USERS_COLLECTION = "users"
        private const val TX_SUBCOLLECTION = "cashback_transactions"
        private const val CONFIG_COLLECTION = "app_config"
        private const val CONFIG_DOC = "cashback"

        // Valores padrão usados quando o painel admin ainda não configurou nada.
        const val DEFAULT_EARN_PERCENTAGE = 5.0      // 5% (usado só se os níveis estiverem desligados)
        const val DEFAULT_ENABLED = true
        const val DEFAULT_ALLOW_REDEEM = true
        const val DEFAULT_MAX_REDEEM_PERCENTAGE = 100.0 // até 100% do pedido

        // Programa de fidelidade em níveis (Bronze/Prata/Ouro), conforme a arte oficial:
        //   Bronze: até R$500 em serviços      -> 3% de cashback
        //   Prata:  de R$500 a R$1.500         -> 5% de cashback
        //   Ouro:   acima de R$1.500           -> 8% de cashback
        const val DEFAULT_TIERS_ENABLED = true
        const val DEFAULT_BRONZE_RATE = 3.0
        const val DEFAULT_SILVER_RATE = 5.0
        const val DEFAULT_GOLD_RATE = 8.0
        const val DEFAULT_SILVER_THRESHOLD = 500.0   // gasto a partir do qual vira Prata
        const val DEFAULT_GOLD_THRESHOLD = 1500.0    // gasto a partir do qual vira Ouro

        // Fase ativa do programa (a imagem mostra duas fases, "uma de cada vez"):
        //   launch  (1ª Fase – Lançamento)   -> vale o DESCONTO DIRETO por nº de serviços
        //   growth  (2ª Fase – Crescimento)  -> vale o CASHBACK em níveis
        const val PHASE_LAUNCH = "launch"
        const val PHASE_GROWTH = "growth"
        const val DEFAULT_ACTIVE_PHASE = PHASE_GROWTH

        // 1ª Fase – Desconto direto por número de serviços no carrinho.
        const val DEFAULT_DIRECT_DISCOUNT_ENABLED = true
        const val DEFAULT_DIRECT_DISCOUNT_2 = 5.0       // 2 serviços
        const val DEFAULT_DIRECT_DISCOUNT_3 = 10.0      // 3 serviços
        const val DEFAULT_DIRECT_DISCOUNT_4PLUS = 15.0  // 4 ou mais serviços

        // Combos especiais (valem nas duas fases). % do maior combo aplicável.
        const val DEFAULT_COMBOS_ENABLED = true
        const val DEFAULT_COMBO_ELE_HID_INST = 15.0 // Elétrica + Hidráulica + Instalações
        const val DEFAULT_COMBO_ELE_HID = 10.0      // Elétrica + Hidráulica
        const val DEFAULT_COMBO_INST_HID = 10.0     // Instalações + Hidráulica
        const val DEFAULT_COMBO_VEICULOS = 15.0     // Manutenção de veículos (2+ serviços automotivos)

        /** Arredonda para 2 casas decimais (centavos). */
        private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

        /** Formata um percentual humano: 5.0 -> "5%", 7.5 -> "7,5%". */
        fun formatRate(rate: Double): String =
            if (rate % 1.0 == 0.0) "${rate.toInt()}%"
            else String.format(java.util.Locale("pt", "BR"), "%.1f%%", rate)
    }

    /** Níveis do programa de fidelidade. */
    enum class CashbackTier(val displayName: String, val emoji: String) {
        BRONZE("Bronze", "🥉"), // 🥉
        PRATA("Prata", "🥈"),   // 🥈
        OURO("Ouro", "🥇")      // 🥇
    }

    /**
     * Configuração de cashback controlada pelo painel admin
     * (documento `app_config/cashback`). Percentuais em número humano: 5 = 5%.
     */
    data class CashbackConfig(
        val enabled: Boolean = DEFAULT_ENABLED,
        val earnPercentage: Double = DEFAULT_EARN_PERCENTAGE,
        val allowRedeem: Boolean = DEFAULT_ALLOW_REDEEM,
        val maxRedeemPercentage: Double = DEFAULT_MAX_REDEEM_PERCENTAGE,
        // Programa de fidelidade em níveis (Bronze/Prata/Ouro).
        val tiersEnabled: Boolean = DEFAULT_TIERS_ENABLED,
        val bronzeRate: Double = DEFAULT_BRONZE_RATE,
        val silverRate: Double = DEFAULT_SILVER_RATE,
        val goldRate: Double = DEFAULT_GOLD_RATE,
        val silverThreshold: Double = DEFAULT_SILVER_THRESHOLD,
        val goldThreshold: Double = DEFAULT_GOLD_THRESHOLD,
        // Fase ativa + 1ª Fase (desconto direto) + combos especiais.
        val activePhase: String = DEFAULT_ACTIVE_PHASE,
        val directDiscountEnabled: Boolean = DEFAULT_DIRECT_DISCOUNT_ENABLED,
        val directDiscount2: Double = DEFAULT_DIRECT_DISCOUNT_2,
        val directDiscount3: Double = DEFAULT_DIRECT_DISCOUNT_3,
        val directDiscount4Plus: Double = DEFAULT_DIRECT_DISCOUNT_4PLUS,
        val combosEnabled: Boolean = DEFAULT_COMBOS_ENABLED,
        val comboEletricaHidraulicaInstalacoes: Double = DEFAULT_COMBO_ELE_HID_INST,
        val comboEletricaHidraulica: Double = DEFAULT_COMBO_ELE_HID,
        val comboInstalacoesHidraulica: Double = DEFAULT_COMBO_INST_HID,
        val comboVeiculos: Double = DEFAULT_COMBO_VEICULOS
    ) {
        /** Fração para cálculo do crédito quando os níveis estão desligados (ex.: 5% -> 0.05). */
        val earnFraction: Double get() = earnPercentage / 100.0
        /** Fração máxima do pedido que pode ser paga com cashback. */
        val maxRedeemFraction: Double get() = maxRedeemPercentage / 100.0

        /** Fase de lançamento: vale o desconto direto por nº de serviços. */
        val isLaunchPhase: Boolean get() = activePhase == PHASE_LAUNCH
        /** Fase de crescimento: vale o cashback em níveis. */
        val isGrowthPhase: Boolean get() = activePhase == PHASE_GROWTH

        /** Nível de fidelidade para um total acumulado gasto em serviços. */
        fun tierFor(totalSpent: Double): CashbackTier = when {
            totalSpent >= goldThreshold -> CashbackTier.OURO
            totalSpent >= silverThreshold -> CashbackTier.PRATA
            else -> CashbackTier.BRONZE
        }

        /** % de cashback de um nível. */
        fun rateForTier(tier: CashbackTier): Double = when (tier) {
            CashbackTier.BRONZE -> bronzeRate
            CashbackTier.PRATA -> silverRate
            CashbackTier.OURO -> goldRate
        }

        /**
         * % de cashback aplicável a um cliente, dado o total já gasto.
         * Com níveis ligados usa a taxa do nível atual; senão, a taxa única.
         */
        fun earnPercentageFor(totalSpent: Double): Double =
            if (tiersEnabled) rateForTier(tierFor(totalSpent)) else earnPercentage

        /** Próximo nível a alcançar (null se já é Ouro). */
        fun nextTier(tier: CashbackTier): CashbackTier? = when (tier) {
            CashbackTier.BRONZE -> CashbackTier.PRATA
            CashbackTier.PRATA -> CashbackTier.OURO
            CashbackTier.OURO -> null
        }

        /** Gasto acumulado mínimo para entrar em um nível. */
        fun entryThreshold(tier: CashbackTier): Double = when (tier) {
            CashbackTier.BRONZE -> 0.0
            CashbackTier.PRATA -> silverThreshold
            CashbackTier.OURO -> goldThreshold
        }
    }

    /**
     * Resumo do cashback de um cliente: saldo, acumulados e situação no
     * programa de fidelidade (nível atual, taxa e progresso para o próximo).
     */
    data class CashbackSummary(
        val balance: Double,
        val totalEarned: Double,
        val totalSpent: Double,
        val tiersEnabled: Boolean,
        val tier: CashbackTier,
        val currentRate: Double,
        val nextTier: CashbackTier?,
        val amountToNextTier: Double,
        val progressToNext: Double, // 0.0..1.0 dentro da faixa do nível atual
        val config: CashbackConfig
    )

    /**
     * Lê a configuração de cashback definida no painel admin.
     * Se o documento não existir ou houver erro, usa os valores padrão.
     */
    suspend fun getConfig(): CashbackConfig {
        return try {
            val snap = db.collection(CONFIG_COLLECTION).document(CONFIG_DOC).get().await()
            if (!snap.exists()) {
                CashbackConfig()
            } else {
                CashbackConfig(
                    enabled = snap.getBoolean("enabled") ?: DEFAULT_ENABLED,
                    earnPercentage = snap.getDouble("earnPercentage") ?: DEFAULT_EARN_PERCENTAGE,
                    allowRedeem = snap.getBoolean("allowRedeem") ?: DEFAULT_ALLOW_REDEEM,
                    maxRedeemPercentage = snap.getDouble("maxRedeemPercentage")
                        ?: DEFAULT_MAX_REDEEM_PERCENTAGE,
                    tiersEnabled = snap.getBoolean("tiersEnabled") ?: DEFAULT_TIERS_ENABLED,
                    bronzeRate = snap.getDouble("bronzeRate") ?: DEFAULT_BRONZE_RATE,
                    silverRate = snap.getDouble("silverRate") ?: DEFAULT_SILVER_RATE,
                    goldRate = snap.getDouble("goldRate") ?: DEFAULT_GOLD_RATE,
                    silverThreshold = snap.getDouble("silverThreshold") ?: DEFAULT_SILVER_THRESHOLD,
                    goldThreshold = snap.getDouble("goldThreshold") ?: DEFAULT_GOLD_THRESHOLD,
                    activePhase = snap.getString("activePhase") ?: DEFAULT_ACTIVE_PHASE,
                    directDiscountEnabled = snap.getBoolean("directDiscountEnabled")
                        ?: DEFAULT_DIRECT_DISCOUNT_ENABLED,
                    directDiscount2 = snap.getDouble("directDiscount2") ?: DEFAULT_DIRECT_DISCOUNT_2,
                    directDiscount3 = snap.getDouble("directDiscount3") ?: DEFAULT_DIRECT_DISCOUNT_3,
                    directDiscount4Plus = snap.getDouble("directDiscount4Plus")
                        ?: DEFAULT_DIRECT_DISCOUNT_4PLUS,
                    combosEnabled = snap.getBoolean("combosEnabled") ?: DEFAULT_COMBOS_ENABLED,
                    comboEletricaHidraulicaInstalacoes = snap.getDouble("comboEletricaHidraulicaInstalacoes")
                        ?: DEFAULT_COMBO_ELE_HID_INST,
                    comboEletricaHidraulica = snap.getDouble("comboEletricaHidraulica")
                        ?: DEFAULT_COMBO_ELE_HID,
                    comboInstalacoesHidraulica = snap.getDouble("comboInstalacoesHidraulica")
                        ?: DEFAULT_COMBO_INST_HID,
                    comboVeiculos = snap.getDouble("comboVeiculos") ?: DEFAULT_COMBO_VEICULOS
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler config de cashback, usando padrão: ${e.message}")
            CashbackConfig()
        }
    }

    /** Retorna o saldo de cashback disponível do cliente. */
    suspend fun getBalance(clientId: String): Double {
        return try {
            val snap = db.collection(USERS_COLLECTION).document(clientId).get().await()
            snap.getDouble("cashbackBalance") ?: 0.0
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter saldo de cashback: ${e.message}")
            0.0
        }
    }

    /**
     * Resumo completo do cashback do cliente: saldo, acumulados e situação no
     * programa de fidelidade (nível, taxa atual e progresso para o próximo nível).
     */
    suspend fun getSummary(clientId: String): CashbackSummary {
        val config = getConfig()
        return try {
            val snap = db.collection(USERS_COLLECTION).document(clientId).get().await()
            buildSummary(
                config = config,
                balance = snap.getDouble("cashbackBalance") ?: 0.0,
                totalEarned = snap.getDouble("cashbackTotalEarned") ?: 0.0,
                totalSpent = snap.getDouble("cashbackTotalSpent") ?: 0.0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter resumo de cashback: ${e.message}")
            buildSummary(config, 0.0, 0.0, 0.0)
        }
    }

    /** Monta o [CashbackSummary] a partir dos valores brutos + config. */
    private fun buildSummary(
        config: CashbackConfig,
        balance: Double,
        totalEarned: Double,
        totalSpent: Double
    ): CashbackSummary {
        val tier = config.tierFor(totalSpent)
        val next = config.nextTier(tier)
        val toNext = if (next != null) {
            (config.entryThreshold(next) - totalSpent).coerceAtLeast(0.0)
        } else 0.0

        // Progresso dentro da faixa do nível atual (0..1).
        val tierStart = config.entryThreshold(tier)
        val progress = if (next != null) {
            val tierEnd = config.entryThreshold(next)
            if (tierEnd > tierStart) {
                ((totalSpent - tierStart) / (tierEnd - tierStart)).coerceIn(0.0, 1.0)
            } else 1.0
        } else 1.0 // Ouro: nível máximo

        return CashbackSummary(
            balance = balance,
            totalEarned = totalEarned,
            totalSpent = totalSpent,
            tiersEnabled = config.tiersEnabled,
            tier = tier,
            currentRate = config.earnPercentageFor(totalSpent),
            nextTier = next,
            amountToNextTier = round2(toNext),
            progressToNext = progress,
            config = config
        )
    }

    /**
     * Credita o cashback de um pedido concluído, de forma idempotente.
     * Deve ser chamado pelo próprio cliente dono do pedido.
     *
     * @return o valor creditado (0.0 se nada foi creditado).
     */
    suspend fun creditForCompletedOrder(order: OrderData): Double {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null || currentUid != order.clientId) {
            // Só o cliente dono pode creditar no próprio documento.
            return 0.0
        }
        if (order.status != OrderData.STATUS_COMPLETED) {
            return 0.0
        }

        val config = getConfig()
        if (!config.enabled) return 0.0 // cashback desativado pelo painel admin
        // O cashback só é creditado na 2ª Fase (crescimento). Na 1ª Fase
        // (lançamento) o benefício é o desconto direto no checkout.
        if (!config.isGrowthPhase) return 0.0

        val orderValue = order.finalPrice ?: order.estimatedPrice
        if (orderValue <= 0.0) return 0.0

        val userRef = db.collection(USERS_COLLECTION).document(order.clientId)
        // Id determinístico garante que o mesmo pedido nunca credite duas vezes.
        val txRef = userRef.collection(TX_SUBCOLLECTION).document("earn_${order.id}")

        return try {
            val credited = db.runTransaction { tx ->
                val txSnap = tx.get(txRef)
                if (txSnap.exists()) {
                    return@runTransaction 0.0 // já creditado
                }

                val userSnap = tx.get(userRef)
                val currentBalance = userSnap.getDouble("cashbackBalance") ?: 0.0
                val totalSpentBefore = userSnap.getDouble("cashbackTotalSpent") ?: 0.0

                // A taxa do crédito é a do nível atual do cliente (definido pelo
                // total já gasto antes deste pedido). "Quanto mais você usa, mais ganha":
                // este pedido eleva o total gasto e pode subir o nível para os próximos.
                val tier = config.tierFor(totalSpentBefore)
                val rate = config.earnPercentageFor(totalSpentBefore)
                val amount = round2(orderValue * rate / 100.0)
                val newBalance = round2(currentBalance + amount)
                val newTotalSpent = round2(totalSpentBefore + orderValue)

                val tierLabel = if (config.tiersEnabled) "${tier.displayName} ${formatRate(rate)} " else ""

                tx.update(
                    userRef,
                    mapOf(
                        "cashbackBalance" to newBalance,
                        "cashbackTotalEarned" to FieldValue.increment(amount),
                        "cashbackTotalSpent" to newTotalSpent,
                        "updatedAt" to Timestamp.now()
                    )
                )

                tx.set(
                    txRef,
                    mapOf(
                        "id" to txRef.id,
                        "orderId" to order.id,
                        "type" to CashbackTransaction.TYPE_EARN,
                        "amount" to amount,
                        "description" to "Cashback ${tierLabel}do serviço: ${order.serviceName}",
                        "balanceAfter" to newBalance,
                        "createdAt" to Timestamp.now()
                    )
                )
                amount
            }.await()

            if (credited > 0.0) {
                Log.d(TAG, "💸 Cashback de R$ $credited creditado ao cliente ${order.clientId} (pedido ${order.id})")
            }
            credited
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao creditar cashback: ${e.message}")
            0.0
        }
    }

    /**
     * Resgata (usa) cashback do saldo do cliente, p.ex. como desconto num pedido.
     * Não permite saldo negativo. Pronto para integração futura no pagamento.
     *
     * @return Result com o valor efetivamente resgatado.
     */
    suspend fun redeem(clientId: String, amount: Double, orderId: String = ""): Result<Double> {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null || currentUid != clientId) {
            return Result.failure(IllegalStateException("Apenas o próprio cliente pode resgatar cashback"))
        }
        val redeemValue = round2(amount)
        if (redeemValue <= 0.0) {
            return Result.failure(IllegalArgumentException("Valor de resgate inválido"))
        }

        val userRef = db.collection(USERS_COLLECTION).document(clientId)
        val txRef = userRef.collection(TX_SUBCOLLECTION).document()

        return try {
            db.runTransaction { tx ->
                val userSnap = tx.get(userRef)
                val currentBalance = userSnap.getDouble("cashbackBalance") ?: 0.0
                if (redeemValue > currentBalance) {
                    throw IllegalStateException("Saldo de cashback insuficiente")
                }
                val newBalance = round2(currentBalance - redeemValue)

                tx.update(
                    userRef,
                    mapOf(
                        "cashbackBalance" to newBalance,
                        "updatedAt" to Timestamp.now()
                    )
                )

                tx.set(
                    txRef,
                    mapOf(
                        "id" to txRef.id,
                        "orderId" to orderId,
                        "type" to CashbackTransaction.TYPE_REDEEM,
                        "amount" to -redeemValue,
                        "description" to if (orderId.isNotEmpty()) "Resgate no pedido" else "Resgate de cashback",
                        "balanceAfter" to newBalance,
                        "createdAt" to Timestamp.now()
                    )
                )
            }.await()

            Log.d(TAG, "💸 Cashback de R$ $redeemValue resgatado pelo cliente $clientId")
            Result.success(redeemValue)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao resgatar cashback: ${e.message}")
            Result.failure(e)
        }
    }

    /** Retorna o extrato de cashback do cliente (mais recentes primeiro). */
    suspend fun getHistory(clientId: String, limit: Long = 100): List<CashbackTransaction> {
        return try {
            val snap = db.collection(USERS_COLLECTION).document(clientId)
                .collection(TX_SUBCOLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .await()
            snap.documents.mapNotNull { it.toObject(CashbackTransaction::class.java) }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter extrato de cashback: ${e.message}")
            emptyList()
        }
    }
}
