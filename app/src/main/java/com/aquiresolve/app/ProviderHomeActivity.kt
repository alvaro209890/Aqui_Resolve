package com.aquiresolve.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aquiresolve.app.databinding.ActivityProviderHomeBinding
import com.aquiresolve.app.models.OrderData
import com.aquiresolve.app.utils.ServiceNicheCatalog
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * ProviderHomeActivity - Tela principal para prestadores
 *
 * Interface especifica para prestadores com:
 * - Dashboard de pedidos
 * - Estatisticas de trabalho
 * - Pedidos disponiveis
 * - Historico de servicos
 * - Configuracoes de disponibilidade
 */
class ProviderHomeActivity : AppCompatActivity() {

    private enum class AvailableOrdersFilter(val label: String) {
        ALL("Todos"),
        PENDING("Somente pendentes"),
        DISTRIBUTING("Somente em distribuicao"),
        HIGH_COMMISSION("Comissao >= R$ 100")
    }

    companion object {
        private const val TAG = "ProviderHome"
        private const val HIGH_COMMISSION_THRESHOLD = 100.0
        private const val AVAILABLE_ORDERS_PREVIEW_LIMIT = 4
    }

    private lateinit var binding: ActivityProviderHomeBinding
    private lateinit var authManager: FirebaseAuthManager
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var allAvailableOrders = emptyList<OrderData>()
    private var selectedAvailableOrdersFilter = AvailableOrdersFilter.ALL
    private var providerServicesNormalized = emptySet<String>()
    private var isProviderAvailable = true
    private var isUpdatingAvailability = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Garantir Firebase inicializado
        if (!FirebaseConfig.isInitialized()) {
            FirebaseConfig.initialize(this)
        }

        binding = ActivityProviderHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        authManager = FirebaseAuthManager(this)

