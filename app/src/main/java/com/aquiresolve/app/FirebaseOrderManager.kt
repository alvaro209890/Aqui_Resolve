package com.aquiresolve.app

import android.util.Log
import com.aquiresolve.app.api.PagarMeApiService
import com.aquiresolve.app.models.OrderData
import com.aquiresolve.app.utils.ProtocolGenerator
import com.aquiresolve.app.utils.VerificationCodeGenerator
import com.aquiresolve.app.utils.awaitCurrentUser
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.*
import java.util.concurrent.TimeUnit

class FirebaseOrderManager {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val settlementApi: PagarMeApiService by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BuildConfig.PAYMENTS_API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PagarMeApiService::class.java)
    }
    
    companion object {
        private const val TAG = "FirebaseOrderManager"
        private const val ORDERS_COLLECTION = "orders"
    }

    data class DetailedRatings(
        val qualityRating: Int? = null,
        val punctualityRating: Int? = null,
        val communicationRating: Int? = null,
        val cleanlinessRating: Int? = null
    )
    
    /**
     * Cria um novo pedido no Firebase
     */
    suspend fun createOrder(orderData: OrderData): Result<String> {
        return try {
            val user = auth.awaitCurrentUser()
            if (user == null) {
                return Result.failure(Exception("Usuário não autenticado"))
            }
            
            // Gerar ID único para o pedido
            val orderId = db.collection(ORDERS_COLLECTION).document().id
            
            // Gerar protocolo único
            val protocol = ProtocolGenerator.generateProtocol()
            
            // Monta um payload ENXUTO e compatível com a regra `validOrderCreate`
            // (pay-before-distribution): grava SOMENTE os campos permitidos, com
            // status/paymentStatus = awaiting_payment. Usar `orderData.copy(...).set()`
            // serializava o OrderData inteiro (id, priority, status=distributing,
            // distributionStartedAt, adminNotes, flags de conclusão...), que a regra
            // NEGA com PERMISSION_DENIED.
            val now = Timestamp.now()
            val order = mutableMapOf<String, Any>(
                "clientId" to user.uid,
                "clientName" to orderData.clientName,
                "clientEmail" to orderData.clientEmail,
                "protocol" to protocol,
                "serviceType" to orderData.serviceType,
                "serviceName" to orderData.serviceName,
                "description" to orderData.description,
                "address" to orderData.address,
                "zipCode" to orderData.zipCode,
                "city" to orderData.city,
                "state" to orderData.state,
                "status" to OrderData.STATUS_AWAITING_PAYMENT,
                "paymentStatus" to OrderData.STATUS_AWAITING_PAYMENT,
                "estimatedPrice" to orderData.estimatedPrice,
                "providerCommission" to orderData.providerCommission,
                "createdAt" to now,
                "updatedAt" to now
            )
            // Opcionais — todos na allowlist (`hasOnly`) da regra; só grava se houver valor.
            orderData.clientPhone.takeIf { it.isNotBlank() }?.let { order["clientPhone"] = it }
            orderData.complement?.takeIf { it.isNotBlank() }?.let { order["complement"] = it }
            orderData.coordinates?.let { order["coordinates"] = it }
            orderData.scheduledDate?.let { order["scheduledDate"] = it }
            orderData.preferredTimeSlot.takeIf { it.isNotBlank() }?.let { order["preferredTimeSlot"] = it }
            orderData.finalPrice?.let { order["finalPrice"] = it }
            if (orderData.images.isNotEmpty()) order["images"] = orderData.images

            // Salvar no Firestore
            db.collection(ORDERS_COLLECTION)
                .document(orderId)
                .set(order)
                .await()
            
            Log.d(TAG, "Pedido criado com sucesso: $orderId | Protocolo: $protocol")
            Result.success(orderId)
            
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar pedido: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Busca pedidos do usuário logado
     */
    suspend fun getUserOrders(): Result<List<OrderData>> {
        return try {
            val user = auth.awaitCurrentUser()
            if (user == null) {
                return Result.failure(Exception("Usuário não autenticado"))
            }
            
            // Buscar pedidos (SEM orderBy para evitar índice composto)
            val snapshot = db.collection(ORDERS_COLLECTION)
                .whereEqualTo("clientId", user.uid)
                .get()
                .await()
            
            val orders = snapshot.documents.mapNotNull { doc ->
                doc.toObject(OrderData::class.java)?.copy(id = doc.id)
            }.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L } // Ordenar manualmente
            
            Log.d(TAG, "Pedidos carregados: ${orders.size}")
            Result.success(orders)
            
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar pedidos: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Busca um pedido específico por ID
     */
    suspend fun getOrderById(orderId: String): Result<OrderData?> {
        return try {
            val snapshot = db.collection(ORDERS_COLLECTION)
                .document(orderId)
                .get()
                .await()
            
            val order = snapshot.toObject(OrderData::class.java)?.copy(id = snapshot.id)
            Result.success(order)
            
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar pedido: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Atualiza o status de um pedido
     */
    suspend fun updateOrderStatus(orderId: String, newStatus: String): Result<Unit> {
        return try {
            val updates = mapOf(
                "status" to newStatus,
                "updatedAt" to Timestamp.now()
            )
            
            db.collection(ORDERS_COLLECTION)
                .document(orderId)
                .update(updates)
                .await()
            
            Log.d(TAG, "Status do pedido atualizado: $orderId -> $newStatus")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao atualizar status: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Marca serviço como iniciado (prestador ou cliente podem acionar; status vai para in_progress)
     */
    suspend fun startService(orderId: String): Result<Unit> {
        return try {
            // Validar que o pedido existe e está em estado compatível
            val orderDoc = db.collection(ORDERS_COLLECTION).document(orderId).get().await()
            if (!orderDoc.exists()) {
                return Result.failure(Exception("Pedido não encontrado"))
            }
            val currentStatus = orderDoc.getString("status") ?: ""
            if (currentStatus == OrderData.STATUS_IN_PROGRESS) {
                Log.d(TAG, "Serviço já estava iniciado: $orderId")
                return Result.success(Unit)
            }
            val allowedStatuses = setOf(OrderData.STATUS_ASSIGNED)
            if (currentStatus !in allowedStatuses) {
                Log.w(TAG, "startService bloqueado: pedido $orderId está em status '$currentStatus'")
                return Result.failure(Exception("Pedido não está em estado que permita iniciar serviço"))
            }

            val updates = mapOf(
                "status" to OrderData.STATUS_IN_PROGRESS,
                "startedAt" to Timestamp.now(),
                "updatedAt" to Timestamp.now()
            )

            db.collection(ORDERS_COLLECTION)
                .document(orderId)
                .update(updates)
                .await()

            Log.d(TAG, "Serviço iniciado: $orderId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar serviço: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Gera códigos de verificação quando o prestador aceita um pedido
     */
    suspend fun generateVerificationCodes(orderId: String): Result<Pair<String, String>> {
        return try {
            val clientCode = VerificationCodeGenerator.generateCode()
            val providerCode = VerificationCodeGenerator.generateCode()
            
            val updates = mapOf(
                "clientVerificationCode" to clientCode,
                "providerVerificationCode" to providerCode,
                "verificationCodesGeneratedAt" to Timestamp.now(),
                "updatedAt" to Timestamp.now()
            )
            
            db.collection(ORDERS_COLLECTION)
                .document(orderId)
                .update(updates)
                .await()
            
            Log.d(TAG, "Códigos de verificação gerados para pedido: $orderId")
            
            Result.success(Pair(clientCode, providerCode))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao gerar códigos de verificação: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Aceita um pedido disponível em uma única transação.
     * Centraliza a regra para evitar dois prestadores aceitando o mesmo pedido.
     */
    suspend fun acceptOrderAsProvider(orderId: String): Result<Unit> {
        return try {
            if (orderId.isBlank()) {
                return Result.failure(IllegalArgumentException("ID do pedido inválido"))
            }

            val currentUser = auth.awaitCurrentUser()
                ?: return Result.failure(IllegalStateException("Usuário não autenticado"))

            val providerDoc = db.collection("providers").document(currentUser.uid).get().await()
            val providerName = if (providerDoc.exists()) {
                providerDoc.getString("fullName") ?: currentUser.displayName ?: "Prestador"
            } else {
                currentUser.displayName ?: "Prestador"
            }

            val clientCode = VerificationCodeGenerator.generateCode()
            val providerCode = VerificationCodeGenerator.generateCode()
            val orderRef = db.collection(ORDERS_COLLECTION).document(orderId)
            val now = Timestamp.now()

            db.runTransaction { tx ->
                val snap = tx.get(orderRef)
                if (!snap.exists()) {
                    throw IllegalStateException("Pedido não encontrado")
                }

                val currentStatus = snap.getString("status") ?: OrderData.STATUS_DISTRIBUTING
                val assigned = snap.getString("assignedProvider")
                val allowedStatuses = setOf(OrderData.STATUS_DISTRIBUTING, OrderData.STATUS_PENDING, "available")

                if (currentStatus !in allowedStatuses || !assigned.isNullOrBlank()) {
                    throw IllegalStateException("Pedido indisponível")
                }

                tx.update(orderRef, mapOf(
                    "assignedProvider" to currentUser.uid,
                    "assignedProviderName" to providerName,
                    "status" to OrderData.STATUS_ASSIGNED,
                    "assignedAt" to now,
                    "clientVerificationCode" to clientCode,
                    "providerVerificationCode" to providerCode,
                    "verificationCodesGeneratedAt" to now,
                    "updatedAt" to now
                ))
            }.await()

            Log.d(TAG, "Pedido aceito com sucesso: $orderId pelo prestador ${currentUser.uid}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao aceitar pedido: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Finaliza um pedido verificando o código do cliente
     * O prestador deve fornecer o código do cliente para finalizar
     */
    suspend fun completeOrderWithVerification(orderId: String, clientCode: String): Result<Unit> {
        return try {
            val docRef = db.collection(ORDERS_COLLECTION).document(orderId)
            
            db.runTransaction { tx ->
                // TODAS AS LEITURAS PRIMEIRO
                val snap = tx.get(docRef)
                
                // Verificar se o pedido existe e está em andamento
                val status = snap.getString("status") ?: ""
                if (status != OrderData.STATUS_IN_PROGRESS && status != OrderData.STATUS_ASSIGNED) {
                    throw IllegalStateException("Pedido não está em andamento")
                }
                
                // Verificar o código do cliente
                val storedClientCode = snap.getString("clientVerificationCode")
                if (storedClientCode == null) {
                    throw IllegalStateException("Código de verificação não encontrado")
                }
                
                val cleanedCode = VerificationCodeGenerator.cleanCode(clientCode)
                if (cleanedCode != storedClientCode) {
                    throw IllegalArgumentException("Código de verificação incorreto")
                }

                // Código correto! Finalizar o pedido
                val updates = mapOf(
                    "status" to OrderData.STATUS_COMPLETED,
                    "completedAt" to Timestamp.now(),
                    "providerCompletionConfirmed" to true,
                    "clientCompletionConfirmed" to true,
                    "settlementStatus" to "pending",
                    "settlementRequestedAt" to Timestamp.now(),
                    "updatedAt" to Timestamp.now()
                )
                
                tx.update(docRef, updates)
            }.await()

            settleCompletedOrderOnBackend(orderId)
            
            Log.d(TAG, "✅ Pedido finalizado com sucesso: $orderId")
            Result.success(Unit)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Erro ao finalizar pedido: ${e.message}")
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Código de verificação incorreto: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao finalizar pedido: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Confirma a conclusão pelo ator ("client" ou "provider"). Fecha o pedido quando ambos confirmarem
     * (Método antigo mantido para compatibilidade)
     */
    suspend fun confirmCompletion(orderId: String, actor: String): Result<Unit> {
        return try {
            val docRef = db.collection(ORDERS_COLLECTION).document(orderId)
            db.runTransaction { tx ->
                // TODAS AS LEITURAS PRIMEIRO
                val snap = tx.get(docRef)
                val clientConfirmed = snap.getBoolean("clientCompletionConfirmed") ?: false
                val providerConfirmed = snap.getBoolean("providerCompletionConfirmed") ?: false

                val newClient = if (actor == "client") true else clientConfirmed
                val newProvider = if (actor == "provider") true else providerConfirmed

                val updates = mutableMapOf<String, Any>(
                    "updatedAt" to Timestamp.now()
                )

                if (actor == "client") {
                    updates["clientCompletionConfirmed"] = true
                } else if (actor == "provider") {
                    updates["providerCompletionConfirmed"] = true
                }

                if (newClient && newProvider) {
                    updates["status"] = OrderData.STATUS_COMPLETED
                    updates["completedAt"] = Timestamp.now()
                    updates["settlementStatus"] = "pending"
                    updates["settlementRequestedAt"] = Timestamp.now()
                }

                tx.update(docRef, updates)
            }.await()

            val updatedOrder = db.collection(ORDERS_COLLECTION).document(orderId).get().await()
            if (updatedOrder.getString("status") == OrderData.STATUS_COMPLETED) {
                settleCompletedOrderOnBackend(orderId)
            }

            Log.d(TAG, "Confirmação registrada ($actor): $orderId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Erro na confirmação de conclusão: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun settleCompletedOrder(orderId: String): Result<Unit> {
        return try {
            if (settleCompletedOrderOnBackend(orderId)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Liquidação financeira pendente"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Submete avaliação de pedido concluído com validação de regras de negócio.
     *
     * Regras:
     * - Apenas cliente dono do pedido pode avaliar.
     * - Pedido precisa estar concluído.
     * - Apenas uma avaliação por pedido (imutável).
     * - Atualiza média pública do prestador com base em pedidos reais avaliados.
     */
    suspend fun submitOrderRating(
        orderId: String,
        rating: Int,
        review: String? = null,
        detailedRatings: DetailedRatings = DetailedRatings()
    ): Result<Unit> {
        if (orderId.isBlank()) {
            return Result.failure(IllegalArgumentException("ID do pedido inválido"))
        }
        if (rating !in 1..5) {
            return Result.failure(IllegalArgumentException("A nota geral deve estar entre 1 e 5 estrelas"))
        }

        val detailedValues = listOf(
            detailedRatings.qualityRating,
            detailedRatings.punctualityRating,
            detailedRatings.communicationRating,
            detailedRatings.cleanlinessRating
        )
        if (detailedValues.any { it != null && it !in 1..5 }) {
            return Result.failure(IllegalArgumentException("As notas detalhadas devem estar entre 1 e 5 estrelas"))
        }

        return try {
            val user = auth.awaitCurrentUser()
                ?: return Result.failure(IllegalStateException("Usuário não autenticado"))

            val orderRef = db.collection(ORDERS_COLLECTION).document(orderId)
            var providerIdToUpdate: String? = null

            db.runTransaction { transaction ->
                val orderDoc = transaction.get(orderRef)
                if (!orderDoc.exists()) {
                    throw IllegalStateException("Pedido não encontrado")
                }

                val clientId = orderDoc.getString("clientId")
                if (clientId != user.uid) {
                    throw SecurityException("Somente o cliente dono do pedido pode avaliar")
                }

                val status = orderDoc.getString("status")
                if (status != OrderData.STATUS_COMPLETED) {
                    throw IllegalStateException("Somente pedidos concluídos podem ser avaliados")
                }

                val existingRating = orderDoc.getLong("rating")?.toInt()
                    ?: orderDoc.getDouble("rating")?.toInt()
                if (existingRating != null && existingRating > 0) {
                    throw IllegalStateException("Este pedido já foi avaliado")
                }

                providerIdToUpdate = orderDoc.getString("assignedProvider")

                val updates = mutableMapOf<String, Any>(
                    "rating" to rating,
                    "reviewedAt" to Timestamp.now(),
                    "updatedAt" to Timestamp.now()
                )

                val normalizedReview = review?.trim()
                if (!normalizedReview.isNullOrEmpty()) {
                    updates["review"] = normalizedReview
                }

                detailedRatings.qualityRating?.let { updates["qualityRating"] = it }
                detailedRatings.punctualityRating?.let { updates["punctualityRating"] = it }
                detailedRatings.communicationRating?.let { updates["communicationRating"] = it }
                detailedRatings.cleanlinessRating?.let { updates["cleanlinessRating"] = it }

                transaction.update(orderRef, updates)
            }.await()

            // Recalcula a nota pública usando os pedidos reais avaliados do prestador.
            providerIdToUpdate?.takeIf { it.isNotBlank() }?.let { providerId ->
                updateProviderAverageRatingFromOrders(providerId)
            }

            Log.d(TAG, "Pedido avaliado com sucesso: $orderId -> $rating estrelas")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "Avaliação não autorizada: ${e.message}")
            Result.failure(e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Erro de regra ao avaliar pedido: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao avaliar pedido: ${e.message}")
            Result.failure(e)
        }
    }

    @Deprecated(
        message = "Use submitOrderRating para validação completa e persistência de notas detalhadas",
        replaceWith = ReplaceWith("submitOrderRating(orderId, rating, review)")
    )
    suspend fun rateOrder(orderId: String, rating: Int, review: String? = null): Result<Unit> {
        return submitOrderRating(orderId, rating, review)
    }

    private suspend fun updateProviderAverageRatingFromOrders(providerId: String) {
        val ratedOrders = db.collection(ORDERS_COLLECTION)
            .whereEqualTo("assignedProvider", providerId)
            .get()
            .await()

        var totalRating = 0.0
        var totalRatings = 0

        for (doc in ratedOrders.documents) {
            val ratingValue = doc.getLong("rating")?.toInt()
                ?: doc.getDouble("rating")?.toInt()
                ?: continue
            if (ratingValue in 1..5) {
                totalRating += ratingValue
                totalRatings++
            }
        }

        val average = if (totalRatings > 0) totalRating / totalRatings else 0.0

        db.collection("providers")
            .document(providerId)
            .set(
                mapOf(
                    "rating" to average,
                    "totalRatings" to totalRatings,
                    "updatedAt" to Date()
                ),
                SetOptions.merge()
            )
            .await()
    }

    private suspend fun settleCompletedOrderOnBackend(orderId: String): Boolean {
        try {
            val authHeader = getAuthorizationHeader()
            val response = settlementApi.settleCompletedOrder(authHeader, orderId)
            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "Liquidação financeira confirmada para pedido $orderId")
                return true
            }
            Log.w(TAG, "Liquidação pendente para pedido $orderId: HTTP ${response.code()}")
        } catch (e: Exception) {
            Log.w(TAG, "Liquidação pendente para pedido $orderId: ${e.message}")
        }
        return false
    }

    private suspend fun getAuthorizationHeader(): String {
        val currentUser = auth.awaitCurrentUser()
            ?: throw IllegalStateException("Usuário não autenticado")

        val cachedToken = currentUser.getIdToken(false).await().token
        if (!cachedToken.isNullOrBlank()) {
            return "Bearer $cachedToken"
        }

        val freshToken = currentUser.getIdToken(true).await().token
            ?: throw IllegalStateException("Não foi possível obter token de autenticação")
        return "Bearer $freshToken"
    }
    
    /**
     * Listener em tempo real para mudanças nos pedidos
     */
    fun listenToUserOrders(onOrdersChanged: (List<OrderData>) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            Log.e(TAG, "Usuário não autenticado")
            return
        }
        
        db.collection(ORDERS_COLLECTION)
            .whereEqualTo("clientId", user.uid)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Erro no listener: ${error.message}")
                    return@addSnapshotListener
                }
                
                val orders = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(OrderData::class.java)
                } ?: emptyList()
                
                onOrdersChanged(orders)
            }
    }
    
    /**
     * Listener em tempo real para um pedido específico
     */
    fun listenToOrder(orderId: String, onOrderChanged: (OrderData?) -> Unit) {
        db.collection(ORDERS_COLLECTION)
            .document(orderId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Erro no listener do pedido: ${error.message}")
                    return@addSnapshotListener
                }
                
                val order = snapshot?.toObject(OrderData::class.java)
                onOrderChanged(order)
            }
    }
    
    /**
     * Cria um pedido com dados básicos (método helper)
     */
    suspend fun createSimpleOrder(
        serviceType: String,
        serviceName: String,
        description: String,
        address: String,
        city: String,
        state: String,
        zipCode: String,
        scheduledDate: Date? = null,
        priority: String = OrderData.PRIORITY_NORMAL,
        estimatedPrice: Double = 0.0
    ): Result<String> {
        val user = auth.awaitCurrentUser()
        if (user == null) {
            return Result.failure(Exception("Usuário não autenticado"))
        }
        
        val orderData = OrderData(
            serviceType = serviceType,
            serviceName = serviceName,
            description = description,
            priority = priority,
            address = address,
            city = city,
            state = state,
            zipCode = zipCode,
            scheduledDate = scheduledDate?.let { Timestamp(it) },
            estimatedPrice = estimatedPrice
        )
        
        return createOrder(orderData)
    }
    
    /**
     * Cancela um pedido com informações detalhadas
     */
    suspend fun cancelOrder(orderId: String, cancelledBy: String, reason: String = ""): Result<Unit> {
        return try {
            // Validar orderId
            if (orderId.isBlank()) {
                Log.e(TAG, "OrderId está vazio")
                return Result.failure(Exception("ID do pedido inválido"))
            }
            
            Log.d(TAG, "Tentando cancelar pedido: $orderId")
            
            val orderRef = db.collection(ORDERS_COLLECTION).document(orderId)
            
            // Verificar se o documento existe antes de atualizar
            val documentSnapshot = orderRef.get().await()
            if (!documentSnapshot.exists()) {
                Log.e(TAG, "Documento não encontrado: $orderId")
                return Result.failure(Exception("Pedido não encontrado"))
            }
            
            val updates = mutableMapOf<String, Any>(
                "status" to OrderData.STATUS_CANCELLED,
                "cancelledAt" to Timestamp.now(),
                "cancelledBy" to cancelledBy,
                "cancellationReason" to reason,
                "updatedAt" to Timestamp.now()
            )

            // Só sinaliza reembolso se o pedido REALMENTE foi pago. Cancelar um pedido
            // ainda em 'awaiting_payment' (nada cobrado) não deve criar pendência de
            // reembolso nem prometer estorno ao cliente.
            val paymentStatus = (documentSnapshot.getString("paymentStatus") ?: "").lowercase()
            val wasPaid = paymentStatus in setOf("paid", "captured", "approved", "confirmed")
            if (cancelledBy == "client" && wasPaid) {
                updates["refundStatus"] = "pending"
                updates["refundRequestedAt"] = Timestamp.now()
            }
            
            orderRef.update(updates).await()
            
            Log.d(TAG, "Pedido cancelado com sucesso: $orderId por $cancelledBy")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao cancelar pedido: ${e.message}")
            Log.e(TAG, "OrderId: $orderId")
            Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            Result.failure(e)
        }
    }
    
    /**
     * Busca pedidos filtrados por status
     */
    suspend fun getOrdersByStatus(status: String? = null): Result<List<OrderData>> {
        return try {
            val user = auth.awaitCurrentUser()
            if (user == null) {
                return Result.failure(Exception("Usuário não autenticado"))
            }
            
            var query = db.collection(ORDERS_COLLECTION)
                .whereEqualTo("clientId", user.uid)
                .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            
            if (status != null && status != "all") {
                query = query.whereEqualTo("status", status)
            }
            
            val snapshot = query.get().await()
            
            val orders = snapshot.documents.mapNotNull { doc ->
                doc.toObject(OrderData::class.java)?.copy(id = doc.id)
            }
            
            Log.d(TAG, "Pedidos filtrados carregados: ${orders.size} (status: $status)")
            Result.success(orders)
            
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar pedidos filtrados: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Conta quantos pedidos um prestador completou
     */
    suspend fun countCompletedOrdersByProvider(providerId: String): Result<Int> {
        return try {
            val snapshot = db.collection(ORDERS_COLLECTION)
                .whereEqualTo("assignedProvider", providerId)
                .whereEqualTo("status", OrderData.STATUS_COMPLETED)
                .get()
                .await()
            
            val count = snapshot.size()
            Log.d(TAG, "Pedidos completados pelo prestador $providerId: $count")
            Result.success(count)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao contar pedidos completados: ${e.message}")
            Result.failure(e)
        }
    }
} 
