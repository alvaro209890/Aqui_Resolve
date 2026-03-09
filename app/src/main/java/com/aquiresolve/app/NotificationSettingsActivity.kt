package com.aquiresolve.app

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aquiresolve.app.databinding.ActivityNotificationSettingsBinding
import com.aquiresolve.app.utils.PermissionHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Activity para configurações de notificações
 */
class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationSettingsBinding
    private lateinit var privacyManager: FirebasePrivacyManager
    private var isBindingSettings = false
    
    // Formato de hora
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    
    // Horários padrão
    private var quietHoursStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 22)
        set(Calendar.MINUTE, 0)
    }
    
    private var quietHoursEnd = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 7)
        set(Calendar.MINUTE, 0)
    }
    
    // Launcher para permissão de notificações (Android 13+)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Permissão negada. Habilite nas configurações do app para receber notificações.", Toast.LENGTH_LONG).show()
            isBindingSettings = true
            binding.switchNotifications.isChecked = false
            isBindingSettings = false
            updateNotificationDependentState(false)
            lifecycleScope.launch {
                privacyManager.updatePrivacySetting("notifications_enabled", false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inicializar ViewBinding
        binding = ActivityNotificationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Inicializar managers
        privacyManager = FirebasePrivacyManager(this)
        
        // Configurar a interface
        setupUI()
        setupClickListeners()
        loadSettings()
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
        
        // Atualizar textos dos horários
        updateQuietHoursTexts()
    }

    /**
     * Configura os listeners de clique para todos os elementos interativos
     */
    private fun setupClickListeners() {
        // Botão voltar da toolbar
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        
        // Switch principal de notificações - solicitar permissão ao ativar
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingSettings) return@setOnCheckedChangeListener
            if (isChecked && PermissionHelper.needsNotificationPermission() && !PermissionHelper.isNotificationPermissionGranted(this)) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return@setOnCheckedChangeListener
            }
            updateNotificationDependentState(isChecked)
        }
        
        // Switch de modo silencioso
        binding.switchQuietHours.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingSettings) return@setOnCheckedChangeListener
            updateQuietHoursVisibility(isChecked && binding.switchNotifications.isChecked)
        }
        
        // Seleção de horário de início
        binding.layoutQuietHoursStart.setOnClickListener {
            showTimePickerDialog(true)
        }
        
        // Seleção de horário de fim
        binding.layoutQuietHoursEnd.setOnClickListener {
            showTimePickerDialog(false)
        }
        
        // Botão salvar
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }

    /**
     * Carrega as configurações salvas
     */
    private fun loadSettings() {
        lifecycleScope.launch {
            try {
                // Carregar configurações de privacidade
                val notificationsEnabled = privacyManager.isSettingEnabled("notifications_enabled")
                val soundEnabled = privacyManager.isSettingEnabled("notification_sound_enabled")
                val vibrationEnabled = privacyManager.isSettingEnabled("notification_vibration_enabled")
                val orderNotifications = privacyManager.isSettingEnabled("order_notifications_enabled")
                val chatNotifications = privacyManager.isSettingEnabled("chat_notifications_enabled")
                val paymentNotifications = privacyManager.isSettingEnabled("payment_notifications_enabled")
                val quietHoursEnabled = privacyManager.isSettingEnabled("quiet_hours_enabled")
                val quietHoursStartStr = privacyManager.getSettingString("quiet_hours_start", "22:00")
                val quietHoursEndStr = privacyManager.getSettingString("quiet_hours_end", "07:00")
                
                // Aplicar horários de silêncio
                try {
                    val (startH, startM) = quietHoursStartStr.split(":").map { it.toIntOrNull() ?: 0 }
                    quietHoursStart.set(Calendar.HOUR_OF_DAY, startH)
                    quietHoursStart.set(Calendar.MINUTE, startM)
                    val (endH, endM) = quietHoursEndStr.split(":").map { it.toIntOrNull() ?: 0 }
                    quietHoursEnd.set(Calendar.HOUR_OF_DAY, endH)
                    quietHoursEnd.set(Calendar.MINUTE, endM)
                    updateQuietHoursTexts()
                } catch (_: Exception) { }
                
                // Aplicar configurações aos switches
                isBindingSettings = true
                binding.switchNotifications.isChecked = notificationsEnabled
                binding.switchNotificationSound.isChecked = soundEnabled
                binding.switchNotificationVibration.isChecked = vibrationEnabled
                binding.switchOrderNotifications.isChecked = orderNotifications
                binding.switchChatNotifications.isChecked = chatNotifications
                binding.switchPaymentNotifications.isChecked = paymentNotifications
                binding.switchQuietHours.isChecked = quietHoursEnabled
                isBindingSettings = false
                updateNotificationDependentState(notificationsEnabled)
                
                // Solicitar permissão ao carregar se notificações habilitadas mas permissão negada (Android 13+)
                if (notificationsEnabled && PermissionHelper.needsNotificationPermission() && !PermissionHelper.isNotificationPermissionGranted(this@NotificationSettingsActivity)) {
                    requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            } catch (e: Exception) {
                showToast("Erro ao carregar configurações: ${e.message}")
            }
        }
    }

    /**
     * Salva as configurações
     */
    private fun saveSettings() {
        lifecycleScope.launch {
            try {
                if (binding.switchNotifications.isChecked &&
                    PermissionHelper.needsNotificationPermission() &&
                    !PermissionHelper.isNotificationPermissionGranted(this@NotificationSettingsActivity)
                ) {
                    showToast("Permita notificações no sistema para habilitar este recurso")
                    return@launch
                }

                // Salvar configurações
                updateBooleanSettingOrThrow("notifications_enabled", binding.switchNotifications.isChecked)
                updateBooleanSettingOrThrow("notification_sound_enabled", binding.switchNotificationSound.isChecked)
                updateBooleanSettingOrThrow("notification_vibration_enabled", binding.switchNotificationVibration.isChecked)
                updateBooleanSettingOrThrow("order_notifications_enabled", binding.switchOrderNotifications.isChecked)
                updateBooleanSettingOrThrow("chat_notifications_enabled", binding.switchChatNotifications.isChecked)
                updateBooleanSettingOrThrow("payment_notifications_enabled", binding.switchPaymentNotifications.isChecked)
                updateBooleanSettingOrThrow("quiet_hours_enabled", binding.switchQuietHours.isChecked)
                
                // Salvar horários de silêncio se habilitado
                if (binding.switchQuietHours.isChecked) {
                    val startTime = timeFormat.format(quietHoursStart.time)
                    val endTime = timeFormat.format(quietHoursEnd.time)
                    updateStringSettingOrThrow("quiet_hours_start", startTime)
                    updateStringSettingOrThrow("quiet_hours_end", endTime)
                }
                
                showToast("✅ Configurações salvas com sucesso!")
                finish()
                
            } catch (e: Exception) {
                showToast("❌ Erro ao salvar configurações: ${e.message}")
            }
        }
    }

    /**
     * Mostra o seletor de horário
     */
    private fun showTimePickerDialog(isStartTime: Boolean) {
        val calendar = if (isStartTime) quietHoursStart else quietHoursEnd
        
        val timePickerDialog = TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                calendar.set(Calendar.MINUTE, minute)
                updateQuietHoursTexts()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        )
        
        timePickerDialog.show()
    }

    /**
     * Atualiza os textos dos horários
     */
    private fun updateQuietHoursTexts() {
        binding.tvQuietHoursStart.text = timeFormat.format(quietHoursStart.time)
        binding.tvQuietHoursEnd.text = timeFormat.format(quietHoursEnd.time)
    }

    private fun updateQuietHoursVisibility(visible: Boolean) {
        binding.layoutQuietHoursStart.visibility = if (visible) View.VISIBLE else View.GONE
        binding.layoutQuietHoursEnd.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateNotificationDependentState(notificationsEnabled: Boolean) {
        val enabledViews = listOf(
            binding.switchNotificationSound,
            binding.switchNotificationVibration,
            binding.switchOrderNotifications,
            binding.switchChatNotifications,
            binding.switchPaymentNotifications,
            binding.switchQuietHours
        )
        enabledViews.forEach { view ->
            view.isEnabled = notificationsEnabled
            view.alpha = if (notificationsEnabled) 1.0f else 0.5f
        }

        updateQuietHoursVisibility(notificationsEnabled && binding.switchQuietHours.isChecked)
    }

    private suspend fun updateBooleanSettingOrThrow(settingName: String, value: Boolean) {
        val result = privacyManager.updatePrivacySetting(settingName, value)
        if (result.isFailure) {
            throw (result.exceptionOrNull() ?: IllegalStateException("Falha ao salvar $settingName"))
        }
    }

    private suspend fun updateStringSettingOrThrow(settingName: String, value: String) {
        val result = privacyManager.updatePrivacySettingString(settingName, value)
        if (result.isFailure) {
            throw (result.exceptionOrNull() ?: IllegalStateException("Falha ao salvar $settingName"))
        }
    }

    /**
     * Mostra um toast
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
