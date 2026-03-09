package com.aquiresolve.app

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aquiresolve.app.databinding.ActivityPrivacySettingsBinding
import com.aquiresolve.app.utils.PermissionHelper
import kotlinx.coroutines.launch

class PrivacySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacySettingsBinding
    private lateinit var authManager: FirebaseAuthManager
    private lateinit var privacyManager: FirebasePrivacyManager
    private var currentSettings: FirebasePrivacyManager.PrivacySettings? = null
    private var isBindingSettings = false

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            updatePrivacySetting("notifications_enabled", true)
        } else {
            Toast.makeText(this, "Permissão negada. Habilite nas configurações do app para receber notificações.", Toast.LENGTH_LONG).show()
            isBindingSettings = true
            binding.switchNotifications.isChecked = false
            isBindingSettings = false
            updatePrivacySetting("notifications_enabled", false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Garantir Firebase inicializado
        if (!FirebaseConfig.isInitialized()) {
            FirebaseConfig.initialize(this)
        }
        
        binding = ActivityPrivacySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = FirebaseAuthManager(this)
        privacyManager = FirebasePrivacyManager(this)
        
        setupToolbar()
        setupClickListeners()
        loadPrivacySettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "Configurações de Privacidade"
        }
    }

    private fun setupClickListeners() {
        // Notificações - solicitar permissão ao ativar (Android 13+)
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingSettings) return@setOnCheckedChangeListener
            if (isChecked && PermissionHelper.needsNotificationPermission() && !PermissionHelper.isNotificationPermissionGranted(this)) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                // Salvar será feito no callback do launcher se concedido
                return@setOnCheckedChangeListener
            }
            updatePrivacySetting("notifications_enabled", isChecked)
        }

        // Compartilhamento de dados
        binding.switchDataSharing.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingSettings) return@setOnCheckedChangeListener
            updatePrivacySetting("data_sharing_enabled", isChecked)
        }

        // Removidos: localização e perfil público

        // Botões de ação
        binding.btnExportData.setOnClickListener {
            exportUserData()
        }

        binding.btnDeleteAccount.setOnClickListener {
            showDeleteAccountDialog()
        }

        binding.btnPrivacyPolicy.setOnClickListener {
            openPrivacyPolicy()
        }

        binding.btnTermsOfService.setOnClickListener {
            openTermsOfService()
        }
    }

    private fun loadPrivacySettings() {
        lifecycleScope.launch {
            try {
                showLoading(true)
                
                val result = privacyManager.loadPrivacySettings()
                
                if (result.isSuccess) {
                    currentSettings = result.getOrNull()
                    updateUI(currentSettings!!)
                    showToast("✅ Configurações carregadas")
                    // Solicitar permissão se notificações habilitadas mas permissão negada (Android 13+)
                    if (currentSettings!!.notificationsEnabled &&
                        PermissionHelper.needsNotificationPermission() &&
                        !PermissionHelper.isNotificationPermissionGranted(this@PrivacySettingsActivity)) {
                        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    showToast("⚠️ Erro ao carregar configurações: ${result.exceptionOrNull()?.message}")
                }
                
            } catch (e: Exception) {
                showToast("❌ Erro ao carregar configurações: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun updateUI(settings: FirebasePrivacyManager.PrivacySettings) {
        isBindingSettings = true
        binding.switchNotifications.isChecked = settings.notificationsEnabled
        binding.switchDataSharing.isChecked = settings.dataSharingEnabled
        isBindingSettings = false
        // Removidos: localização e perfil público
    }

    private fun updatePrivacySetting(settingName: String, value: Boolean) {
        lifecycleScope.launch {
            try {
                val result = privacyManager.updatePrivacySetting(settingName, value)
                
                if (result.isSuccess) {
                    val message = when (settingName) {
                        "notifications_enabled" -> if (value) "✅ Notificações ativadas" else "🔕 Notificações desativadas"
                        "data_sharing_enabled" -> if (value) "✅ Compartilhamento ativado" else "🔒 Compartilhamento desativado"
                        // Removidos: localização e perfil público
                        else -> "✅ Configuração atualizada"
                    }
                    showToast(message)
                    
                    // Atualizar configurações locais
                    currentSettings?.let { settings ->
                        val updatedSettings = when (settingName) {
                            "notifications_enabled" -> settings.copy(notificationsEnabled = value)
                            "data_sharing_enabled" -> settings.copy(dataSharingEnabled = value)
                            // Removidos: localização e perfil público
                            else -> settings
                        }
                        currentSettings = updatedSettings
                    }
                } else {
                    showToast("❌ Erro ao atualizar configuração: ${result.exceptionOrNull()?.message}")
                    // Reverter UI em caso de erro
                    loadPrivacySettings()
                }
                
            } catch (e: Exception) {
                showToast("❌ Erro ao atualizar configuração: ${e.message}")
                loadPrivacySettings()
            }
        }
    }

    private fun exportUserData() {
        lifecycleScope.launch {
            try {
                binding.btnExportData.isEnabled = false
                binding.btnExportData.text = "Exportando..."
                showToast("📤 Iniciando exportação de dados...")
                
                val result = privacyManager.exportUserData()
                
                if (result.isSuccess) {
                    val exportId = result.getOrNull()
                    showToast("✅ Dados exportados com sucesso! ID: ${exportId?.takeLast(8)}")
                    
                    // Mostrar diálogo com informações da exportação
                    showExportSuccessDialog(exportId ?: "")
                } else {
                    showToast("❌ Erro ao exportar dados: ${result.exceptionOrNull()?.message}")
                }
                
            } catch (e: Exception) {
                showToast("❌ Erro ao exportar dados: ${e.message}")
            } finally {
                binding.btnExportData.isEnabled = true
                binding.btnExportData.text = "Exportar Dados"
            }
        }
    }

    private fun showExportSuccessDialog(exportId: String) {
        AlertDialog.Builder(this)
            .setTitle("✅ Exportação Concluída")
            .setMessage("""
                Seus dados foram exportados com sucesso!
                
                ID da Exportação: ${exportId.takeLast(8).uppercase()}
                Data: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date())}
                
                Os dados incluem:
                • Informações do perfil
                • Configurações de privacidade
                • Histórico de pedidos
                • Dados de uso do app
                
                Os dados ficam disponíveis por 30 dias para download.
            """.trimIndent())
            .setPositiveButton("Entendi") { _, _ -> }
            .setNeutralButton("Ver Detalhes") { _, _ ->
                showExportDetailsDialog(exportId)
            }
            .show()
    }

    private fun showExportDetailsDialog(exportId: String) {
        AlertDialog.Builder(this)
            .setTitle("📋 Detalhes da Exportação")
            .setMessage("""
                ID: ${exportId.uppercase()}
                Status: Disponível
                Tamanho: Aproximadamente 2-5 MB
                Formato: JSON estruturado
                Validade: 30 dias
                
                Para solicitar uma nova exportação, aguarde pelo menos 24 horas.
            """.trimIndent())
            .setPositiveButton("Fechar") { _, _ -> }
            .show()
    }

    private fun showDeleteAccountDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "Digite EXCLUIR para confirmar"
            setTextColor(resources.getColor(com.aquiresolve.app.R.color.text_primary, null))
            setHintTextColor(resources.getColor(com.aquiresolve.app.R.color.text_secondary, null))
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("🗑️ Excluir Conta")
            .setMessage("""
                Tem certeza que deseja excluir sua conta?

                ⚠️ ATENÇÃO: Esta ação é IRREVERSÍVEL!

                Serão excluídos permanentemente:
                • Todos os seus dados pessoais
                • Histórico de pedidos
                • Configurações de privacidade
                • Dados de uso do aplicativo

                Se você tem pedidos em andamento, eles serão cancelados automaticamente.

                Digite "EXCLUIR" para confirmar:
            """.trimIndent())
            .setView(editText)
            .setPositiveButton("Excluir Conta") { _, _ ->
                val confirmationText = editText.text.toString().trim()

                if (confirmationText == "EXCLUIR") {
                    deleteUserAccount()
                } else {
                    showToast("❌ Confirmação incorreta. Digite 'EXCLUIR' para confirmar.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun deleteUserAccount() {
        lifecycleScope.launch {
            try {
                binding.btnDeleteAccount.isEnabled = false
                binding.btnDeleteAccount.text = "Excluindo..."
                showToast("🗑️ Excluindo conta e todos os dados...")
                
                val result = privacyManager.deleteUserAccount()
                
                if (result.isSuccess) {
                    showToast("✅ Conta excluída com sucesso")
                    
                    // Fazer logout e voltar para tela de login
                    authManager.signOut()
                    val intent = Intent(this@PrivacySettingsActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                } else {
                    showToast("❌ Erro ao excluir conta: ${result.exceptionOrNull()?.message}")
                    binding.btnDeleteAccount.isEnabled = true
                    binding.btnDeleteAccount.text = "Excluir Conta"
                }
                
            } catch (e: Exception) {
                showToast("❌ Erro ao excluir conta: ${e.message}")
                binding.btnDeleteAccount.isEnabled = true
                binding.btnDeleteAccount.text = "Excluir Conta"
            }
        }
    }

    private fun openPrivacyPolicy() {
        val intent = Intent(this, PrivacyPolicyActivity::class.java)
        startActivity(intent)
    }

    private fun openTermsOfService() {
        val intent = Intent(this, TermsOfServiceActivity::class.java)
        startActivity(intent)
    }

    private fun showLoading(loading: Boolean) {
        binding.loadingState.visibility = if (loading) View.VISIBLE else View.GONE
        binding.contentLayout.visibility = if (loading) View.GONE else View.VISIBLE
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}




