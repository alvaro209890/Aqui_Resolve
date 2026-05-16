package com.aquiresolve.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.aquiresolve.app.databinding.ActivityProfileBinding
import com.aquiresolve.app.utils.ImagePermissionHelper
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * ProfileActivity - Tela de perfil do usuário
 * 
 * Esta activity gerencia o perfil do usuário com:
 * - Informações pessoais
 * - Opção para se tornar prestador de serviço
 * - Configurações da conta
 * - Logout
 */
class ProfileActivity : AppCompatActivity() {

    // ViewBinding para acesso aos elementos da interface
    private lateinit var binding: ActivityProfileBinding
    private lateinit var authManager: FirebaseAuthManager
    private lateinit var permissionManager: com.aquiresolve.app.utils.ActivityPermissionManager
    private lateinit var firebaseImageManager: FirebaseImageManager
    
    // Launcher para galeria
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { launchCrop(it) }
    }

    // Launcher para câmera
    private var cameraImageUri: Uri? = null
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) cameraImageUri?.let { launchCrop(it) }
        cameraImageUri = null
    }

    // Launcher para UCrop (recorte 1:1 estilo WhatsApp)
    private val uCropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when {
            result.resultCode == Activity.RESULT_OK -> {
                result.data?.let { data ->
                    UCrop.getOutput(data)?.let { uploadAndUpdateProfile(it) }
                }
            }
            result.resultCode == UCrop.RESULT_ERROR -> {
                result.data?.let { data ->
                    val cropError = UCrop.getError(data)
                    showToast("Erro ao recortar: ${cropError?.message ?: "Desconhecido"}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Garantir Firebase inicializado
        if (!FirebaseConfig.isInitialized()) {
            FirebaseConfig.initialize(this)
        }
        
        // Inicializar managers
        authManager = FirebaseAuthManager(this)
        permissionManager = com.aquiresolve.app.utils.ActivityPermissionManager(this)
        firebaseImageManager = FirebaseImageManager()
        
        // Inicializar ViewBinding
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Configurar a interface
        setupUI()
        setupClickListeners()
        loadUserData()
    }

    override fun onResume() {
        super.onResume()
        
        // Recarregar dados quando a Activity volta do foco
        // Isso garante que o botão de prestador seja atualizado após voltar do envio de documentos
        android.util.Log.d("ProfileActivity", "🔄 onResume - Recarregando dados do usuário...")
        loadUserData()
    }

    /**
     * Configura os elementos da interface do usuário
     */
    private fun setupUI() {
        // Configurar a toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        
        // Configurar a status bar
        window.statusBarColor = ContextCompat.getColor(this, R.color.primary_color)
    }

    /**
     * Configura os listeners de clique para todos os elementos interativos
     */
    private fun setupClickListeners() {
        // Botão voltar da toolbar
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        
        // Avatar (para editar foto)
        binding.ivAvatar.setOnClickListener {
            showImagePickerDialog()
        }
        
        // Botão editar informações
        binding.ivEdit.setOnClickListener {
            showEditProfileDialog()
        }
        
        // Botão para se tornar prestador OU ir para dashboard (depende do userType)
        binding.btnBecomeProvider.setOnClickListener {
            handleBecomeProviderClick()
        }
        
        // Botão para upload de documentos (prestadores)
        binding.btnUploadDocuments.setOnClickListener {
            val intent = Intent(this, DocumentUploadActivity::class.java)
            startActivity(intent)
        }
        

        
        // Opções de configuração
        binding.llPersonalInfo.setOnClickListener {
            val intent = Intent(this, PersonalDataActivity::class.java)
            startActivity(intent)
        }
        
        binding.llNotifications.setOnClickListener {
            showNotificationsDialog()
        }
        
        binding.llPrivacy.setOnClickListener {
            showPrivacyDialog()
        }
        
        binding.llAddresses.setOnClickListener {
            val intent = Intent(this, AddressManagementActivity::class.java)
            startActivity(intent)
        }
        
        binding.llBankData.setOnClickListener {
            showBankDataDialog()
        }
        
        binding.llHelp.setOnClickListener {
            showHelpDialog()
        }
        
        // Botão de logout
        binding.btnLogout.setOnClickListener {
            showLogoutDialog()
        }
    }

    /**
     * Carrega os dados do usuário logado
     */
    private fun loadUserData() {
        val user = authManager.getLocalUserData()
        if (user != null) {
            // Log para debug
            android.util.Log.d("ProfileActivity", "=== CARREGANDO DADOS DO USUÁRIO ===")
            android.util.Log.d("ProfileActivity", "Dados locais encontrados")
            android.util.Log.d("ProfileActivity", "USER_TYPE_PROVIDER: ${FirebaseAuthManager.USER_TYPE_PROVIDER}")
            android.util.Log.d("ProfileActivity", "É prestador? ${user.userType == FirebaseAuthManager.USER_TYPE_PROVIDER}")
            
            // Mostrar nome de usuário (que pode ser editado) e email real
            binding.tvUserName.text = user.username
            binding.tvUserEmail.text = user.email
            
            // Carregar imagem do perfil se existir
            loadProfileImage(user.profileImageUrl)
            
            // Verificar se é prestador de serviço
            val isProvider = user.userType == FirebaseAuthManager.USER_TYPE_PROVIDER
            if (isProvider) {
                android.util.Log.d("ProfileActivity", "✅ USUÁRIO É PRESTADOR - Configurando interface para prestador")
                binding.tvUserType.text = "Prestador"
                // Ocultar CTA de "Ir para área do Prestador" na aba do prestador
                binding.btnBecomeProvider.visibility = View.GONE
                binding.btnUploadDocuments.visibility = View.VISIBLE
                binding.llBankData.visibility = View.VISIBLE

                // Esconder botão de documentos se prestador já aprovado
                lifecycleScope.launch {
                    try {
                        val verificationData = ProviderVerificationManager().getVerificationStatus(user.uid)
                        if (verificationData?.status == ProviderVerificationManager.VerificationStatus.APPROVED) {
                            binding.btnUploadDocuments.visibility = View.GONE
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ProfileActivity", "Erro ao verificar status: ${e.message}")
                    }
                }
                
                // Adicionar botão para voltar à conta de cliente
                addSwitchToClientButton()
            } else {
                android.util.Log.d("ProfileActivity", "✅ USUÁRIO É CLIENTE - Configurando interface para cliente")
                binding.tvUserType.text = "Cliente"
                binding.btnBecomeProvider.text = "Tornar-se Prestador de Serviços"
                binding.btnBecomeProvider.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary_color)
                binding.btnBecomeProvider.visibility = View.VISIBLE
                binding.btnUploadDocuments.visibility = View.GONE
                binding.llBankData.visibility = View.GONE
                
                // Remover botão de voltar à conta de cliente se existir
                removeSwitchToClientButton()

                // Verificar se já possui perfil de prestador no Firestore
                lifecycleScope.launch {
                    try {
                        android.util.Log.d("ProfileActivity", "🔍 Verificando se usuário tem perfil de prestador...")
                        val hasProvider = FirebaseProviderManager().hasProviderProfile(user.uid)
                        android.util.Log.d("ProfileActivity", "📊 Tem perfil de prestador? $hasProvider")
                        
                        if (hasProvider) {
                            android.util.Log.d("ProfileActivity", "✅ Usuário tem perfil de prestador - Atualizando botão")
                            binding.btnBecomeProvider.text = "Voltar para Conta de Prestador"
                            binding.btnBecomeProvider.backgroundTintList = ContextCompat.getColorStateList(this@ProfileActivity, R.color.secondary_color)
                        } else {
                            android.util.Log.d("ProfileActivity", "❌ Usuário não tem perfil de prestador - Mantendo botão padrão")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ProfileActivity", "❌ Erro ao verificar perfil de prestador: ${e.message}")
                    }
                }
            }
        } else {
            android.util.Log.e("ProfileActivity", "❌ ERRO: Dados do usuário não encontrados")
        }
    }
    
    /**
     * Carrega a imagem do perfil
     */
    private fun loadProfileImage(imageUrl: String?) {
        // Remover tint para que a foto carregue com cores corretas (evita aparência cinza)
        binding.ivAvatar.imageTintList = null
        
        val requestOptions = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .circleCrop()
            .placeholder(R.drawable.ic_person)
            .error(R.drawable.ic_person)
        
        Glide.with(this)
            .load(imageUrl?.takeIf { it.isNotEmpty() })
            .apply(requestOptions)
            .into(binding.ivAvatar)
    }

    /**
     * Mostra diálogo para selecionar imagem
     */
    private fun showImagePickerDialog() {
        permissionManager.checkAndRequestImagePermissions(
            onGranted = {
                val userId = authManager.getLocalUserData()?.uid
                    ?: authManager.getCurrentUser()?.uid
                    ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

                if (userId == null) {
                    showToast("❌ Usuário não autenticado. Faça login novamente.")
                    return@checkAndRequestImagePermissions
                }

                AlertDialog.Builder(this)
                    .setTitle("Foto do Perfil")
                    .setItems(arrayOf("📷 Tirar Foto", "🖼️ Galeria")) { _, which ->
                        when (which) {
                            0 -> takeProfilePhoto()
                            1 -> galleryLauncher.launch("image/*")
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            },
            onDenied = {
                showToast("Permissões necessárias para alterar foto do perfil")
            }
        )
    }

    private fun takeProfilePhoto() {
        try {
            val photoFile = File(getExternalFilesDir(null), "profile_${System.currentTimeMillis()}.jpg")
            cameraImageUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
            cameraLauncher.launch(cameraImageUri!!)
        } catch (e: Exception) {
            showToast("Erro ao abrir câmera: ${e.message}")
        }
    }

    private fun launchCrop(sourceUri: Uri) {
        val fileName = "profile_crop_${System.currentTimeMillis()}.jpg"
        val destinationFile = File(cacheDir, fileName)
        val destinationUri = Uri.fromFile(destinationFile)

        val options = UCrop.Options().apply {
            setCompressionFormat(android.graphics.Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
        }

        val uCropIntent = UCrop.of(sourceUri, destinationUri)
            .withAspectRatio(1f, 1f)
            .withOptions(options)
            .getIntent(this)

        uCropLauncher.launch(uCropIntent)
    }

    private fun uploadAndUpdateProfile(croppedUri: Uri) {
        val userId = authManager.getLocalUserData()?.uid
            ?: authManager.getCurrentUser()?.uid
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            ?: return

        // Mostrar barra de carregamento durante o upload
        binding.profileImageProgress.visibility = View.VISIBLE
        binding.ivAvatar.isClickable = false

        lifecycleScope.launch {
            try {
                val uploadData = FirebaseImageManager.ImageUploadData(
                    uri = croppedUri,
                    fileName = "profile_${userId}.jpg",
                    folder = FirebaseImageManager.FOLDER_PROFILE_IMAGES,
                    userId = userId,
                    orderId = null
                )
                when (val result = firebaseImageManager.uploadImage(this@ProfileActivity, uploadData)) {
                    is FirebaseImageManager.UploadResult.Success -> updateProfileImage(result.downloadUrl)
                    is FirebaseImageManager.UploadResult.Error -> showToast("❌ Erro no upload: ${result.message}")
                    else -> showToast("❌ Erro ao enviar foto")
                }
            } catch (e: Exception) {
                showToast("❌ Erro: ${e.message}")
            } finally {
                binding.profileImageProgress.visibility = View.GONE
                binding.ivAvatar.isClickable = true
            }
        }
    }

    /**
     * Atualiza a imagem do perfil
     */
    private fun updateProfileImage(imageUrl: String) {
        lifecycleScope.launch {
            try {
                // Esconder barra de carregamento
                binding.profileImageProgress.visibility = View.GONE
                binding.ivAvatar.isClickable = true
                
                // Remover tint para foto carregar com cores corretas
                binding.ivAvatar.imageTintList = null
                
                // Carregar imagem com Glide
                val requestOptions = RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                
                Glide.with(this@ProfileActivity)
                    .load(imageUrl)
                    .apply(requestOptions)
                    .into(binding.ivAvatar)
                
                // Salvar URL da imagem no Firestore
                val userId = authManager.getLocalUserData()?.uid 
                    ?: authManager.getCurrentUser()?.uid
                    ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                    
                if (userId != null) {
                    updateProfileImageInFirestore(userId, imageUrl)
                } else {
                    showToast("❌ Não foi possível identificar o usuário")
                }
                
            } catch (e: Exception) {
                showToast("❌ Erro ao atualizar foto: ${e.message}")
            }
        }
    }

    /**
     * Mostra diálogo para editar perfil
     */
    private fun showEditProfileDialog() {
        val user = authManager.getLocalUserData()
        if (user == null) {
            showToast("❌ Erro ao carregar dados do usuário")
            return
        }
        
        // Verificar se pode editar o nome de usuário
        if (!authManager.canEditUsername(user.uid)) {
            val remainingDays = (user.lastUsernameEdit + (15 * 24 * 60 * 60 * 1000L) - System.currentTimeMillis()) / (24 * 60 * 60 * 1000L)
            showToast("⏰ Você pode editar seu nome de usuário em $remainingDays dias")
            return
        }
        
        // Criar diálogo de edição
        val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Novo nome de usuário"
            setText(user.username)
            setSelection(text?.length ?: 0)
        }
        
        AlertDialog.Builder(this)
            .setTitle("Editar Nome de Usuário")
            .setMessage("Digite seu novo nome de usuário (3-60 caracteres; pode ser igual ao nome completo)")
            .setView(editText)
            .setPositiveButton("Salvar") { _, _ ->
                val newUsername = editText.text.toString().trim()
                if (newUsername.isNotEmpty()) {
                    updateUsername(newUsername)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Atualiza o nome de usuário
     */
    private fun updateUsername(newUsername: String) {
        val user = authManager.getLocalUserData()
        if (user == null) {
            showToast("❌ Erro ao carregar dados do usuário")
            return
        }
        
        // Validar nome de usuário
        if (!authManager.isValidUsername(newUsername)) {
            showToast("❌ Nome de usuário inválido")
            return
        }
        
        // Verificar se já existe
        lifecycleScope.launch {
            try {
                if (authManager.isUsernameTaken(newUsername)) {
                    showToast("❌ Nome de usuário já está em uso")
                    return@launch
                }
                
                // Atualizar no Firebase
                val result = authManager.updateUsername(user.uid, newUsername)
                if (result.isSuccess) {
                    showToast("✅ Nome de usuário atualizado com sucesso!")
                    loadUserData() // Recarregar dados
                } else {
                    showToast("❌ Erro ao atualizar nome de usuário")
                }
            } catch (e: Exception) {
                showToast("❌ Erro: ${e.message}")
            }
        }
    }

    /**
     * Mostra diálogo para se tornar prestador
     */
    private fun showBecomeProviderDialog() {
        val user = authManager.getLocalUserData()
        if (user != null && user.userType == FirebaseAuthManager.USER_TYPE_CLIENT) {
            AlertDialog.Builder(this)
                .setTitle("Tornar-se Prestador de Serviços")
                .setMessage("Deseja se tornar um prestador de serviços? Você poderá oferecer seus serviços e ganhar dinheiro.")
                .setPositiveButton("Sim") { _, _ ->
                    becomeProvider()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            showToast("🔧 Gerenciamento de serviços em desenvolvimento...")
            // TODO: Implementar gerenciamento de serviços
        }
    }

    /**
     * Converte o usuário em prestador de serviço
     */
    private fun becomeProvider() {
        val user = authManager.getLocalUserData()
        if (user != null) {
            // Simular conversão para prestador
            // Em um sistema real, isso seria salvo no banco
            showToast("✅ Agora você é um prestador de serviços!")
            loadUserData() // Recarregar dados
        }
    }

    /**
     * Mostra diálogo de informações pessoais
     */
    private fun showPersonalInfoDialog() {
        showToast("👤 Informações pessoais em desenvolvimento...")
        // TODO: Implementar tela de informações pessoais
    }

    /**
     * Mostra diálogo de notificações
     */
    private fun showNotificationsDialog() {
        val intent = Intent(this, NotificationSettingsActivity::class.java)
        startActivity(intent)
    }

    /**
     * Abre as configurações de privacidade
     */
    private fun showPrivacyDialog() {
        val intent = Intent(this, PrivacySettingsActivity::class.java)
        startActivity(intent)
    }

    /**
     * Mostra diálogo de chave PIX
     */
    private fun showBankDataDialog() {
        val user = authManager.getLocalUserData()
        if (user == null) {
            showToast("❌ Erro ao carregar dados do usuário")
            return
        }
        
        // Verificar se é prestador
        if (user.userType != FirebaseAuthManager.USER_TYPE_PROVIDER) {
            showToast("❌ Apenas prestadores podem acessar dados de PIX")
            return
        }
        
        // Buscar dados do prestador do Firebase
        lifecycleScope.launch {
            try {
                val providerManager = FirebaseProviderManager()
                val providerProfile = providerManager.getProviderProfile(user.uid)
                
                if (providerProfile == null) {
                    showToast("❌ Dados do prestador não encontrados")
                    return@launch
                }
                
                // Verificar se já tem dados de PIX
                val hasPixData = !providerProfile.pixKey.isNullOrEmpty() && 
                               !providerProfile.pixKeyType.isNullOrEmpty()
                
                if (hasPixData) {
                    // Mostrar dados de PIX existentes
                    showExistingBankData(providerProfile)
                } else {
                    // Mostrar opção para adicionar chave PIX
                    showAddBankDataDialog()
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileActivity", "Erro ao carregar dados do prestador: ${e.message}")
                showToast("❌ Erro ao carregar dados: ${e.message}")
            }
        }
    }
    
    /**
     * Mostra dados de PIX existentes
     */
    private fun showExistingBankData(providerProfile: FirebaseProviderManager.ProviderProfile) {
        val pixKeyTypeDisplay = when (providerProfile.pixKeyType?.lowercase()) {
            "cpf" -> "CPF"
            "celular" -> "Celular"
            "email" -> "Email"
            else -> providerProfile.pixKeyType ?: ""
        }
        
        val pixInfo = if (!providerProfile.pixKey.isNullOrEmpty() && !providerProfile.pixKeyType.isNullOrEmpty()) {
            "Chave PIX ($pixKeyTypeDisplay): ${providerProfile.pixKey}"
        } else {
            "Chave PIX: Não cadastrada"
        }
        
        val message = "$pixInfo\n\nDeseja editar?"
        
        AlertDialog.Builder(this)
            .setTitle("Chave PIX")
            .setMessage(message)
            .setPositiveButton("Editar") { _, _ ->
                showEditBankDataDialog(providerProfile)
            }
            .setNegativeButton("Fechar", null)
            .show()
    }
    
    /**
     * Mostra diálogo para adicionar chave PIX
     */
    private fun showAddBankDataDialog() {
        val user = authManager.getLocalUserData()
        if (user == null) {
            showToast("❌ Erro ao carregar dados do usuário")
            return
        }
        
        lifecycleScope.launch {
            try {
                val providerManager = FirebaseProviderManager()
                val providerProfile = providerManager.getProviderProfile(user.uid)
                showEditBankDataDialog(providerProfile)
            } catch (e: Exception) {
                android.util.Log.e("ProfileActivity", "Erro ao carregar dados: ${e.message}")
                showEditBankDataDialog(null)
            }
        }
    }
    
    /**
     * Mostra diálogo para editar chave PIX
     */
    private fun showEditBankDataDialog(providerProfile: FirebaseProviderManager.ProviderProfile?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_bank_data, null)
        
        // Referências aos campos de PIX
        val etPixKeyType = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPixKeyType)
        val etPixKey = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPixKey)
        val tilPixKey = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilPixKey)
        
        // Variável para rastrear o tipo de PIX selecionado
        var selectedPixType = ""
        
        // Preencher dados de PIX se já existirem
        if (providerProfile != null) {
            if (!providerProfile.pixKeyType.isNullOrEmpty()) {
                selectedPixType = providerProfile.pixKeyType.lowercase()
                val pixKeyTypeDisplay = when (selectedPixType) {
                    "cpf" -> "CPF"
                    "celular" -> "Celular"
                    "email" -> "Email"
                    else -> providerProfile.pixKeyType
                }
                etPixKeyType.setText(pixKeyTypeDisplay)
                tilPixKey.hint = pixKeyTypeDisplay
            }
            
            if (!providerProfile.pixKey.isNullOrEmpty()) {
                // Para celular, remover formatação ao exibir
                val keyToDisplay = if (selectedPixType == "celular") {
                    providerProfile.pixKey.replace(Regex("[^\\d]"), "")
                } else {
                    providerProfile.pixKey
                }
                etPixKey.setText(keyToDisplay)
            }
            
            // Aplicar formatação se já tiver tipo selecionado
            if (selectedPixType.isNotEmpty()) {
                when (selectedPixType) {
                    "cpf" -> {
                        etPixKey.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        com.aquiresolve.app.utils.TextFormatter.applyCpfFormatting(etPixKey)
                    }
                    "celular" -> {
                        // Chave PIX celular: apenas números, sem formatação
                        etPixKey.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    }
                    "email" -> {
                        etPixKey.inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    }
                }
            }
        }
        
        // Configurar seletor de tipo de chave PIX
        val pixKeyTypes = listOf("CPF", "Celular", "Email")
        etPixKeyType.setOnClickListener {
            val items = pixKeyTypes.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Selecione o tipo de chave PIX")
                .setItems(items) { _, which ->
                    val selectedType = pixKeyTypes[which]
                    selectedPixType = selectedType.lowercase()
                    etPixKeyType.setText(selectedType)
                    tilPixKey.hint = selectedType
                    
                    // Ajustar input type e formatação baseado no tipo selecionado
                    when (selectedPixType) {
                        "cpf" -> {
                            etPixKey.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                            etPixKey.text?.clear()
                            // Aplicar formatação de CPF
                            com.aquiresolve.app.utils.TextFormatter.applyCpfFormatting(etPixKey)
                        }
                        "celular" -> {
                            // Chave PIX celular: apenas números, sem formatação
                            etPixKey.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                            etPixKey.text?.clear()
                            // Remover qualquer formatação existente
                            etPixKey.removeTextChangedListener(etPixKey.tag as? android.text.TextWatcher)
                        }
                        "email" -> {
                            etPixKey.inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                            etPixKey.text?.clear()
                        }
                    }
                }
                .show()
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setTitle("Chave PIX")
            .setPositiveButton("Salvar") { _, _ ->
                // Usar selectedPixType se disponível, senão usar o texto do campo
                val pixTypeToSave = if (selectedPixType.isNotEmpty()) {
                    selectedPixType
                } else {
                    etPixKeyType.text?.toString()?.trim()?.lowercase() ?: ""
                }
                
                var pixKeyToSave = etPixKey.text?.toString()?.trim() ?: ""
                
                // Remover formatação da chave PIX celular antes de salvar
                if (pixTypeToSave == "celular") {
                    pixKeyToSave = pixKeyToSave.replace(Regex("[^\\d]"), "")
                }
                
                savePixData(
                    pixTypeToSave,
                    pixKeyToSave
                )
            }
            .setNegativeButton("Cancelar", null)
            .create()
        
        dialog.show()
    }
    
    /**
     * Salva dados de PIX
     */
    private fun savePixData(
        pixKeyType: String,
        pixKey: String
    ) {
        val user = authManager.getLocalUserData()
        if (user == null) {
            showToast("❌ Erro ao salvar dados")
            return
        }
        
        lifecycleScope.launch {
            try {
                // Validar PIX
                if (pixKeyType.isEmpty()) {
                    showToast("❌ Selecione o tipo de chave PIX")
                    return@launch
                }
                
                if (pixKey.isEmpty()) {
                    showToast("❌ Informe a chave PIX")
                    return@launch
                }
                
                // Validar formato da chave PIX
                when (pixKeyType.lowercase()) {
                    "cpf" -> {
                        val cleanKey = pixKey.replace(Regex("[^0-9]"), "")
                        if (cleanKey.length != 11) {
                            showToast("❌ CPF deve ter 11 dígitos")
                            return@launch
                        }
                    }
                    "celular" -> {
                        // Chave PIX celular: apenas números, sem formatação, 11 dígitos
                        val cleanKey = pixKey.replace(Regex("[^\\d]"), "")
                        if (cleanKey.length != 11) {
                            showToast("❌ Celular deve ter 11 dígitos (com DDD)")
                            return@launch
                        }
                    }
                    "email" -> {
                        // Validar formato de email
                        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(pixKey).matches()) {
                            showToast("❌ Email inválido")
                            return@launch
                        }
                    }
                }
                
                // Buscar perfil atual do prestador
                val providerManager = FirebaseProviderManager()
                val currentProfile = providerManager.getProviderProfile(user.uid)
                
                if (currentProfile == null) {
                    showToast("❌ Perfil do prestador não encontrado")
                    return@launch
                }
                
                // Criar novo perfil com dados de PIX atualizados (mantendo dados bancários existentes)
                val updatedProfile = currentProfile.copy(
                    pixKey = pixKey.ifEmpty { null },
                    pixKeyType = pixKeyType.lowercase().ifEmpty { null }
                )
                
                // Salvar no Firebase
                val result = providerManager.updateProfile(updatedProfile)
                
                if (result is FirebaseProviderManager.ProviderResult.Success) {
                    showToast("✅ Chave PIX salva com sucesso!")
                } else {
                    val errorMessage = (result as? FirebaseProviderManager.ProviderResult.Error)?.message ?: "Erro desconhecido"
                    showToast("❌ Erro ao salvar: $errorMessage")
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileActivity", "Erro ao salvar chave PIX: ${e.message}")
                showToast("❌ Erro ao salvar dados: ${e.message}")
            }
        }
    }

    /**
     * Mostra tela de ajuda e suporte
     */
    private fun showHelpDialog() {
        val user = authManager.getLocalUserData()
        val userType = user?.userType ?: ""
        
        val intent = Intent(this, HelpSupportActivity::class.java)
        intent.putExtra("user_type", userType)
        startActivity(intent)
    }

    /**
     * Mostra diálogo de logout
     */
    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Sair da Conta")
            .setMessage("Tem certeza que deseja sair da sua conta?")
            .setPositiveButton("Sim") { _, _ ->
                logout()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Faz logout do usuário
     */
    private fun logout() {
        // Fazer logout usando FirebaseAuthManager
        authManager.signOut()
        showToast("👋 Logout realizado com sucesso!")
        
        // Voltar para a tela de login
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    /**
     * Atualiza a URL da imagem no Firestore (users e providers para prestadores)
     */
    private fun updateProfileImageInFirestore(userId: String, imageUrl: String) {
        lifecycleScope.launch {
            try {
                val result = authManager.updateUserProfileImage(userId, imageUrl)
                if (!result.isSuccess) {
                    showToast("❌ Erro ao salvar foto no servidor")
                    return@launch
                }

                // Sempre tentar atualizar coleção providers (mesma foto para ambos os perfis)
                try {
                    val providerDoc = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("providers").document(userId).get().await()
                    if (providerDoc.exists()) {
                        val providerManager = FirebaseProviderManager()
                        providerManager.updateProfileImage(userId, imageUrl)
                    }
                } catch (e: Exception) {
                    android.util.Log.d("ProfileActivity", "Sem perfil de prestador para atualizar: ${e.message}")
                }

                showToast("✅ Foto do perfil atualizada com sucesso!")
                loadUserData()
            } catch (e: Exception) {
                showToast("❌ Erro ao salvar foto: ${e.message}")
            }
        }
    }

    /**
     * Adiciona botão para voltar à conta de cliente (apenas para prestadores)
     */
    private fun addSwitchToClientButton() {
        // Verificar se o botão já existe
        val existingButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_switch_to_client)
        if (existingButton != null) return
        
        // Criar botão dinamicamente
        val switchButton = com.google.android.material.button.MaterialButton(this).apply {
            id = R.id.btn_switch_to_client
            text = "Voltar para Conta Cliente"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@ProfileActivity, R.color.white))
            backgroundTintList = ContextCompat.getColorStateList(this@ProfileActivity, R.color.gray_600)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(32, 16, 32, 16)
            }
            setOnClickListener {
                switchToClientAccount()
            }
        }
        
        // Adicionar o botão após o botão de prestador
        val parentLayout = binding.btnBecomeProvider.parent as android.widget.LinearLayout
        val providerButtonIndex = parentLayout.indexOfChild(binding.btnBecomeProvider)
        parentLayout.addView(switchButton, providerButtonIndex + 1)
    }
    
    /**
     * Remove o botão de voltar à conta de cliente
     */
    private fun removeSwitchToClientButton() {
        val switchButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_switch_to_client)
        if (switchButton != null) {
            val parentLayout = switchButton.parent as android.widget.LinearLayout
            parentLayout.removeView(switchButton)
        }
    }
    
    /**
     * Volta para a conta de cliente
     */
    private fun switchToClientAccount() {
        lifecycleScope.launch {
            try {
                val user = authManager.getLocalUserData()
                if (user != null) {
                    // Atualizar tipo de usuário para cliente
                    val updatedUser = user.copy(userType = FirebaseAuthManager.USER_TYPE_CLIENT)
                    val result = authManager.updateUserProfile(updatedUser)
                    if (!result.isSuccess) {
                        // Fallback offline: manter alternância local
                        authManager.cacheUserDataLocally(updatedUser)
                        showToast("⚠️ Sem conexão. Alternando para cliente localmente.")
                    }
                    ProviderNewOrderAlertManager.refreshMonitoring()
                    showToast("✅ Conta alterada para Cliente")
                    
                    // Recarregar dados e atualizar interface
                    loadUserData()
                    
                    // Navegar para a tela principal do cliente (ClientHome)
                    val intent = Intent(this@ProfileActivity, ClientHomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("show_switch_message", true)
                        putExtra("switch_message", "🎉 Agora você está na conta de Cliente!")
                        putExtra("can_switch_to_provider", true) // Indica que pode voltar para prestador
                    }
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                showToast("❌ Erro ao alterar conta: ${e.message}")
            }
        }
    }

    /**
     * Exibe uma mensagem toast para o usuário
     * 
     * @param message Mensagem a ser exibida
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Manipula o clique no botão para trocar perfil
     */
    private fun handleBecomeProviderClick() {
        val user = authManager.getLocalUserData()
        if (user == null) {
            android.util.Log.e("ProfileActivity", "❌ ERRO: Dados do usuário não encontrados no handleBecomeProviderClick")
            return
        }
        
        android.util.Log.d("ProfileActivity", "=== HANDLE BECOME PROVIDER CLICK ===")
        android.util.Log.d("ProfileActivity", "UserType: ${user.userType}")
        android.util.Log.d("ProfileActivity", "USER_TYPE_CLIENT: ${FirebaseAuthManager.USER_TYPE_CLIENT}")
        android.util.Log.d("ProfileActivity", "USER_TYPE_PROVIDER: ${FirebaseAuthManager.USER_TYPE_PROVIDER}")
        android.util.Log.d("ProfileActivity", "É cliente? ${user.userType == FirebaseAuthManager.USER_TYPE_CLIENT}")
        android.util.Log.d("ProfileActivity", "É prestador? ${user.userType == FirebaseAuthManager.USER_TYPE_PROVIDER}")
        
        if (user.userType == FirebaseAuthManager.USER_TYPE_CLIENT) {
            lifecycleScope.launch {
                // Se já tem perfil de prestador, apenas alterna e navega
                val hasProvider = try { FirebaseProviderManager().hasProviderProfile(user.uid) } catch (_: Exception) { false }
                if (hasProvider) {
                    // Atualizar tipo no Firestore e local, e ir para ProviderHome
                    val updatedUser = user.copy(userType = FirebaseAuthManager.USER_TYPE_PROVIDER)
                    val result = authManager.updateUserProfile(updatedUser)
                    if (!result.isSuccess) {
                        // Fallback offline: manter alternância local
                        authManager.cacheUserDataLocally(updatedUser)
                        showToast("⚠️ Sem conexão. Alternando para prestador localmente.")
                    }
                    ProviderNewOrderAlertManager.refreshMonitoring()
                    val intent = Intent(this@ProfileActivity, ProviderHomeActivity::class.java).apply {
                        putExtra("show_switch_message", true)
                        putExtra("switch_message", "🎉 Agora você está na conta de Prestador!")
                    }
                    startActivity(intent)
                    finish()
                } else {
                    // Ir para cadastro de prestador
                    startProviderRegistration()
                }
            }
        } else {
            android.util.Log.d("ProfileActivity", "✅ USUÁRIO É PRESTADOR - Navegando para envio de documentos")
            val intent = Intent(this, DocumentUploadActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * Inicia o processo de registro de prestador
     */
    private fun startProviderRegistration() {
        val intent = Intent(this, ProviderSignUpActivity::class.java)
        intent.putExtra("from_profile", true) // Indica que veio do perfil
        startActivity(intent)
    }
    
    /**
     * Troca o perfil de prestador para cliente
     */
    private fun switchToClientProfile() {
        lifecycleScope.launch {
            try {
                val user = authManager.getLocalUserData()
                if (user != null) {
                    // Atualizar tipo de usuário para cliente
                    val updatedUser = user.copy(userType = FirebaseAuthManager.USER_TYPE_CLIENT)
                    
                    // Atualizar no Firebase
                    authManager.updateUserProfile(updatedUser)
                    
                    showToast("✅ Perfil alterado para Cliente")
                    loadUserData() // Recarregar dados
                }
            } catch (e: Exception) {
                showToast("❌ Erro ao alterar perfil: ${e.message}")
            }
        }
    }

    /**
     * Limpa os recursos quando a activity é destruída
     */
    override fun onDestroy() {
        super.onDestroy()
        // Limpar recursos se necessário
    }
} 
