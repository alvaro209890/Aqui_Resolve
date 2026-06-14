package com.aquiresolve.app

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.TranslateAnimation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aquiresolve.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import com.google.android.material.snackbar.Snackbar
import android.widget.ImageView

/**
 * MainActivity - Tela principal de login do aplicativo
 * 
 * Esta activity gerencia a interface de login do usuário, incluindo:
 * - Validação de email e senha
 * - Autenticação local do usuário
 * - Navegação para outras telas
 * - Simulação de login com redes sociais (Google, Facebook)
 */
class MainActivity : AppCompatActivity() {

    // ViewBinding para acesso aos elementos da interface
    private lateinit var binding: ActivityMainBinding
    
    // Variáveis para controle de estado
    private var isLoading = false
    private lateinit var authManager: FirebaseAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        
        // Inicializar AuthManager
        authManager = FirebaseAuthManager(this)

        // Inicializar ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Solicitar permissão de notificação
        requestNotificationPermissionIfNeeded()
        
        // Configurar a interface
        setupUI()
        setupAnimations()
        setupClickListeners()
    }

    override fun onStart() {
        super.onStart()
        
        // Verificar se há mensagem de sucesso para mostrar
        checkForSuccessMessage()
        
        // Verificar se o usuário já está logado
        checkAutoLogin()
    }

    /**
     * Verifica se há mensagem de sucesso para mostrar
     */
    private fun checkForSuccessMessage() {
        val showSuccessMessage = intent.getBooleanExtra("show_success_message", false)
        val successMessage = intent.getStringExtra("success_message")
        
        if (showSuccessMessage && successMessage != null) {
            // Limpar os extras para evitar mostrar a mensagem novamente
            intent.removeExtra("show_success_message")
            intent.removeExtra("success_message")
            
            // Mostrar mensagem de sucesso com animação
            showSuccessMessageWithAnimation(successMessage)
        }
    }

    /**
     * Verifica se o usuário já está logado e navega automaticamente
     */
    private fun checkAutoLogin() {
        lifecycleScope.launch {
            try {
                // Verificar e restaurar sessão se necessário
                val isLoggedIn = authManager.checkAndRestoreSession()
                
                if (isLoggedIn) {
                    // Obter dados do usuário
                    val userData = authManager.getLocalUserData()
                    
                    if (userData != null) {
                        android.util.Log.d("MainActivity", "Login automático detectado")
                        
                        // Garantir userType atualizado do Firestore (caso tenha virado prestador em outra tela)
                        val refreshed = authManager.getUserDataFromFirestore(userData.uid)
                        if (refreshed != null) {
                            // Atualizar somente o cache local (evitar gravação remota desnecessária)
                            authManager.cacheUserDataLocally(refreshed)
                        }

                        // Navegar para a tela apropriada baseada no tipo de usuário
                        val typeToUse = refreshed?.userType ?: userData.userType
                        when (typeToUse) {
                            FirebaseAuthManager.USER_TYPE_CLIENT -> {
                                android.util.Log.d("MainActivity", "🚀 Navegando para ClientHomeActivity")
                                val intent = Intent(this@MainActivity, ClientHomeActivity::class.java)
                                startActivity(intent)
                                finish()
                            }
                            FirebaseAuthManager.USER_TYPE_PROVIDER -> {
                                android.util.Log.d("MainActivity", "🚀 Navegando para ProviderHomeActivity")
                                val intent = Intent(this@MainActivity, ProviderHomeActivity::class.java)
                                startActivity(intent)
                                finish()
                            }
                            else -> {
                                android.util.Log.d("MainActivity", "🚀 Navegando para HomeActivity (tipo desconhecido)")
                                // Tipo desconhecido, ir para tela genérica
                                val intent = Intent(this@MainActivity, HomeActivity::class.java)
                                startActivity(intent)
                                finish()
                            }
                        }
                    } else {
                        android.util.Log.w("MainActivity", "⚠️ Dados locais corrompidos, fazendo logout")
                        // Dados locais corrompidos, fazer logout
                        authManager.signOut()
                    }
                } else {
                    android.util.Log.d("MainActivity", "ℹ️ Nenhum login automático detectado")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Erro ao verificar login automático: ${e.message}")
                // Em caso de erro, continuar na tela de login
            }
        }
    }

    /**
     * Mostra mensagem de sucesso com animação elegante
     */
    private fun showSuccessMessageWithAnimation(message: String) {
        // Criar um Snackbar elegante
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        
        // Configurar o estilo do Snackbar
        snackbar.setBackgroundTint(ContextCompat.getColor(this, R.color.success_green))
        snackbar.setTextColor(ContextCompat.getColor(this, R.color.white))
        
        // Mostrar com animação
        snackbar.show()
        
        // Animar o card de login para chamar atenção
        binding.cardLogin.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(200)
            .withEndAction {
                binding.cardLogin.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }

    /**
     * Configura os elementos da interface do usuário
     */
    private fun setupUI() {
        // Configurar a status bar para ser transparente
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.transparent)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        
        // Configurar foco inicial no campo de email
        binding.etEmail.requestFocus()
    }
    
    /**
     * Configura as animações da interface
     */
    private fun setupAnimations() {
        // Load animations
        val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        val slideUp = AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left)
        
        // Apply animations with delays
        binding.headerSection.startAnimation(fadeIn)
        
        // Animate floating circles
        animateFloatingCircles()
        
        // Animate login card with delay
        binding.cardLogin.postDelayed({
            binding.cardLogin.startAnimation(slideUp)
        }, 300)
    }
    
    private fun animateFloatingCircles() {
        // Animate first circle
        val animation1 = TranslateAnimation(
            TranslateAnimation.RELATIVE_TO_PARENT, 0f,
            TranslateAnimation.RELATIVE_TO_PARENT, 0.1f,
            TranslateAnimation.RELATIVE_TO_PARENT, 0f,
            TranslateAnimation.RELATIVE_TO_PARENT, 0.1f
        )
        animation1.duration = 4000
        animation1.repeatCount = -1
        animation1.repeatMode = TranslateAnimation.REVERSE
        binding.floatingCircle1.startAnimation(animation1)
        
        // Animate second circle
        val animation2 = TranslateAnimation(
            TranslateAnimation.RELATIVE_TO_PARENT, 0f,
            TranslateAnimation.RELATIVE_TO_PARENT, -0.1f,
            TranslateAnimation.RELATIVE_TO_PARENT, 0f,
            TranslateAnimation.RELATIVE_TO_PARENT, -0.1f
        )
        animation2.duration = 6000
        animation2.repeatCount = -1
        animation2.repeatMode = TranslateAnimation.REVERSE
        binding.floatingCircle2.startAnimation(animation2)
    }

    /**
     * Configura os listeners de clique para todos os elementos interativos
     */
    private fun setupClickListeners() {
        // Botão de login
        binding.btnLogin.setOnClickListener {
            performLogin()
        }
        

        
        // Botão de cadastro (apenas para clientes)
        binding.btnSignUpClient.setOnClickListener {
            val intent = Intent(this, ClientSignUpActivity::class.java)
            startActivity(intent)
        }
        
        // Link para recuperação de senha
        binding.tvForgotPassword.setOnClickListener {
            handleForgotPassword()
        }
        
        // Links de privacidade e termos
        binding.tvPrivacyPolicy.setOnClickListener {
            openPrivacyPolicy()
        }
        
        binding.tvTermsOfService.setOnClickListener {
            openTermsOfService()
        }
        
        // Ler Termos de Uso do Cliente (na seção Criar Conta)
        binding.tvReadTermsClient.setOnClickListener {
            openTermsOfService()
        }
    }

    /**
     * Executa o processo de login com email e senha
     */
    private fun performLogin() {
        // Verificar se já está processando um login
        if (isLoading) return
        
        // Obter dados dos campos
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        
        // Limpar erros anteriores
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        
        // Validar campos
        if (!validateInputs(email, password)) {
            return
        }
        
        // Mostrar estado de carregamento
        setLoadingState(true)
        
        // Autenticar localmente
        authenticateLocally(email, password)
    }

    /**
     * Valida os campos de entrada do usuário
     * 
     * @param email Email inserido pelo usuário
     * @param password Senha inserida pelo usuário
     * @return true se todos os campos são válidos, false caso contrário
     */
    private fun validateInputs(email: String, password: String): Boolean {
        var isValid = true
        
        // Validar email
        when {
            email.isEmpty() -> {
                binding.tilEmail.error = getString(R.string.email_required)
                isValid = false
            }
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                binding.tilEmail.error = getString(R.string.email_invalid)
                isValid = false
            }
        }
        
        // Validar senha (DESENVOLVIMENTO: aceitar qualquer senha não vazia)
        when {
            password.isEmpty() -> {
                binding.tilPassword.error = getString(R.string.password_required)
                isValid = false
            }
            // Removida validação de comprimento mínimo para desenvolvimento
        }
        
        return isValid
    }

    /**
     * Autentica o usuário com Firebase
     * 
     * @param email Email do usuário
     * @param password Senha do usuário
     */
    private fun authenticateLocally(email: String, password: String) {
        lifecycleScope.launch {
            try {
                // Fazer login com Firebase
                val result = authManager.signIn(email, password)
                
                setLoadingState(false)
                
                if (result.isSuccess) {
                    // Login bem-sucedido
                    val userData = result.getOrNull()
                    val displayName = userData?.username ?: userData?.email ?: "Usuário"
                    
                    showSuccessMessage("✅ Login realizado com sucesso! Bem-vindo, $displayName!")
                    
                    // Navegar para a tela principal baseada no tipo de usuário
                    val intent = when (userData?.userType) {
                        "provider" -> Intent(this@MainActivity, ProviderHomeActivity::class.java)
                        "client" -> Intent(this@MainActivity, ClientHomeActivity::class.java)
                        else -> Intent(this@MainActivity, HomeActivity::class.java) // Fallback
                    }
                    startActivity(intent)
                    finish()
                } else {
                    handleLoginError(result.exceptionOrNull()?.message ?: "Erro de autenticação")
                }
                
            } catch (e: Exception) {
                setLoadingState(false)
                showErrorMessage("❌ ${getString(R.string.login_error)}")
            }
        }
    }

    /**
     * Trata erros de autenticação
     * 
     * @param errorMessage Mensagem de erro
     */
    private fun handleLoginError(errorMessage: String) {
        when {
            errorMessage.contains("não encontrado") || errorMessage.contains("no user record") -> {
                binding.tilEmail.error = "Usuário não encontrado"
                showErrorMessage("❌ Usuário não encontrado. Verifique o email ou cadastre-se.")
                binding.etEmail.requestFocus()
            }
            errorMessage.contains("Senha incorreta") || errorMessage.contains("password is invalid") || errorMessage.contains("wrong password") -> {
                binding.tilPassword.error = "Senha incorreta"
                showErrorMessage("❌ Senha incorreta")
                binding.etPassword.requestFocus()
            }
            errorMessage.contains("conexão") || errorMessage.contains("network") -> {
                showErrorMessage("❌ Erro de conexão. Verifique sua internet e tente novamente.")
                binding.tilEmail.error = " "
                binding.tilPassword.error = " "
            }
            errorMessage.contains("Dados do usuário não encontrados") -> {
                showErrorMessage("❌ Dados do usuário não encontrados. Entre em contato com o suporte.")
                binding.tilEmail.error = " "
                binding.tilPassword.error = " "
            }
            else -> {
                showErrorMessage("❌ $errorMessage")
                binding.tilEmail.error = " "
                binding.tilPassword.error = " "
            }
        }
    }

    /**
     * Controla o estado de carregamento da interface
     * 
     * @param loading true para mostrar carregamento, false para esconder
     */
    private fun setLoadingState(loading: Boolean) {
        isLoading = loading
        
        // Atualizar botão de login
        binding.btnLogin.apply {
            isEnabled = !loading
            text = if (loading) "Entrando..." else getString(R.string.login_button)
        }
        
        // Desabilitar outros botões durante o carregamento
        binding.tvForgotPassword.isEnabled = !loading
    }

    /**
     * Manipula o clique em "Esqueci minha senha"
     */
    private fun handleForgotPassword() {
        // Navegar para a tela de recuperação de senha, levando o email já digitado
        val intent = Intent(this, ForgotPasswordActivity::class.java)
        val typedEmail = binding.etEmail.text?.toString()?.trim()
        if (!typedEmail.isNullOrEmpty()) intent.putExtra("prefill_email", typedEmail)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * Abre a política de privacidade
     */
    private fun openPrivacyPolicy() {
        val intent = Intent(this, PrivacyPolicyActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * Abre os termos de uso
     */
    private fun openTermsOfService() {
        val intent = Intent(this, TermsOfServiceActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * Manipula o clique em "Cadastre-se"
     */
    private fun handleSignUp() {
        // Navegar para a tela de cadastro
        val intent = Intent(this, SignUpActivity::class.java)
        startActivity(intent)
    }

    /**
     * Exibe uma mensagem de sucesso com estilo específico
     * 
     * @param message Mensagem de sucesso a ser exibida
     */
    private fun showSuccessMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Exibe uma mensagem de erro com estilo específico
     * 
     * @param message Mensagem de erro a ser exibida
     */
    private fun showErrorMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Exibe uma mensagem toast para o usuário
     * 
     * @param message Mensagem a ser exibida
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ===== Permissões: Notificações (Android 13+) =====
    private val requestNotificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                showToast("Permissão de notificações negada. Você pode habilitar nas configurações.")
            }
        }

    private fun requestNotificationPermissionIfNeeded(force: Boolean = false) {
        if (!com.aquiresolve.app.utils.PermissionHelper.needsNotificationPermission()) return
        if (com.aquiresolve.app.utils.PermissionHelper.isNotificationPermissionGranted(this)) return

        requestNotificationsPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Limpa os recursos quando a activity é destruída
     */
    override fun onDestroy() {
        super.onDestroy()
        // Limpar recursos se necessário
    }
}
