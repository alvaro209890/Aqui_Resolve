package com.aquiresolve.app

import android.content.Context
import android.util.Log
import com.aquiresolve.app.models.CartItemData
import com.aquiresolve.app.models.OrderData
import com.aquiresolve.app.payment.PagarMeManager
import com.aquiresolve.app.payment.PricingResult
import com.aquiresolve.app.utils.ProtocolGenerator
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Gerenciador de carrinho do cliente.
 */
data class PreparedCartCheckout(
    val checkoutCode: String,
    val orderIds: List<String>,
    val cartItemIds: List<String>,
    val orderCount: Int,
    val totalAmount: Double
)

private data class CartItemPricing(
    val estimatedPrice: Double,
    val providerCommission: Double
)

class FirebaseCartManager(private val context: Context? = null) {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val TAG = "FirebaseCartManager"
        private const val CARTS_COLLECTION = "carts"
        private const val ITEMS_COLLECTION = "items"
    }

    private fun resolveItemPrice(item: CartItemData): Double {
        if (item.estimatedPrice > 0) {
            return item.estimatedPrice
        }

        return com.aquiresolve.app.models.ServicePricing.getPrice(
            category = item.serviceNiche,
            serviceType = item.serviceType
        ) ?: com.aquiresolve.app.models.ServicePricing.getDefaultPrice(item.serviceNiche)
    }

    private suspend fun resolveItemPricingForCheckout(item: CartItemData): CartItemPricing {
        val appContext = context
            ?: throw IllegalStateException("Contexto Android indisponível para precificação no backend")

        return when (val result = PagarMeManager(appContext).calculateServicePricing(
            category = item.serviceNiche,
            serviceType = item.serviceType
        )) {
            is PricingResult.Success -> CartItemPricing(
                estimatedPrice = result.estimatedPrice,
                providerCommission = result.providerCommission
            )
            is PricingResult.Error -> throw IllegalStateException(result.message)
        }
    }

    suspend fun addItem(item: CartItemData): Result<String> {
        return try {
            val userId = item.clientId.ifBlank { auth.currentUser?.uid ?: "" }
            if (userId.isBlank()) {
                return Result.failure(Exception("Usuário não autenticado"))
            }

            val cartItemsRef = db.collection(CARTS_COLLECTION)
                .document(userId)
                .collection(ITEMS_COLLECTION)

            val itemId = item.id.ifBlank { cartItemsRef.document().id }
            val payload = item.copy(
                id = itemId,
                clientId = userId,
                updatedAt = Timestamp.now(),
                createdAt = item.createdAt
            )

            cartItemsRef.document(itemId)
                .set(payload)
                .await()

            Result.success(itemId)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao adicionar item no carrinho", e)
            Result.failure(e)
        }
    }

    suspend fun getItems(userId: String): Result<List<CartItemData>> {
        return try {
            val snapshot = db.collection(CARTS_COLLECTION)
                .document(userId)
                .collection(ITEMS_COLLECTION)
                .orderBy("createdAt")
                .get()
                .await()

            val items = snapshot.documents.mapNotNull { doc ->
                doc.toObject(CartItemData::class.java)?.copy(id = doc.id)
            }

            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar itens do carrinho", e)
            Result.failure(e)
        }
    }

    suspend fun removeItem(userId: String, itemId: String): Result<Unit> {
        return try {
            db.collection(CARTS_COLLECTION)
                .document(userId)
                .collection(ITEMS_COLLECTION)
                .document(itemId)
                .delete()
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao remover item do carrinho", e)
            Result.failure(e)
        }
    }

    suspend fun clearCart(userId: String): Result<Unit> {
        return try {
            val snapshot = db.collection(CARTS_COLLECTION)
                .document(userId)
                .collection(ITEMS_COLLECTION)
                .get()
                .await()

            val batch = db.batch()
            snapshot.documents.forEach { doc -> batch.delete(doc.reference) }
            batch.commit().await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar carrinho", e)
            Result.failure(e)
        }
    }

    /**
     * Mantido apenas para compatibilidade. A confirmação real de pagamento/status do carrinho
     * agora é feita pelo backend, via criação da transação, polling seguro e webhook Pagar.me.
     */
    @Deprecated("Use backend payment sync; client must not write paymentStatus/status after checkout.")
    suspend fun checkoutCart(
        userId: String,
        orderIds: List<String>,
        cartItemIds: List<String>,
        transactionId: String,
        paymentStatus: String
    ): Result<List<String>> {
        return if (orderIds.isEmpty()) {
            Result.failure(Exception("Nenhum pedido pendente encontrado para finalizar"))
        } else {
            Result.success(orderIds)
        }
    }

    suspend fun prepareCheckout(
        userId: String,
        clientName: String,
        clientEmail: String,
        checkoutCode: String,
        clientPhone: String? = null,
        discountPercent: Double = 0.0
    ): Result<PreparedCartCheckout> {
        return try {
            val cartSnapshot = db.collection(CARTS_COLLECTION)
                .document(userId)
                .collection(ITEMS_COLLECTION)
                .get()
                .await()

            if (cartSnapshot.isEmpty) {
                return Result.failure(Exception("Carrinho vazio"))
            }

            val items = cartSnapshot.documents.mapNotNull { doc ->
                doc.toObject(CartItemData::class.java)?.copy(id = doc.id)
            }

            if (items.isEmpty()) {
                return Result.failure(Exception("Carrinho vazio"))
            }

            val now = Timestamp.now()
            val batch = db.batch()
            val createdOrderIds = mutableListOf<String>()
            val cartItemIds = items.map { it.id }
            val pricingByItemId = items.associate { item ->
                item.id to resolveItemPricingForCheckout(item)
            }
            val totalAmount = pricingByItemId.values.sumOf { it.estimatedPrice }

            if (totalAmount <= 0) {
                return Result.failure(Exception("Carrinho com valor inválido para pagamento"))
            }

            // Fator de desconto do programa (desconto direto / combos). O valor do
            // prestador (providerCommission) NÃO é alterado: o desconto é custeado
            // pela plataforma. O cliente paga `finalPrice` (preço já com desconto).
            val pct = discountPercent.coerceIn(0.0, 100.0)
            val factor = 1.0 - pct / 100.0
            var chargedTotal = 0.0

            items.forEach { item ->
                val orderRef = db.collection("orders").document()
                createdOrderIds.add(orderRef.id)
                val pricing = pricingByItemId[item.id]
                    ?: throw IllegalStateException("Preço do item não calculado")
                val effectivePrice = pricing.estimatedPrice
                val providerCommission = pricing.providerCommission
                val finalPrice = Math.round(effectivePrice * factor * 100.0) / 100.0
                chargedTotal += finalPrice

                val orderData = mutableMapOf<String, Any>(
                    "clientId" to userId,
                    "clientName" to clientName,
                    "clientEmail" to clientEmail,
                    "protocol" to ProtocolGenerator.generateProtocol(),
                    "serviceType" to item.serviceType,
                    "serviceName" to item.serviceNiche,
                    "description" to item.description,
                    "address" to item.address,
                    "zipCode" to item.zipCode,
                    "complement" to item.complement,
                    "city" to item.city,
                    "state" to item.state,
                    "status" to OrderData.STATUS_AWAITING_PAYMENT,
                    "paymentStatus" to OrderData.STATUS_AWAITING_PAYMENT,
                    "estimatedPrice" to effectivePrice,
                    "finalPrice" to finalPrice,
                    "cartDiscountPercent" to pct,
                    "providerCommission" to providerCommission,
                    "images" to item.imageUrls,
                    "cartCheckoutCode" to checkoutCode,
                    "cartItemId" to item.id,
                    "createdAt" to now,
                    "updatedAt" to now
                )

                clientPhone?.takeIf { it.isNotBlank() }?.let { phone ->
                    orderData["clientPhone"] = phone
                }
                item.preferredDate?.let { orderData["scheduledDate"] = it }
                item.preferredTime?.let { orderData["preferredTimeSlot"] = it }
                item.coordinates?.let { orderData["coordinates"] = it }

                batch.set(orderRef, orderData)
            }

            batch.commit().await()
            Result.success(
                PreparedCartCheckout(
                    checkoutCode = checkoutCode,
                    orderIds = createdOrderIds,
                    cartItemIds = cartItemIds,
                    orderCount = items.size,
                    totalAmount = Math.round(chargedTotal * 100.0) / 100.0
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao preparar checkout do carrinho", e)
            Result.failure(e)
        }
    }

    suspend fun cancelPreparedCheckout(orderIds: List<String>): Result<Unit> {
        return try {
            if (orderIds.isEmpty()) {
                return Result.success(Unit)
            }

            val batch = db.batch()
            orderIds.forEach { orderId ->
                batch.delete(db.collection("orders").document(orderId))
            }
            batch.commit().await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao cancelar checkout preparado", e)
            Result.failure(e)
        }
    }
}
