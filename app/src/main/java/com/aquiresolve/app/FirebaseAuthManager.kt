package com.aquiresolve.app

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class FirebaseAuthManager(private val context: Context) {
    
    private val auth: FirebaseAuth = FirebaseConfig.getAuth()
    private val firestore: FirebaseFirestore = FirebaseConfig.getFirestore()
    private val prefs: SharedPreferences = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    
    companion object {
        const val USER_TYPE_CLIENT = "client"
        const val USER_TYPE_PROVIDER = "provider"
    }
    
    data class UserData(
        val uid: String,
        val email: String,
        val fullName: String,
        val username: String,
        val phone: String,
        val userType: String,
        val isVerified: Boolean = false,
        val profileImageUrl: String? = null,
        val lastUsernameEdit: Long = 0L
    )
    
    /**
     * Verifica se um username já está em uso
     */
    suspend fun isUsernameAvailable(username: String): Boolean {
        return try {
            android.util.Log.d("FirebaseAuthManager", "Verificando disponibilidade do username")
            
            val snapshot = firestore.collection("users")
                .whereEqualTo("username", username)
                .get()
                .await()
            
            val available = snapshot.isEmpty
            android.util.Log.d("FirebaseAuthManager", if (available) "✅ Username disponível" else "❌ Username já em uso")
            
            available
        } catch (e: Exception) {
            android.util.Log.e("FirebaseAuthManager", "Erro ao verificar username", e)
            true // Em caso de erro, permitir continuar
        }
    }
    
    suspend fun signUp(email: String, password: String, userData: UserData): Result<FirebaseUser> {
        return try {
            android.util.Log.d("FirebaseAuthManager", "Iniciando cadastro")
            
            // Verificar se o username já está em uso
            if (!isUsernameAvailable(userData.username)) {
                android.util.Log.e("FirebaseAuthManager", "❌ ERRO: Username já está em uso")
                return Result.failure(Exception("Este nome de usuário já está em uso por outra conta. O nome de usuário precisa ser único no app."))
            }
            
            android.util.Log.d("FirebaseAuthManager", "🔄 CRIANDO USUÁRIO NO FIREBASE AUTH...")
            
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            
            android.util.Log.d("FirebaseAuthManager", "Usuário criado no Firebase Auth")
            
            result.user?.let { user ->
                android.util.Log.d("FirebaseAuthManager", "Salvando dados no Firestore")
                
                // Salvar dados do usuário no Firestore
                val userMap = hashMapOf(
                    "uid" to user.uid,
                    "email" to userData.email,
                    "fullName" to userData.fullName,
                    "username" to userData.username,
                    "phone" to userData.phone,
                    "userType" to userData.userType,
                    "isVerified" to userData.isVerified,
                    "profileImageUrl" to userData.profileImageUrl,
                    "lastUsernameEdit" to userData.lastUsernameEdit,
                    "createdAt" to com.google.firebase.Timestamp.now()
                )
                
                try {
                    firestore.collection("users").document(user.uid).set(userMap).await()
                    android.util.Log.d("FirebaseAuthManager", "✅ DADOS SALVOS NO FIRESTORE")
                } catch (firestoreError: Exception) {
                    android.util.Log.w("FirebaseAuthManager", "Erro ao salvar dados no Firestore", firestoreError)
                    android.util.Log.w("FirebaseAuthManager", "⚠️ CONTINUANDO COM CADASTRO LOCAL...")
                    // Continuar mesmo com erro do Firestore
                }
                
                // Salvar dados locais com UID atualizado (sempre funciona)
                val updatedUserData = userData.copy(uid = user.uid)
                saveUserDataLocally(updatedUserData)
                android.util.Log.d("FirebaseAuthManager", "Dados locais salvos")

                // Registrar token FCM (best-effort)
                try {
                    val notificationManager = FirebaseNotificationManager(context)
                    notificationManager.saveUserToken(user.uid)
                } catch (_: Exception) {}
                
                android.util.Log.d("FirebaseAuthManager", "🎉 CADASTRO COMPLETADO COM SUCESSO!")
            }
            
            val user = result.user
            if (user == null) {
                return Result.failure(Exception("Falha ao criar conta: usuário não retornado pelo Firebase"))
            }
            Result.success(user)
        } catch (e: Exception) {
            android.util.Log.e("FirebaseAuthManager", "❌ ERRO NO CADASTRO:", e)
            
            when {
                e.message?.contains("email address is already in use") == true -> {
                    android.util.Log.e("FirebaseAuthManager", "ERRO: Email já está em uso")
                    Result.failure(Exception("Este e-mail já está em uso"))
                }
                e.message?.contains("nome de usuário já está em uso") == true -> {
                    android.util.Log.e("FirebaseAuthManager", "ERRO: Username já está em uso")
                    Result.failure(Exception("Este nome de usuário já está em uso por outra conta. O nome de usuário precisa ser único no app."))
                }
                e.message?.contains("password is invalid") == true -> {
                    android.util.Log.e("FirebaseAuthManager", "ERRO: Senha inválida")
                    Result.failure(Exception("Senha inválida"))
                }
                e.message?.contains("network") == true -> {
                    android.util.Log.e("FirebaseAuthManager", "ERRO: Problema de rede")
                    Result.failure(Exception("Erro de conexão. Verifique sua internet"))
                }
                else -> {
                    android.util.Log.e("FirebaseAuthManager", "ERRO DESCONHECIDO")
                    Result.failure(Exception("Erro ao criar conta: ${e.message}"))
                }
            }
        }
    }
    
    suspend fun signIn(email: String, password: String): Result<UserData> {
        return try {
            android.util.Log.d("FirebaseAuthManager", "Attempting sign in")
            
            val result = auth.signInWithEmailAndPassword(email, password).await()
            
            result.user?.let { user ->
                android.util.Log.d("FirebaseAuthManager", "User authenticated, fetching data from Firestore")
                val userData = getUserDataFromFirestore(user.uid)
                val finalData = if (userData != null) {
                    userData
                } else {
                    android.util.Log.w("FirebaseAuthManager", "User data not found. Creating minimal profile document.")
                    val minimal = UserData(
                        uid = user.uid,
                        email = user.email ?: email,
                        fullName = user.displayName ?: email.substringBefore('@'),
                        username = email.substringBefore('@'),
                        phone = "",
                        userType = USER_TYPE_CLIENT,
                        isVerified = false,
                        profileImageUrl = user.photoUrl?.toString(),
                        lastUsernameEdit = 0L
                    )
                    // Persistir doc mínimo no Firestore (best-effort)
                    try {
                        val userMap = hashMapOf(
                            "uid" to minimal.uid,
                            "email" to minimal.email,
                            "fullName" to minimal.fullName,
                            "username" to minimal.username,
                            "phone" to minimal.phone,
                            "userType" to minimal.userType,
                            "isVerified" to minimal.isVerified,
                            "profileImageUrl" to minimal.profileImageUrl,
                            "lastUsernameEdit" to minimal.lastUsernameEdit,
                            "createdAt" to com.google.firebase.Timestamp.now()
                        )
                        firestore.collection("users").document(minimal.uid).set(userMap).await()
                    } catch (_: Exception) {}
                    minimal
                }

                // Salvar localmente
                saveUserDataLocally(finalData)

                // Registrar token FCM
                try {
                    val notificationManager = FirebaseNotificationManager(context)
                    notificationManager.saveUserToken(user.uid)
                } catch (_: Exception) {}

                // Registrar hora de login no Firestore
                try {
                    updateUserOnlineStatus(user.uid, isOnline = true)
                } catch (_: Exception) {}

                Result.success(finalData)
            } ?: Result.failure(Exception("Falha na autenticação"))
        } catch (e: Exception) {
            android.util.Log.e("FirebaseAuthManager", "Sign in error", e)
            when {
                e.message?.contains("no user record") == true -> {
                    Result.failure(Exception("Usuário não encontrado"))
                }
                e.message?.contains("password is invalid") == true -> {
                    Result.failure(Exception("Senha incorreta"))
                }
                e.message?.contains("network") == true -> {
                    Result.failure(Exception("Erro de conexão. Verifique sua internet"))
                }
                else -> {
                    Result.failure(Exception("Erro de autenticação: ${e.message}"))
                }
            }
        }
    }
    
    fun signOut() {
        android.util.Log.d("FirebaseAuthManager", "🔄 Iniciando logout...")
        
        // Registrar hora de logout no Firestore (sem bloquear a main thread)
        try {
            val currentUserId = auth.currentUser?.uid
            if (currentUserId != null) {
                kotlinx.coroutines.GlobalScope.launch {
                    updateUserOnlineStatus(currentUserId, isOnline = false)
                }
            }
        } catch (_: Exception) {}
        
        // Fazer logout do Firebase Auth
        auth.signOut()
        android.util.Log.d("FirebaseAuthManager", "✅ Logout do Firebase Auth realizado")
        
        // Limpar dados locais
        clearLocalUserData()
        android.util.Log.d("FirebaseAuthManager", "✅ Dados locais limpos")
        
        android.util.Log.d("FirebaseAuthManager", "🎉 Logout completo realizado com sucesso")
    }
    
    fun getCurrentUser(): FirebaseUser? {
        return auth.currentUser
    }
    
    fun isUserLoggedIn(): Boolean {
        return auth.currentUser != null
    }
    
    /**
     * Verifica se o usuário está logado e restaura a sessão se necessário
     */
    suspend fun checkAndRestoreSession(): Boolean {
        return try {
            // Verificar se há usuário atual no Firebase
            val currentUser = auth.currentUser

            if (currentUser != null) {
                // Usuário já está logado no Firebase
                android.util.Log.d("FirebaseAuthManager", "Usuário já logado no Firebase")
                return true
            }

            // Verificar se há dados locais de login
            if (isLoggedInLocally()) {
                val localUserData = getLocalUserData()
                if (localUserData != null) {
                    android.util.Log.d("FirebaseAuthManager", "Tentando restaurar sessão")

                    // Tentar obter dados atualizados do Firestore
                    val firestoreUserData = getUserDataFromFirestore(localUserData.uid)
                    if (firestoreUserData != null) {
                        // Atualizar dados locais com informações do Firestore
                        saveUserDataLocally(firestoreUserData)
                        android.util.Log.d("FirebaseAuthManager", "✅ Sessão restaurada com sucesso")
                        return true
                    } else {
                        // Dados locais válidos, mas não conseguiu conectar ao Firestore
                        android.util.Log.w("FirebaseAuthManager", "⚠️ Usando dados locais (Firestore indisponível)")
                        return true
                    }
                }
            }

            android.util.Log.d("FirebaseAuthManager", "❌ Nenhuma sessão válida encontrada")
            false
        } catch (e: Exception) {
            android.util.Log.e("FirebaseAuthManager", "Erro ao verificar sessão", e)
            false
        }
    }
    
    suspend fun getUserDataFromFirestore(uid: String): UserData? {
        return try {
            val document = firestore.collection("users").document(uid).get().await()
            if (document.exists()) {
                UserData(
                    uid = document.getString("uid") ?: "",
                    email = document.getString("email") ?: "",
                    fullName = document.getString("fullName") ?: "",
                    username = document.getString("username") ?: "",
                    phone = document.getString("phone") ?: "",
                    userType = document.getString("userType") ?: "",
                    isVerified = document.getBoolean("isVerified") ?: false,
                    profileImageUrl = document.getString("profileImageUrl"),
                    lastUsernameEdit = document.getLong("lastUsernameEdit") ?: 0L
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun updateUserProfile(userData: UserData): Result<Unit> {
        return try {
            val userMap = hashMapOf(
                "fullName" to userData.fullName,
                "phone" to userData.phone,
                "userType" to userData.userType,
                "profileImageUrl" to userData.profileImageUrl,
                "updatedAt" to com.google.firebase.Timestamp.now(),
                "isVerified" to userData.isVerified,
                "uid" to userData.uid,
                "email" to userData.email,
                "username" to userData.username,
                "lastUsernameEdit" to userData.lastUsernameEdit
            )

            firestore.collection("users")
                .document(userData.uid)
                .set(userMap, SetOptions.merge())
                .await()
            saveUserDataLocally(userData)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun updateUserVerificationStatus(uid: String, isVerified: Boolean): Result<Unit> {
        return try {
            firestore.collection("users").document(uid).update("isVerified", isVerified).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun updateUsername(uid: String, newUsername: String): Result<Unit> {
        return try {
            val currentTime = System.currentTimeMillis()
            firestore.collection("users").document(uid).update(
                mapOf(
                    "username" to newUsername,
                    "lastUsernameEdit" to currentTime
                )
            ).await()
            
            // Atualizar dados locais
            val currentUser = getLocalUserData()
            if (currentUser != null) {
                val updatedUser = currentUser.copy(
                    username = newUsername,
                    lastUsernameEdit = currentTime
                )
                saveUserDataLocally(updatedUser)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Atualiza a URL da imagem de perfil do usuário
     * 
     * @param uid ID do usuário
     * @param imageUrl URL da nova imagem de perfil
     * @return Result indicando sucesso ou falha
     */
    suspend fun updateUserProfileImage(uid: String, imageUrl: String): Result<Unit> {
        return try {
            // Atualizar no Firestore
            firestore.collection("users").document(uid).update(
                mapOf(
                    "profileImageUrl" to imageUrl,
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()
            
            // Atualizar dados locais
            val currentUser = getLocalUserData()
            if (currentUser != null) {
                val updatedUser = currentUser.copy(profileImageUrl = imageUrl)
                saveUserDataLocally(updatedUser)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("FirebaseAuthManager", "Erro ao atualizar imagem de perfil", e)
            Result.failure(e)
        }
    }
    
    fun canEditUsername(userId: String): Boolean {
        val userData = getLocalUserData()
        if (userData?.uid != userId) return true
        
        val lastEdit = userData.lastUsernameEdit
        if (lastEdit == 0L) return true
        
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastEdit
        val daysDiff = timeDiff / (1000 * 60 * 60 * 24) // Converter para dias
        
        return daysDiff >= 15
    }
    
    /**
     * Verifica se um nome de usuário já existe no banco de dados
     * 
     * @param username Nome de usuário a verificar
     * @return true se o nome de usuário já existe, false caso contrário
     */
    suspend fun isUsernameTaken(username: String): Boolean {
        return try {
            val query = firestore.collection("users")
                .whereEqualTo("username", username)
                .get()
                .await()
            
            !query.isEmpty
        } catch (e: Exception) {
            android.util.Log.e("FirebaseAuthManager", "Erro ao verificar nome de usuário", e)
            false // Em caso de erro, assumir que não está em uso
        }
    }
    
    /**
     * Verifica se um nome de usuário é válido
     * 
     * @param username Nome de usuário a validar
     * @return true se é válido, false caso contrário
     */
    fun isValidUsername(username: String): Boolean {
        // Regras para nome de usuário válido:
        // - Mínimo 3 caracteres, máximo 60
        // - Aceita letras com acento, números, espaços, underscore, hífen e apóstrofo
        // - Não pode começar ou terminar com espaço, underscore, hífen ou apóstrofo
        // Assim o usuário pode usar o próprio nome completo também como nome de usuário.
        
        if (username.length < 3 || username.length > 60) return false
        
        val usernameRegex = Regex("^[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N} _'’-]*[\\p{L}\\p{M}\\p{N}]$")
        return usernameRegex.matches(username)
    }
    
    private fun saveUserDataLocally(userData: UserData) {
        prefs.edit().apply {
            putString("user_uid", userData.uid)
            putString("user_email", userData.email)
            putString("user_full_name", userData.fullName)
            putString("user_username", userData.username)
            putString("user_phone", userData.phone)
            putString("user_type", userData.userType)
            putBoolean("user_verified", userData.isVerified)
            putString("user_profile_image", userData.profileImageUrl)
            putLong("user_last_username_edit", userData.lastUsernameEdit)
            putBoolean("is_logged_in", true)
        }.apply()
    }
    
    private fun clearLocalUserData() {
        prefs.edit().apply {
            // Remover todos os dados do usuário
            remove("user_uid")
            remove("user_email")
            remove("user_full_name")
            remove("user_username")
            remove("user_phone")
            remove("user_type")
            remove("user_verified")
            remove("user_profile_image")
            remove("user_last_username_edit")
            remove("is_logged_in")
        }.apply()
        
        android.util.Log.d("FirebaseAuthManager", "🧹 Todos os dados locais removidos")
    }
    
    fun getLocalUserData(): UserData? {
        val uid = prefs.getString("user_uid", null) ?: return null
        return UserData(
            uid = uid,
            email = prefs.getString("user_email", "") ?: "",
            fullName = prefs.getString("user_full_name", "") ?: "",
            username = prefs.getString("user_username", "") ?: "",
            phone = prefs.getString("user_phone", "") ?: "",
            userType = prefs.getString("user_type", "") ?: "",
            isVerified = prefs.getBoolean("user_verified", false),
            profileImageUrl = prefs.getString("user_profile_image", null),
            lastUsernameEdit = prefs.getLong("user_last_username_edit", 0L)
        )
    }
    
    fun isLoggedInLocally(): Boolean {
        return prefs.getBoolean("is_logged_in", false)
    }

    /**
     * Atualiza SOMENTE o cache local de dados do usuário (SharedPreferences)
     * sem gravar alterações no Firestore.
     */
    fun cacheUserDataLocally(userData: UserData) {
        saveUserDataLocally(userData)
    }
    
    /**
     * Atualiza o status online do usuário no Firestore
     * Registra horário de login e logout
     */
    private suspend fun updateUserOnlineStatus(userId: String, isOnline: Boolean) {
        try {
            val now = com.google.firebase.Timestamp.now()
            val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale("pt", "BR"))
            val formattedTime = dateFormat.format(now.toDate())
            
            val updates = if (isOnline) {
                hashMapOf<String, Any>(
                    "lastLoginAt" to now,
                    "lastLoginFormatted" to formattedTime,
                    "isOnline" to true,
                    "onlineStatusUpdatedAt" to now
                )
            } else {
                hashMapOf<String, Any>(
                    "lastLogoutAt" to now,
                    "lastLogoutFormatted" to formattedTime,
                    "isOnline" to false,
                    "onlineStatusUpdatedAt" to now
                )
            }
            
            firestore.collection("users").document(userId)
                .update(updates)
                .await()
                
            android.util.Log.d("FirebaseAuthManager", "Status online atualizado")
        } catch (e: Exception) {
            android.util.Log.e("FirebaseAuthManager", "Erro ao atualizar status online", e)
        }
    }
}