        setupUI()
        setupClickListeners()
        loadProviderData()
    }

    override fun onResume() {
        super.onResume()
        ProviderNewOrderAlertManager.refreshMonitoring()

        // Recarrega apos voltar para refletir mudancas recentes no Firestore.
        lifecycleScope.launch {
            delay(500)
            loadProviderStats()
            loadAvailabilityState()
            loadAvailableOrders()
        }
    }

    /**
     * Configura a interface especifica para prestadores
     */
    private fun setupUI() {
        // Status bar personalizada para prestadores
        window.statusBarColor = ContextCompat.getColor(this, R.color.secondary_color)

        // Configurar titulo especifico para prestadores
        binding.tvWelcome.text = "Bem-vindo de volta!"
        binding.tvDashboardTitle.text = "Dashboard"
        binding.tvAvailableOrders.text = "Pedidos Disponiveis"
        applyAvailabilityUiState()
    }

    /**
     * Configura os listeners especificos para prestadores
     */
    private fun setupClickListeners() {
        // Barra de pesquisa de pedidos
        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            performSearch()
            true
        }

        // Botao de filtro
        binding.ivFilter.setOnClickListener {
            showFilterDialog()
        }

        // Botao de disponibilidade
        binding.btnAvailability.setOnClickListener {
            toggleAvailability()
        }

        // Botao de ver todos os pedidos
        binding.btnViewAllOrders.setOnClickListener {
            val intent = Intent(this, ProviderOrdersActivity::class.java)
            startActivity(intent)
        }

        // Navegacao inferior especifica para prestadores
        binding.bottomNavigation.menu.clear()
        binding.bottomNavigation.inflateMenu(R.menu.bottom_nav_menu_provider)
        binding.bottomNavigation.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.navigation_home -> {
                    // Ja estamos na home
                    true
                }
                R.id.navigation_orders -> {
                    // Ir para lista de pedidos do prestador
                    val intent = Intent(this, ProviderOrdersActivity::class.java)
                    startActivity(intent)
                    true
                }
                R.id.navigation_profile -> {
                    val intent = Intent(this, ProfileActivity::class.java)
                    startActivity(intent)
                    true
                }
                else -> false
            }
        }

        // Botao de notificacoes
        binding.btnNotifications.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }

        // Botao de configuracoes
        binding.btnSettings.setOnClickListener {
            showSettings()
        }
    }

    /**
     * Carrega os dados do prestador
     */
    private fun loadProviderData() {
        val user = authManager.getLocalUserData()
        if (user != null) {
            val firstName = user.fullName.ifEmpty { user.username }
                .trim()
                .split(" ")
                .firstOrNull()
                ?: "Prestador"
            binding.tvWelcome.text = "Bem-vindo de volta, $firstName!"
        }

        // Carregar estatisticas do prestador
        loadProviderStats()
        loadAvailabilityState()

        // Carregar pedidos disponiveis
        loadAvailableOrders()
    }

    /**
     * Carrega as estatisticas do prestador
     */
    private fun loadProviderStats() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            binding.tvCompletedServices.text = "0"
            binding.tvActiveOrders.text = "0"
            binding.tvEarnings.text = "R$ 0,00"
            return
        }

        lifecycleScope.launch {
            try {
                // Carregar dados do prestador do Firestore
                val providerDoc = db.collection("providers")
                    .document(currentUser.uid)
                    .get()
                    .await()

                if (providerDoc.exists()) {
                    val completedJobs = (providerDoc.getLong("completedJobs") ?: 0L).toInt()
                    val totalEarnings = providerDoc.getDouble("totalEarnings") ?: 0.0

                    // Contar pedidos ativos (assigned ou in_progress)
                    val activeOrdersSnap = db.collection("orders")
                        .whereEqualTo("assignedProvider", currentUser.uid)
                        .whereIn("status", listOf(OrderData.STATUS_ASSIGNED, OrderData.STATUS_IN_PROGRESS))
                        .get()
                        .await()

                    val activeOrders = activeOrdersSnap.size()

                    // Carregar nota media
                    val rating = providerDoc.getDouble("rating") ?: 0.0
                    val totalRatings = (providerDoc.getLong("totalRatings") ?: 0L).toInt()

                    // Atualizar interface
                    binding.tvCompletedServices.text = completedJobs.toString()
                    binding.tvActiveOrders.text = activeOrders.toString()
                    binding.tvEarnings.text = formatCurrency(totalEarnings)
                    binding.tvProviderRating.text = if (rating > 0) String.format("%.1f", rating) else "-"
                    binding.tvProviderRatingCount.text = if (totalRatings > 0) {
                        "Nota media ($totalRatings avaliacoes)"
                    } else {
                        "Sem avaliacoes ainda"
                    }

                    android.util.Log.d(TAG, "Estatisticas carregadas com sucesso")
                } else {
                    // Prestador nao encontrado, usar valores padrao
                    binding.tvCompletedServices.text = "0"
                    binding.tvActiveOrders.text = "0"
                    binding.tvEarnings.text = "R$ 0,00"
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Erro ao carregar estatisticas: ${e.message}", e)
                binding.tvCompletedServices.text = "0"
                binding.tvActiveOrders.text = "0"
                binding.tvEarnings.text = "R$ 0,00"
            }
        }
    }

    /**
     * Formata valor monetario
     */
    private fun formatCurrency(value: Double): String {
        return "R$ ${String.format("%.2f", value).replace(".", ",")}" 
    }

    /**
     * Carrega os pedidos disponiveis
     */
    private fun loadAvailableOrders() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            allAvailableOrders = emptyList()
            binding.tvNoAvailableOrders.text = "Usuario nao autenticado."
            return
        }

        lifecycleScope.launch {
            binding.tvNoAvailableOrders.text = "Carregando pedidos disponiveis..."
            try {
                val verificationStatus = ProviderVerificationManager().getVerificationStatus(currentUser.uid)
                if (verificationStatus?.status != ProviderVerificationManager.VerificationStatus.APPROVED) {
                    allAvailableOrders = emptyList()
                    binding.tvNoAvailableOrders.text = "Seu perfil ainda nao foi aprovado para receber pedidos."
                    return@launch
                }

                providerServicesNormalized = loadProviderServices(currentUser.uid)
                if (providerServicesNormalized.isEmpty()) {
                    allAvailableOrders = emptyList()
                    binding.tvNoAvailableOrders.text = "Configure seus servicos no perfil para receber pedidos."
                    return@launch
                }

                val statuses = listOf(
                    OrderData.STATUS_PENDING,
                    OrderData.STATUS_DISTRIBUTING,
                    "available",
                    OrderData.STATUS_PENDING.uppercase(),
                    OrderData.STATUS_DISTRIBUTING.uppercase(),
                    "AVAILABLE"
                )

                val snapshot = db.collection("orders")
                    .whereIn("status", statuses)
                    .get()
                    .await()

                val orders = snapshot.documents.mapNotNull { document ->
                    try {
                        document.toObject(OrderData::class.java)?.copy(id = document.id)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Erro ao converter pedido ${document.id}: ${e.message}")
                        null
                    }
                }

                allAvailableOrders = orders
                    .filter { shouldIncludeOrderForProvider(it, currentUser.uid) }
                    .distinctBy { it.id }
                    .sortedByDescending { it.createdAt.toDate().time }

                applyAvailableOrdersFilters(showToastResult = false)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Erro ao carregar pedidos disponiveis: ${e.message}", e)
                allAvailableOrders = emptyList()
                binding.tvNoAvailableOrders.text = "Erro ao carregar pedidos disponiveis."
            }
        }
    }

    /**
     * Executa a pesquisa de pedidos
     */
    private fun performSearch() {
        applyAvailableOrdersFilters(showToastResult = true)
    }

    /**
     * Mostra o dialogo de filtros
     */
    private fun showFilterDialog() {
        val options = AvailableOrdersFilter.entries.toTypedArray()
        val labels = options.map { it.label }.toTypedArray()
        var selectedIndex = selectedAvailableOrdersFilter.ordinal

        AlertDialog.Builder(this)
            .setTitle("Filtrar pedidos")
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar") { _, _ ->
                selectedAvailableOrdersFilter = options[selectedIndex]
                applyAvailableOrdersFilters(showToastResult = true)
            }
            .show()
    }

    /**
     * Alterna a disponibilidade do prestador
     */
    private fun toggleAvailability() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            showToast("Usuario nao autenticado")
            return
        }
        if (isUpdatingAvailability) return

        val nextAvailability = !isProviderAvailable
        isUpdatingAvailability = true
        binding.btnAvailability.isEnabled = false

        lifecycleScope.launch {
            try {
                db.collection("providers")
                    .document(currentUser.uid)
                    .set(
                        mapOf(
                            "isAvailable" to nextAvailability,
                            "updatedAt" to Timestamp.now()
                        ),
                        SetOptions.merge()
                    )
                    .await()

                isProviderAvailable = nextAvailability
                applyAvailabilityUiState()
                applyAvailableOrdersFilters(showToastResult = false)
                ProviderNewOrderAlertManager.refreshMonitoring()

                val message = if (nextAvailability) {
                    "Voce esta disponivel para novos pedidos."
                } else {
                    "Voce esta indisponivel para novos pedidos."
                }
                showToast(message)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Erro ao atualizar disponibilidade: ${e.message}", e)
                showToast("Erro ao atualizar disponibilidade.")
            } finally {
                isUpdatingAvailability = false
                binding.btnAvailability.isEnabled = true
            }
        }
    }

    private fun loadAvailabilityState() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            isProviderAvailable = false
            applyAvailabilityUiState()
            return
        }

        lifecycleScope.launch {
            try {
                val providerDoc = db.collection("providers")
                    .document(currentUser.uid)
                    .get()
                    .await()

                isProviderAvailable = providerDoc.getBoolean("isAvailable") ?: true
                applyAvailabilityUiState()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Erro ao carregar disponibilidade: ${e.message}", e)
                isProviderAvailable = true
                applyAvailabilityUiState()
            }
        }
    }

    private fun applyAvailabilityUiState() {
        val color = if (isProviderAvailable) {
            ContextCompat.getColor(this, R.color.success_color)
        } else {
            ContextCompat.getColor(this, R.color.error_color)
        }

        binding.btnAvailability.text = if (isProviderAvailable) "Disponivel" else "Indisponivel"
        binding.btnAvailability.backgroundTintList = ColorStateList.valueOf(color)
    }

    private suspend fun loadProviderServices(providerId: String): Set<String> {
        return try {
            val providerDoc = db.collection("providers")
                .document(providerId)
                .get()
                .await()

            val rawServices = (providerDoc.get("services") as? List<*>)
                ?.mapNotNull { it as? String }
                ?: emptyList()

            ServiceNicheCatalog.normalizeProviderServices(rawServices)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Erro ao carregar servicos do prestador: ${e.message}", e)
            emptySet()
        }
    }

    private fun shouldIncludeOrderForProvider(order: OrderData, providerId: String): Boolean {
        val assignedProvider = order.assignedProvider
        if (!assignedProvider.isNullOrBlank() && assignedProvider != providerId) {
            return false
        }

        return ServiceNicheCatalog.matchesProviderServices(providerServicesNormalized, order)
    }

    private fun applyAvailableOrdersFilters(showToastResult: Boolean) {
        val query = binding.etSearch.text.toString().trim()
        var filteredOrders = allAvailableOrders

        filteredOrders = when (selectedAvailableOrdersFilter) {
            AvailableOrdersFilter.ALL -> filteredOrders
            AvailableOrdersFilter.PENDING -> filteredOrders.filter {
                it.status.equals(OrderData.STATUS_PENDING, ignoreCase = true)
            }
            AvailableOrdersFilter.DISTRIBUTING -> filteredOrders.filter {
                it.status.equals(OrderData.STATUS_DISTRIBUTING, ignoreCase = true)
            }
            AvailableOrdersFilter.HIGH_COMMISSION -> filteredOrders.filter {
                it.providerCommission >= HIGH_COMMISSION_THRESHOLD
            }
        }

        if (query.isNotEmpty()) {
            filteredOrders = filteredOrders.filter { order ->
                order.protocol.contains(query, ignoreCase = true) ||
                    order.serviceType.contains(query, ignoreCase = true) ||
                    order.serviceName.contains(query, ignoreCase = true) ||
                    order.description.contains(query, ignoreCase = true) ||
                    order.clientName.contains(query, ignoreCase = true) ||
                    order.address.contains(query, ignoreCase = true) ||
                    order.id.contains(query, ignoreCase = true)
            }
        }

        updateAvailableOrdersSummary(filteredOrders, query)
        if (showToastResult) {
            val message = if (filteredOrders.isEmpty()) {
                "Nenhum pedido encontrado."
            } else {
                "${filteredOrders.size} pedido(s) encontrado(s)."
            }
            showToast(message)
        }
    }

    private fun updateAvailableOrdersSummary(orders: List<OrderData>, query: String) {
        if (!isProviderAvailable) {
            binding.tvNoAvailableOrders.text = "Voce esta indisponivel. Ative para receber novos pedidos."
            return
        }

        if (providerServicesNormalized.isEmpty()) {
            binding.tvNoAvailableOrders.text = "Configure seus servicos no perfil para receber pedidos."
            return
        }

        if (orders.isEmpty()) {
            binding.tvNoAvailableOrders.text = if (query.isNotEmpty()) {
                "Nenhum pedido encontrado para \"$query\"."
            } else {
                "Nenhum pedido disponivel no momento."
            }
            return
        }

        binding.tvNoAvailableOrders.text = buildAvailableOrdersSummary(orders)
    }

    private fun buildAvailableOrdersSummary(orders: List<OrderData>): String {
        val preview = orders.take(AVAILABLE_ORDERS_PREVIEW_LIMIT)
        val header = if (orders.size > preview.size) {
            "Mostrando ${preview.size} de ${orders.size} pedidos disponiveis.\nAbra \"Meus Pedidos\" para ver todos.\n\n"
        } else {
            "${orders.size} pedido(s) disponivel(is):\n\n"
        }

        val body = preview.joinToString(separator = "\n\n") { order ->
            val protocol = order.protocol.ifBlank { order.id.takeLast(6) }
            val service = order.serviceType.ifBlank { order.serviceName.ifBlank { "Servico" } }
            val statusText = when {
                order.status.equals(OrderData.STATUS_PENDING, ignoreCase = true) -> "Pendente"
                order.status.equals(OrderData.STATUS_DISTRIBUTING, ignoreCase = true) -> "Em distribuicao"
                else -> order.status
            }
            val commission = if (order.providerCommission > 0) {
                formatCurrency(order.providerCommission)
            } else {
                "a combinar"
            }
            val address = order.address.ifBlank { "Endereco nao informado" }

            "#$protocol | $service\n$statusText | Comissao: $commission\n$address"
        }

        return header + body
    }

    /**
     * Mostra configuracoes
     */
    private fun showSettings() {
        startActivity(Intent(this, ProfileActivity::class.java))
    }

    /**
     * Exibe uma mensagem toast
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
