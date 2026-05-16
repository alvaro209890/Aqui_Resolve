package com.aquiresolve.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aquiresolve.app.databinding.ActivityPersonalDataBinding
import kotlinx.coroutines.launch

class PersonalDataActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonalDataBinding
    private lateinit var authManager: FirebaseAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Garantir Firebase inicializado
        if (!FirebaseConfig.isInitialized()) {
            FirebaseConfig.initialize(this)
        }

        authManager = FirebaseAuthManager(this)
        binding = ActivityPersonalDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupListeners()
        loadUser()
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        window.statusBarColor = ContextCompat.getColor(this, R.color.primary_color)
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSaveEnabled() }
            override fun afterTextChanged(s: Editable?) {}
        }

        binding.etFullName.addTextChangedListener(textWatcher)
        binding.etUsername.addTextChangedListener(textWatcher)
        binding.etPhone.addTextChangedListener(textWatcher)

        binding.btnSave.setOnClickListener { onSave() }
    }

    private fun loadUser() {
        val local = authManager.getLocalUserData()
        if (local != null) {
            binding.etFullName.setText(local.fullName)
            binding.etUsername.setText(local.username)
            binding.etEmail.setText(local.email)
            binding.etPhone.setText(local.phone)
            updateSaveEnabled()
        }

        // Buscar dados mais recentes do Firestore
        lifecycleScope.launch {
            try {
                val fresh = authManager.getUserDataFromFirestore(local?.uid ?: return@launch)
                if (fresh != null) {
                    binding.etFullName.setText(fresh.fullName)
                    binding.etUsername.setText(fresh.username)
                    binding.etEmail.setText(fresh.email)
                    binding.etPhone.setText(fresh.phone)
                    updateSaveEnabled()
                }
            } catch (_: Exception) { }
        }
    }

    private fun onSave() {
        val user = authManager.getLocalUserData() ?: return

        clearErrors()

        val newFullName = binding.etFullName.text?.toString()?.trim().orEmpty()
        val newUsername = binding.etUsername.text?.toString()?.trim().orEmpty()
        val newPhone = binding.etPhone.text?.toString()?.trim().orEmpty()

        var hasError = false
        if (newFullName.isEmpty()) {
            binding.tilFullName.error = "Nome é obrigatório"
            hasError = true
        }

        if (newUsername.isEmpty() || !authManager.isValidUsername(newUsername)) {
            binding.tilUsername.error = "Usuário inválido (3-60; pode ser igual ao nome completo)"
            hasError = true
        }

        if (newPhone.isNotEmpty() && newPhone.length < 8) {
            binding.tilPhone.error = "Telefone muito curto"
            hasError = true
        }

        if (hasError) return

        showLoading(true)

        lifecycleScope.launch {
            try {
                // Atualizar username se mudou
                val current = authManager.getLocalUserData() ?: user
                if (newUsername != current.username) {
                    if (!authManager.canEditUsername(current.uid)) {
                        binding.tilUsername.error = "Você só pode editar o usuário a cada 15 dias"
                        showLoading(false)
                        return@launch
                    }
                    // Verificar se já está em uso
                    if (authManager.isUsernameTaken(newUsername)) {
                        binding.tilUsername.error = "Nome de usuário já em uso"
                        showLoading(false)
                        return@launch
                    }
                    val usernameResult = authManager.updateUsername(current.uid, newUsername)
                    if (usernameResult.isFailure) {
                        binding.tilUsername.error = usernameResult.exceptionOrNull()?.message ?: "Erro ao atualizar usuário"
                        showLoading(false)
                        return@launch
                    }
                }

                // Atualizar perfil (nome, telefone)
                val updated = FirebaseAuthManager.UserData(
                    uid = current.uid,
                    email = current.email,
                    fullName = newFullName,
                    username = newUsername,
                    phone = newPhone,
                    userType = current.userType,
                    isVerified = current.isVerified,
                    profileImageUrl = current.profileImageUrl,
                    lastUsernameEdit = authManager.getLocalUserData()?.lastUsernameEdit ?: current.lastUsernameEdit
                )

                val profileResult = authManager.updateUserProfile(updated)
                if (profileResult.isSuccess) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    binding.tilFullName.error = profileResult.exceptionOrNull()?.message ?: "Erro ao salvar"
                    showLoading(false)
                }
            } catch (e: Exception) {
                binding.tilFullName.error = e.message ?: "Erro inesperado"
                showLoading(false)
            }
        }
    }

    private fun clearErrors() {
        binding.tilFullName.error = null
        binding.tilUsername.error = null
        binding.tilPhone.error = null
    }

    private fun updateSaveEnabled() {
        val user = authManager.getLocalUserData() ?: return
        val changed = binding.etFullName.text?.toString()?.trim() != user.fullName ||
                binding.etUsername.text?.toString()?.trim() != user.username ||
                binding.etPhone.text?.toString()?.trim() != user.phone
        binding.btnSave.isEnabled = changed
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSave.isEnabled = !loading
    }
}







