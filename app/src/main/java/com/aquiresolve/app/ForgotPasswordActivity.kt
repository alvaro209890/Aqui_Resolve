package com.aquiresolve.app

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aquiresolve.app.databinding.ActivityForgotPasswordBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupClickListeners()

        // Pré-preenche o email vindo do login/cadastro, se houver
        intent.getStringExtra("prefill_email")?.takeIf { it.isNotBlank() }?.let { email ->
            binding.etEmail.setText(email)
        }
    }

    private fun setupUI() {
        // Configurar status bar transparente
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    private fun setupClickListeners() {
        // Botão voltar
        binding.btnBack.setOnClickListener {
            onBackPressed()
        }

        // Botão enviar instruções
        binding.btnSendRecovery.setOnClickListener {
            sendRecoveryEmail()
        }

        // Link voltar para login
        binding.tvBackToLogin.setOnClickListener {
            finish()
        }
    }

    private fun sendRecoveryEmail() {
        if (isLoading) return

        val email = binding.etEmail.text.toString().trim()

        // Validar email
        if (!validateEmail(email)) {
            return
        }

        setLoadingState(true)

        lifecycleScope.launch {
            try {
                val auth = FirebaseConfig.getAuth()
                // .await() garante que o envio realmente concluiu antes de mostrar sucesso;
                // sem isso, qualquer falha (rede etc.) era engolida e o sucesso aparecia sempre.
                auth.sendPasswordResetEmail(email).await()

                setLoadingState(false)
                showSuccessMessage()

            } catch (e: Exception) {
                setLoadingState(false)
                handleError(e.message ?: "Erro ao enviar email de recuperação")
            }
        }
    }

    private fun validateEmail(email: String): Boolean {
        if (email.isEmpty()) {
            binding.tilEmail.error = "Email é obrigatório"
            binding.etEmail.requestFocus()
            return false
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Email inválido"
            binding.etEmail.requestFocus()
            return false
        }

        binding.tilEmail.error = null
        return true
    }

    private fun setLoadingState(loading: Boolean) {
        isLoading = loading

        binding.btnSendRecovery.apply {
            isEnabled = !loading
            text = if (loading) "Enviando..." else "Enviar Instruções"
        }

        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showSuccessMessage() {
        // Esconde só o formulário (o card permanece) e revela a mensagem de sucesso.
        // Antes escondia o cardRecovery inteiro — e como o successLayout fica DENTRO dele,
        // a tela ficava em branco mesmo com o envio bem-sucedido.
        binding.formLayout.visibility = View.GONE
        binding.successLayout.visibility = View.VISIBLE

        Toast.makeText(
            this,
            "✅ Se houver uma conta com este email, você receberá o link. Verifique a caixa de entrada e o spam.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun handleError(errorMessage: String) {
        val msg = errorMessage.lowercase()
        when {
            // Email mal formatado (validação extra do Firebase)
            msg.contains("badly formatted") || msg.contains("invalid_email") || msg.contains("invalid email") -> {
                binding.tilEmail.error = "Email inválido"
                binding.etEmail.requestFocus()
            }
            // Excesso de tentativas
            msg.contains("too many") || msg.contains("too-many-requests") -> {
                Toast.makeText(this, "❌ Muitas tentativas. Aguarde alguns minutos e tente novamente.", Toast.LENGTH_LONG).show()
            }
            // Sem conexão
            msg.contains("network") || msg.contains("timeout") || msg.contains("unreachable") -> {
                Toast.makeText(this, "❌ Erro de conexão. Verifique sua internet e tente novamente.", Toast.LENGTH_LONG).show()
            }
            else -> {
                Toast.makeText(this, "❌ Não foi possível enviar agora. Tente novamente.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
} 