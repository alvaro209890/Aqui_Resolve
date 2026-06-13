package com.aquiresolve.app

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.tasks.await

class FirebaseChatManager {
    
    private val firestore: FirebaseFirestore = FirebaseConfig.getFirestore()
    
    data class ChatMessage(
        val id: String = "",
        val orderId: String = "",
        val senderId: String = "",
        val senderName: String = "",
        val senderType: String = "", // "client" or "provider"
        val message: String = "",
        val timestamp: com.google.firebase.Timestamp = com.google.firebase.Timestamp.now(),
        val isRead: Boolean = false,
        val imageUrl: String? = null,
        val documentUrl: String? = null
    )
    
    data class ChatRoom(
        val id: String = "",
        val orderId: String = "",
        val clientId: String = "",
        val clientName: String = "",
        val providerId: String = "",
        val providerName: String = "",
        val lastMessage: String = "",
        val lastMessageTime: com.google.firebase.Timestamp = com.google.firebase.Timestamp.now(),
        val unreadCount: Int = 0
    )
    
    suspend fun sendMessage(message: ChatMessage): Result<String> {
        return try {
            val messageMap = hashMapOf(
                "orderId" to message.orderId,
                "senderId" to message.senderId,
                "senderName" to message.senderName,
                "senderType" to message.senderType,
                "message" to message.message,
                "timestamp" to message.timestamp,
                "isRead" to message.isRead,
                "imageUrl" to message.imageUrl,
                "documentUrl" to message.documentUrl
            )

            // Usar subcoleção por pedido para evitar necessidade de índice composto
            val docRef = firestore
                .collection("orders")
                .document(message.orderId)
                .collection("messages")
                .add(messageMap)
                .await()
            
            // Atualizar a sala de chat
            updateChatRoom(message.orderId, message)

            // Atualizar/criar a conversa para o painel admin (Central Operacional)
            upsertChatConversation(message.orderId, message)

            Result.success(docRef.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getMessagesForOrder(orderId: String): Result<List<ChatMessage>> {
        return try {
            val query = firestore
                .collection("orders")
                .document(orderId)
                .collection("messages")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()
            
            val messages = query.documents.mapNotNull { doc ->
                val orderIdDoc = doc.getString("orderId") ?: orderId
                val senderId = doc.getString("senderId") ?: ""
                val senderName = doc.getString("senderName") ?: ""
                val senderType = doc.getString("senderType") ?: ""
                val message = doc.getString("message") ?: ""
                val timestamp = doc.getTimestamp("timestamp") ?: com.google.firebase.Timestamp.now()
                val isRead = doc.getBoolean("isRead") ?: false
                val imageUrl = doc.getString("imageUrl")
                val documentUrl = doc.getString("documentUrl")
                ChatMessage(
                    id = doc.id,
                    orderId = orderIdDoc,
                    senderId = senderId,
                    senderName = senderName,
                    senderType = senderType,
                    message = message,
                    timestamp = timestamp,
                    isRead = isRead,
                    imageUrl = imageUrl,
                    documentUrl = documentUrl
                )
            }
            Result.success(messages)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getMessagesFlow(orderId: String): Flow<List<ChatMessage>> = callbackFlow {
        val registration = firestore
            .collection("orders")
            .document(orderId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val messages = snapshot?.documents?.mapNotNull { doc ->
                    val orderIdDoc = doc.getString("orderId") ?: orderId
                    val senderId = doc.getString("senderId") ?: ""
                    val senderName = doc.getString("senderName") ?: ""
                    val senderType = doc.getString("senderType") ?: ""
                    val message = doc.getString("message") ?: ""
                    val timestamp = doc.getTimestamp("timestamp") ?: com.google.firebase.Timestamp.now()
                    val isRead = doc.getBoolean("isRead") ?: false
                    val imageUrl = doc.getString("imageUrl")
                    val documentUrl = doc.getString("documentUrl")
                    ChatMessage(
                        id = doc.id,
                        orderId = orderIdDoc,
                        senderId = senderId,
                        senderName = senderName,
                        senderType = senderType,
                        message = message,
                        timestamp = timestamp,
                        isRead = isRead,
                        imageUrl = imageUrl,
                        documentUrl = documentUrl
                    )
                } ?: emptyList()
                trySend(messages).isSuccess
            }
        awaitClose { registration.remove() }
    }
    
    suspend fun getChatRoomsForUser(userId: String): Result<List<ChatRoom>> {
        return try {
            val query = firestore.collection("chatRooms")
                .whereEqualTo("clientId", userId)
                .orderBy("lastMessageTime", Query.Direction.DESCENDING)
                .get()
                .await()
            
            val chatRooms = query.documents.mapNotNull { doc ->
                doc.toObject(ChatRoom::class.java)?.copy(id = doc.id)
            }
            Result.success(chatRooms)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getChatRoomsForProvider(providerId: String): Result<List<ChatRoom>> {
        return try {
            val query = firestore.collection("chatRooms")
                .whereEqualTo("providerId", providerId)
                .orderBy("lastMessageTime", Query.Direction.DESCENDING)
                .get()
                .await()
            
            val chatRooms = query.documents.mapNotNull { doc ->
                doc.toObject(ChatRoom::class.java)?.copy(id = doc.id)
            }
            Result.success(chatRooms)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun markMessagesAsRead(orderId: String, userId: String): Result<Unit> {
        return try {
            val query = firestore
                .collection("orders")
                .document(orderId)
                .collection("messages")
                .whereEqualTo("isRead", false)
                .get()
                .await()
            
            val batch = firestore.batch()
            for (document in query.documents) {
                val senderId = document.getString("senderId")
                if (senderId != userId) {
                    batch.update(document.reference, "isRead", true)
                }
            }
            batch.commit().await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun createChatRoom(chatRoom: ChatRoom): Result<String> {
        return try {
            val chatRoomMap = hashMapOf(
                "orderId" to chatRoom.orderId,
                "clientId" to chatRoom.clientId,
                "clientName" to chatRoom.clientName,
                "providerId" to chatRoom.providerId,
                "providerName" to chatRoom.providerName,
                "lastMessage" to chatRoom.lastMessage,
                "lastMessageTime" to chatRoom.lastMessageTime,
                "unreadCount" to chatRoom.unreadCount
            )
            
            val docRef = firestore.collection("chatRooms").add(chatRoomMap).await()
            Result.success(docRef.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private suspend fun updateChatRoom(orderId: String, message: ChatMessage) {
        try {
            val chatRoomQuery = firestore.collection("chatRooms")
                .whereEqualTo("orderId", orderId)
                .get()
                .await()
            
            if (chatRoomQuery.documents.isNotEmpty()) {
                val chatRoomDoc = chatRoomQuery.documents.first()
                chatRoomDoc.reference.update(
                    mapOf(
                        "lastMessage" to message.message,
                        "lastMessageTime" to message.timestamp,
                        // unreadCount será administrado por cliente/prestador ao ler
                        "unreadCount" to (chatRoomDoc.getLong("unreadCount") ?: 0)
                    )
                ).await()
            }
        } catch (e: Exception) {
            // Log error but don't fail the message send
            e.printStackTrace()
        }
    }
    
    /**
     * Cria/atualiza `chatConversations/{orderId}` para alimentar a Central Operacional do painel admin.
     * Na primeira mensagem resolve as identidades pelo pedido (orders/{orderId}); nas demais só atualiza
     * a última mensagem (merge), sem sobrescrever status/priority/notas definidos pelo admin.
     */
    private suspend fun upsertChatConversation(orderId: String, message: ChatMessage) {
        try {
            val messageType = when {
                !message.imageUrl.isNullOrEmpty() -> "image"
                !message.documentUrl.isNullOrEmpty() -> "file"
                else -> "text"
            }
            val lastMessage = mapOf(
                "content" to message.message,
                "senderName" to message.senderName,
                "senderId" to message.senderId,
                "timestamp" to message.timestamp,
                "messageType" to messageType
            )

            val convRef = firestore.collection("chatConversations").document(orderId)
            val existing = convRef.get().await()

            if (existing.exists()) {
                convRef.set(
                    mapOf(
                        "orderId" to orderId,
                        "lastMessage" to lastMessage,
                        "updatedAt" to Timestamp.now()
                    ),
                    SetOptions.merge()
                ).await()
                return
            }

            // Primeira mensagem: resolve cliente/prestador pelo pedido.
            val order = firestore.collection("orders").document(orderId).get().await()
            val clienteId = order.getString("clientId")
                ?: if (message.senderType == "client") message.senderId else ""
            val clienteName = order.getString("clientName")
                ?: if (message.senderType == "client") message.senderName else ""
            val prestadorId = order.getString("assignedProvider")
                ?: if (message.senderType == "provider") message.senderId else ""
            val prestadorName = order.getString("assignedProviderName")
                ?: order.getString("providerName")
                ?: if (message.senderType == "provider") message.senderName else ""
            val protocol = order.getString("protocol")
                ?: order.getString("orderProtocol")
                ?: ""

            convRef.set(
                mapOf(
                    "orderId" to orderId,
                    "clienteId" to clienteId,
                    "clienteName" to clienteName,
                    "prestadorId" to prestadorId,
                    "prestadorName" to prestadorName,
                    "orderProtocol" to protocol,
                    "status" to "active",
                    "priority" to "medium",
                    "lastMessage" to lastMessage,
                    "unreadCount" to mapOf("cliente" to 0, "prestador" to 0, "admin" to 0),
                    "createdAt" to Timestamp.now(),
                    "updatedAt" to Timestamp.now()
                )
            ).await()
        } catch (e: Exception) {
            // Não falha o envio da mensagem se a sincronização da conversa falhar.
            e.printStackTrace()
        }
    }

    suspend fun deleteMessage(orderId: String, messageId: String): Result<Unit> {
        return try {
            firestore
                .collection("orders")
                .document(orderId)
                .collection("messages")
                .document(messageId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun deleteChatRoom(chatRoomId: String): Result<Unit> {
        return try {
            firestore.collection("chatRooms").document(chatRoomId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
} 