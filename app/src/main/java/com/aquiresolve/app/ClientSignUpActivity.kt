package com.aquiresolve.app

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aquiresolve.app.databinding.ActivityClientSignupBinding
import kotlinx.coroutines.launch
import androidx.appcompat.app.AlertDialog
import com.aquiresolve.app.R

/**
 * ClientSignUpActivity - Tela de cadastro de clientes
 * 
 * Esta activity gerencia o cadastro de clientes com:
 * - Informações pessoais (CPF opcional)
 * - Endereço com CEP
 * - Validações específicas para clientes
 */
class ClientSignUpActivity : AppCompatActivity() {

    // ViewBinding para acesso aos elementos da interface
    private lateinit var binding: ActivityClientSignupBinding
    
    // Variáveis para controle de estado
    private var isLoading = false
    private lateinit var authManager: FirebaseAuthManager
    private var timeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        
        // Garantir Firebase inicializado
        if (!FirebaseConfig.isInitialized()) {
            FirebaseConfig.initialize(this)
        }
        
        // Inicializar AuthManager
        authManager = FirebaseAuthManager(this)
        
        // Inicializar ViewBinding
        binding = ActivityClientSignupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Configurar a interface
        setupUI()
        setupClickListeners()
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
     * Configura os listeners de clique para todos os elementos interativos
     */
    private fun setupClickListeners() {
        // Botão de cadastro
        binding.btnSignUp.setOnClickListener {
            performSignUp()
        }
        
        // Link para voltar ao login
        binding.tvSignIn.setOnClickListener {
            finish()
        }
        
        // Botão voltar
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // Ler Termos de Uso do Cliente
        binding.tvReadTerms.setOnClickListener {
            startActivity(Intent(this, TermsOfServiceActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    /**
     * Executa o processo de cadastro
     */
    private fun performSignUp() {
        // Verificar se já está processando um cadastro
        if (isLoading) return
        
        android.util.Log.d("ClientSignUp", "=== INICIANDO PROCESSO DE CADASTRO ===")
        
        // Obter dados dos campos
        val fullName = binding.etFullName.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()
        val termsAccepted = binding.cbTerms.isChecked
        
        // Validar dados de entrada
        if (!validateInputs(fullName, username, email, password, confirmPassword, termsAccepted)) {
            android.util.Log.e("ClientSignUp", "❌ VALIDAÇÃO FALHOU - Dados inválidos")
            return
        }
        
        android.util.Log.d("ClientSignUp", "✅ VALIDAÇÃO PASSOU - Dados válidos")
        
        // Limpar erros anteriores
        clearErrors()
        
        // Iniciar processo de cadastro
        setLoadingState(true)
        android.util.Log.d("ClientSignUp", "🔄 INICIANDO CRIAÇÃO DE CONTA...")
        
        // Criar conta de cliente
        createClientAccount(fullName, username, email, password)
        
        // Timeout de segurança (30 segundos)
        timeoutRunnable = Runnable {
            if (isLoading) {
                android.util.Log.w("ClientSignUp", "⚠️ TIMEOUT DE SEGURANÇA ATIVADO")
                handleSignUpError("Tempo limite excedido. Verifique sua conexão e tente novamente.")
            }
        }
        binding.root.postDelayed(timeoutRunnable!!, 30000) // 30 segundos
    }

    /**
     * Valida os campos de entrada do usuário
     * 
     * @param fullName Nome completo
     * @param username Nome de usuário
     * @param email Email
     * @param password Senha
     * @param confirmPassword Confirmação de senha
     * @param termsAccepted Termos aceitos
     * @return true se todos os campos são válidos, false caso contrário
     */
    private fun validateInputs(
        fullName: String, username: String, email: String, password: String, confirmPassword: String, termsAccepted: Boolean
    ): Boolean {
        android.util.Log.d("ClientSignUp", "=== INICIANDO VALIDAÇÃO ===")
        
        var isValid = true
        
        // Validar nome completo
        if (fullName.isEmpty()) {
            android.util.Log.e("ClientSignUp", "❌ ERRO: Nome completo vazio")
            binding.tilFullName.error = "Nome completo é obrigatório"
            isValid = false
        } else if (fullName.length < 3) {
            android.util.Log.e("ClientSignUp", "❌ ERRO: Nome completo muito curto")
            binding.tilFullName.error = "Nome completo deve ter pelo menos 3 caracteres"
            isValid = false
        } else {
            android.util.Log.d("ClientSignUp", "✅ Nome completo válido")
        }
        
        // Validar nome de usuário
        if (username.isEmpty()) {
            android.util.Log.e("ClientSignUp", "❌ ERRO: Nome de usuário vazio")
            binding.tilUsername.error = "Nome de usuário é obrigatório"
            isValid = false
        } else if (!authManager.isValidUsername(username)) {
            android.util.Log.e("ClientSignUp", "❌ ERRO: Nome de usuário inválido")
            binding.tilUsername.error = "Nome de usuário deve ter 3-60 caracteres (pode ser igual ao nome completo)"
            isValid = false
        } else {
            android.util.Log.d("ClientSignUp", "✅ Nome de usuário válido")
        }
        
        // Validar email
        if (email.isEmpty()) {
            android.util.Log.e("ClientSignUp", "❌ ERRO: Email vazio")
            binding.tilEmail.error = "E-mail é obrigatório"
            isValid = false
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            android.util.Log.e("ClientSignUp", "❌ ERRO: Email inválido")
            binding.tilEmail.error = "E-mail inválido"
            isValid = false
        } else {
            android.util.Log.d("ClientSignUp", "✅ Email válido")
        }
        
        // Validar senha
        if (password.isEmpty()) {
            android.util.Log.e("ClientSignUp", "❌ ERRO: Senha vazia")
            binding.tilPassword.error = "Senha é obrigatória"
            isValid = false
        } else if (password.length < 6) {
            android.util.Log.e("ClientSignUp", "❌ ERRO: Senha muito curta (${password.length})")
            binding.tilPassword.error = "Senha deve ter pelo menos 6 caracteres"
            isValid = false
        } else {
            android.util.Log.d("ClientSignUp", "✅ Senha válida")
        }
        
        // Validar confirmação de senha
        if (confirmPassword.isEmpty()) {
            android.util.Log.e("ClientSignUp", "❌ ERRO: Confirmação de senha vazia")
            binding.tilConfirmPassword.error = "Confirmação de senha é obrigatória"
            isValid = false
        } else if (password != confirmPassword) {
            android.util.Log.e("ClientSignUp", "❌ ERRO: Senhas não coincidem")
            binding.tilConfirmPassword.error = "Senhas não coincidem"
            isValid = false
        } else {
            android.util.Log.d("ClientSignUp", "✅ Confirmação de senha válida")
        }
        
        // Validar termos
        if (!termsAccepted) {
            android.util.Log.e("ClientSignUp", "❌ ERRO: Termos não aceitos")
            showErrorMessage("Você deve aceitar os termos de uso")
            isValid = false
        } else {
            android.util.Log.d("ClientSignUp", "✅ Termos aceitos")
        }
        
        android.util.Log.d("ClientSignUp", "=== RESULTADO DA VALIDAÇÃO: ${if (isValid) "✅ VÁLIDO" else "❌ INVÁLIDO"} ===")
        
        return isValid
    }

    /**
     * Valida CPF (formato básico)
     */
    private fun isValidCpf(cpf: String): Boolean {
        val cleanCpf = cpf.replace(Regex("[^0-9]"), "")
        return cleanCpf.length == 11
    }

    /**
     * Cria conta de cliente com Firebase
     */
    private fun createClientAccount(fullName: String, username: String, email: String, password: String) {
        lifecycleScope.launch {
            try {
                android.util.Log.d("ClientSignUp", "🔄 VERIFICANDO NOME DE USUÁRIO...")
                
                // Verificar se o nome de usuário já existe
                if (authManager.isUsernameTaken(username)) {
                    android.util.Log.e("ClientSignUp", "❌ NOME DE USUÁRIO JÁ EXISTE")
                    handleSignUpError("O nome de usuário '$username' já está sendo usado por outra pessoa. Dois usuários não podem ter o mesmo nome de usuário; escolha outro ou adicione um sobrenome/número.")
                    return@launch
                }
                
                android.util.Log.d("ClientSignUp", "✅ NOME DE USUÁRIO DISPONÍVEL")
                android.util.Log.d("ClientSignUp", "🔄 CRIANDO DADOS DO USUÁRIO...")
                
                // Criar dados do usuário
                val userData = FirebaseAuthManager.UserData(
                    uid = "",
                    email = email,
                    fullName = fullName,
                    username = username,
                    phone = "", // Telefone vazio, será preenchido depois
                    userType = FirebaseAuthManager.USER_TYPE_CLIENT,
                    isVerified = false
                )
                
                android.util.Log.d("ClientSignUp", "🔄 CHAMANDO FIREBASE AUTH MANAGER...")
                
                // Criar conta com Firebase
                val result = authManager.signUp(email, password, userData)
                
                android.util.Log.d("ClientSignUp", "📋 RESULTADO DO FIREBASE:")
                android.util.Log.d("ClientSignUp", "- Sucesso: ${result.isSuccess}")
                android.util.Log.d("ClientSignUp", "- Erro: ${result.exceptionOrNull()?.message}")
                
                if (result.isSuccess) {
                    android.util.Log.d("ClientSignUp", "🎉 CADASTRO REALIZADO COM SUCESSO!")
                    
                    // Resetar estado de carregamento antes de mostrar diálogo
                    setLoadingState(false)
                    
                    // Fazer logout imediatamente após criar a conta
                    // para que o usuário precise fazer login manualmente
                    authManager.signOut()
                    android.util.Log.d("ClientSignUp", "🔄 Logout realizado após criação da conta")
                    
                    // Mostrar diálogo de sucesso profissional
                    showSuccessDialog(email)
                } else {
                    android.util.Log.e("ClientSignUp", "❌ ERRO NO CADASTRO:")
                    android.util.Log.e("ClientSignUp", "- Mensagem: ${result.exceptionOrNull()?.message}")
                    android.util.Log.e("ClientSignUp", "- Causa: ${result.exceptionOrNull()?.cause}")
                    
                    handleSignUpError(result.exceptionOrNull()?.message ?: "Erro ao criar conta")
                }
                
            } catch (e: Exception) {
                android.util.Log.e("ClientSignUp", "💥 EXCEÇÃO DURANTE CADASTRO:", e)
                handleSignUpError("Erro inesperado: ${e.message}")
            } finally {
                // Garantir que o estado de carregamento seja sempre resetado
                setLoadingState(false)
            }
        }
    }

    /**
     * Trata erros de cadastro
     */
    private fun handleSignUpError(errorMessage: String) {
        // Garantir que o estado de carregamento seja resetado
        setLoadingState(false)
        
        when {
            errorMessage.contains("nome de usuário", ignoreCase = true) && errorMessage.contains("uso", ignoreCase = true) -> {
                binding.tilUsername.error = "Esse nome de usuário já pertence a outra conta"
                showErrorMessage("❌ $errorMessage")
                binding.etUsername.requestFocus()
            }
            errorMessage.contains("e-mail já está em uso", ignoreCase = true) || 
            errorMessage.contains("email address is already in use", ignoreCase = true) -> {
                binding.tilEmail.error = "E-mail já está em uso"
                showErrorMessage("❌ Este e-mail já está em uso")
                binding.etEmail.requestFocus()
            }
            errorMessage.contains("Senha inválida") -> {
                binding.tilPassword.error = "Senha inválida"
                showErrorMessage("❌ Senha inválida")
                binding.etPassword.requestFocus()
            }
            errorMessage.contains("conexão") || errorMessage.contains("internet") -> {
                showErrorMessage("❌ Erro de conexão. Verifique sua internet e tente novamente.")
            }
            else -> {
                showErrorMessage("❌ $errorMessage")
            }
        }
    }

    /**
     * Limpa todos os erros dos campos
     */
    private fun clearErrors() {
        binding.tilFullName.error = null
        binding.tilUsername.error = null
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirmPassword.error = null
    }

    /**
     * Controla o estado de carregamento da interface
     */
    private fun setLoadingState(loading: Boolean) {
        android.util.Log.d("ClientSignUp", "🔄 SETLOADINGSTATE: $loading")
        isLoading = loading
        
        // Cancelar timeout se o carregamento foi finalizado
        if (!loading) {
            timeoutRunnable?.let { runnable ->
                binding.root.removeCallbacks(runnable)
                timeoutRunnable = null
                android.util.Log.d("ClientSignUp", "⏰ TIMEOUT CANCELADO")
            }
        }
        
        // Atualizar botão de cadastro
        binding.btnSignUp.apply {
            isEnabled = !loading
            text = if (loading) "Criando conta..." else "Criar Conta"
        }
        
        android.util.Log.d("ClientSignUp", "🔘 BOTÃO ATUALIZADO: ${if (loading) "Criando conta..." else "Criar Conta"}")
        
        // Desabilitar outros elementos durante o carregamento
        binding.tvSignIn.isEnabled = !loading
    }

    /**
     * Exibe uma mensagem de sucesso
     */
    private fun showSuccessMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Exibe uma mensagem de erro
     */
    private fun showErrorMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Mostra diálogo de sucesso profissional
     */
    private fun showSuccessDialog(email: String) {
        android.util.Log.d("ClientSignUp", "🎉 MOSTRANDO DIÁLOGO DE SUCESSO")
        
        // Garantir que o estado de carregamento seja resetado
        setLoadingState(false)
        
        try {
            val dialogView = layoutInflater.inflate(R.layout.dialog_success_signup, null)
            val dialog = AlertDialog.Builder(this, R.style.SuccessDialogTheme)
                .setView(dialogView)
                .setCancelable(false)
                .create()

            // Configurar animação de entrada
            dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation

            // Configurar o diálogo
            dialog.show()
            android.util.Log.d("ClientSignUp", "✅ DIÁLOGO EXIBIDO COM SUCESSO")

            // Configurar botão de continuar
            dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnContinue).setOnClickListener {
                android.util.Log.d("ClientSignUp", "🔄 BOTÃO CONTINUAR CLICADO")
                dialog.dismiss()
                navigateToLoginWithAnimation()
            }

            // Configurar texto do email
            dialogView.findViewById<android.widget.TextView>(R.id.tvEmail).text = email
            
        } catch (e: Exception) {
            android.util.Log.e("ClientSignUp", "❌ ERRO AO MOSTRAR DIÁLOGO:", e)
            // Fallback: mostrar toast e navegar
            showSuccessMessage("✅ Conta criada com sucesso!")
            navigateToLoginWithAnimation()
        }
    }

    /**
     * Navega para a tela de login com animação elegante
     */
    private fun navigateToLoginWithAnimation() {
        // Criar intent para MainActivity
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("show_success_message", true)
            putExtra("success_message", "✅ Conta criada com sucesso! Faça login para continuar.")
        }

        // Iniciar a activity com animação
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        finish()
    }

    /**
     * Limpa os recursos quando a activity é destruída
     */
    override fun onDestroy() {
        super.onDestroy()
        // Cancelar timeout se ainda estiver ativo
        timeoutRunnable?.let { runnable ->
            binding.root.removeCallbacks(runnable)
            timeoutRunnable = null
        }
    }
} 
