package com.aquiresolve.app.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

/**
 * Lançamento (extrato) de cashback do cliente.
 *
 * Armazenado em `users/{clientId}/cashback_transactions/{txId}`.
 * Para créditos de pedido, o id do documento é `earn_{orderId}` para
 * garantir idempotência (o mesmo pedido nunca credita duas vezes).
 */
data class CashbackTransaction(
    @PropertyName("id")
    val id: String = "",
    @PropertyName("orderId")
    val orderId: String = "",
    @PropertyName("type")
    val type: String = "", // "earn" (crédito) | "redeem" (resgate/uso)
    @PropertyName("amount")
    val amount: Double = 0.0, // positivo no crédito, negativo no resgate
    @PropertyName("description")
    val description: String = "",
    @PropertyName("balanceAfter")
    val balanceAfter: Double = 0.0,
    @PropertyName("createdAt")
    val createdAt: Timestamp = Timestamp.now()
) {
    companion object {
        const val TYPE_EARN = "earn"
        const val TYPE_REDEEM = "redeem"
    }
}
