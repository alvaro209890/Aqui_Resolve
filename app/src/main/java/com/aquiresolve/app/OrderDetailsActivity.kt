package com.aquiresolve.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.location.Location
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.aquiresolve.app.adapters.ImageAdapter
import com.aquiresolve.app.databinding.ActivityOrderDetailsBinding
import com.aquiresolve.app.models.OrderData
import com.aquiresolve.app.models.OrderStatus
import com.aquiresolve.app.models.OsChecklistData
import com.aquiresolve.app.utils.LocationPermissionHelper
import com.aquiresolve.app.utils.ProtocolGenerator
import com.aquiresolve.app.utils.VerificationCodeDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * OrderDetailsActivity - Tela de detalhes do pedido
 * 
 * Funcionalidades:
 * - Exibe todos os detalhes do pedido
 * - Mostra prestador atribuído (se houver)
 * - Lista cotações recebidas
 * - Ações específicas por status
 * - Navegação para chat
 */
class OrderDetailsActivity : AppCompatActivity() {

    companion object {
    }

    // ViewBinding para acesso aos elementos da interface
    private lateinit var binding: ActivityOrderDetailsBinding
    
    // Variáveis para controle de estado
    private var orderId: String? = null
    private var order: OrderData? = null
    private var isProviderView = false
    private enum class LocationPermissionPurpose { MAP, START_OS }
    private var pendingLocationPermissionPurpose: LocationPermissionPurpose? = null
    
    // Firebase
    private val db = FirebaseFirestore.getInstance()
    private lateinit var orderManager: FirebaseOrderManager
    private lateinit var checklistManager: FirebaseChecklistManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // OS Checklist
    private var osChecklist: OsChecklistData? = null
    private var osStatusCard: com.google.android.material.card.MaterialCardView? = null
    
    // Map overlays
    private var clientMarker: Marker? = null
    private var providerMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var locationCallback: LocationCallback? = null
    private var locationRequest: LocationRequest? = null
    private var lastRouteUpdateAt: Long = 0L
    private var lastProviderPoint: GeoPoint? = null
    private var currentClientPoint: GeoPoint? = null
    private var providerLocationListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var mapTapOverlay: org.osmdroid.views.overlay.Overlay? = null
    private val osrmClient: okhttp3.OkHttpClient by lazy {
        // Os servidores públicos OSRM (project-osrm / FOSSGIS) só aceitam TLS 1.3.
        // O ConnectionSpec padrão do OkHttp restringe os cipher suites e provoca
        // "SSLV3_ALERT_HANDSHAKE_FAILURE" no handshake. Liberar todos os ciphers
        // habilitados pela plataforma faz o socket oferecer o conjunto TLS 1.3
        // completo e a conexão fecha corretamente.
        val tlsSpec = okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
            .tlsVersions(okhttp3.TlsVersion.TLS_1_3, okhttp3.TlsVersion.TLS_1_2)
            .allEnabledCipherSuites()
            .build()
        okhttp3.OkHttpClient.Builder()
            .connectionSpecs(listOf(tlsSpec, okhttp3.ConnectionSpec.CLEARTEXT))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // Servidores OSRM públicos (sem API key). Tentados em ordem antes do fallback
    // de linha reta — se um estiver fora do ar/limitado, o próximo cobre.
    private val osrmHosts = listOf(
        "https://router.project-osrm.org",
        "https://routing.openstreetmap.de/routed-car"
    )

    // Proxy de roteamento no nosso backend (deriva da base de pagamentos).
    // É a fonte PRIMÁRIA da rota: o backend alcança o OSRM sem o problema de
    // handshake TLS 1.3 que afeta vários aparelhos Android (minSdk 24) e emuladores.
    // Ex.: https://aquiresolve.onrender.com/api/route
    private val routeProxyBase: String =
        BuildConfig.PAYMENTS_API_BASE_URL.removeSuffix("/").removeSuffix("/payments") + "/route"
    private val ratingResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            loadOrderDetails()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        
        // Inicializar ViewBinding
        binding = ActivityOrderDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Obter dados da intent
        orderId = intent.getStringExtra("order_id")
        isProviderView = intent.getBooleanExtra("is_provider_view", false)
        
        if (orderId == null) {
            showErrorMessage("Pedido não encontrado")
            finish()
            return
        }
        
        // Inicializar managers
        orderManager = FirebaseOrderManager()
        checklistManager = FirebaseChecklistManager()
        
        // Configurar a interface
        setupUI()
        setupClickListeners()
        
        // Carregar dados do pedido
        loadOrderDetails()
    }

    /**
     * Configura os elementos da interface do usuário
     */
    private fun setupUI() {
        // Configurar a status bar
        window.statusBarColor = ContextCompat.getColor(this, R.color.primary_color)
    }
    
    /**
     * Teste temporário para verificar visibilidade do botão
     */
    private fun testButtonVisibility() {
        // Garantir que o botão tenha texto visível
        binding.btnSecondaryAction.text = "Cancelar Pedido"
        binding.btnSecondaryAction.setTextColor(ContextCompat.getColor(this, R.color.error_red))
        binding.btnSecondaryAction.visibility = View.VISIBLE
        
        android.util.Log.d("OrderDetails", "Teste: Texto do botão = ${binding.btnSecondaryAction.text}")
        android.util.Log.d("OrderDetails", "Teste: Visibilidade = ${binding.btnSecondaryAction.visibility}")
    }

    /**
     * Configura os listeners de clique para todos os elementos interativos
     */
    private fun setupClickListeners() {
        // Botão voltar
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // Botão mais opções
        binding.btnMore.setOnClickListener {
            showMoreOptionsDialog()
        }
        
        // Botão contatar prestador
        binding.btnContactProvider.setOnClickListener {
            openChat()
        }
        
        // Botão de ação principal
        binding.btnPrimaryAction.setOnClickListener {
            handlePrimaryAction()
        }
        
        // Botão de ação secundária
        binding.btnSecondaryAction.setOnClickListener {
            handleSecondaryAction()
        }

        // Botão de recorrer ao serviço (WhatsApp)
        binding.btnAppeal.setOnClickListener {
            openAppealWhatsApp()
        }
    }

    /**
     * Carrega os detalhes do pedido do Firebase
     */
    private fun loadOrderDetails() {
        lifecycleScope.launch {
            try {
                showLoading(true)
                
                val result = orderManager.getOrderById(orderId!!)
                
                if (result.isSuccess) {
                    val orderData = result.getOrNull()
                    if (orderData != null) {
                        order = orderData
                        loadChecklistData(orderData.id)
                        updateUI(orderData)
                    } else {
                        showErrorMessage("Pedido não encontrado")
                        finish()
                    }
                } else {
                    showErrorMessage("Erro ao carregar pedido: ${result.exceptionOrNull()?.message}")
                    finish()
                }
                
            } catch (e: Exception) {
                showErrorMessage("Erro ao carregar pedido: ${e.message}")
                finish()
            } finally {
                showLoading(false)
            }
        }
    }

    /**
     * Carrega dados do checklist da OS
     */
    private fun loadChecklistData(orderId: String) {
        lifecycleScope.launch {
            try {
                val result = checklistManager.getChecklist(orderId)
                if (result.isSuccess) {
                    osChecklist = result.getOrNull()
                    // Reconfigurar botões com base no checklist
                    order?.let { setActionButtons(it) }
                }
            } catch (e: Exception) {
                android.util.Log.w("OrderDetails", "Erro ao carregar checklist: ${e.message}")
            }
        }
    }

    /**
     * Atualiza a interface com os dados do pedido
     */
    /**
     * Solicita a liquidação financeira no backend caso uma OS concluída ainda esteja pendente.
     * Cashback e comissão são creditados exclusivamente pelo backend/admin para evitar duplicidade.
     */
    private fun creditCashbackIfEligible(order: OrderData) {
        if (order.status != OrderData.STATUS_COMPLETED || order.settlementStatus == "settled") {
            return
        }
        lifecycleScope.launch {
            try {
                orderManager.settleCompletedOrder(order.id)
            } catch (e: Exception) {
                android.util.Log.w("OrderDetailsActivity", "Liquidação financeira pendente: ${e.message}")
            }
        }
    }

    private fun updateUI(order: OrderData) {
        // Configurar ícone do serviço
        setServiceIcon(order.serviceName)
        
        // Configurar dados básicos
        binding.tvServiceNiche.text = order.serviceName
        binding.tvDescription.text = order.description
        binding.tvAddress.text = order.address
        
        // Configurar data
        val dateFormat = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale("pt", "BR"))
        binding.tvOrderDate.text = dateFormat.format(order.createdAt.toDate())
        
        // Configurar protocolo
        if (order.protocol.isNotEmpty()) {
            binding.tvOrderProtocol.text = "Protocolo: ${ProtocolGenerator.formatProtocolForDisplay(order.protocol)}"
            binding.tvOrderProtocol.visibility = View.VISIBLE
        } else {
            binding.tvOrderProtocol.visibility = View.GONE
        }
        
        // Mostrar código de verificação do cliente se o pedido foi aceito ou está em andamento
        if (!isProviderView && 
            (order.status == OrderData.STATUS_ASSIGNED || order.status == OrderData.STATUS_IN_PROGRESS) && 
            order.clientVerificationCode != null) {
            // Exibir código no campo da interface
            showVerificationCodeField(order.clientVerificationCode!!)
        } else {
            // Ocultar campo de código se não for cliente ou pedido não aceito
            binding.cardVerificationCode.visibility = View.GONE
        }
        
        // Configurar status
        setStatusInfo(order.status)

        // Creditar cashback do cliente quando o pedido estiver concluído (idempotente)
        if (!isProviderView && order.status == OrderData.STATUS_COMPLETED) {
            creditCashbackIfEligible(order)
        }

        // Configurar preço
        setPriceInfo(order)
        
        // Configurar badges
        setBadges(order)

        // Mapa e distância (apenas se tiver coordenadas do cliente)
        setupMapAndDistance(order)

        // Se cancelado, exibir cartão com informações de cancelamento
        if (order.status == OrderData.STATUS_CANCELLED || order.status == OrderData.STATUS_EXPIRED) {
            binding.cardCancellationInfo.visibility = View.VISIBLE
            val dateFormat = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale("pt", "BR"))
            val cancelledAtText = order.cancelledAt?.toDate()?.let { dateFormat.format(it) } ?: "--"
            val cancelledByText = when (order.cancelledBy) { 
                "client" -> "Cliente"
                "provider" -> "Prestador"
                else -> "Sistema"
            }
            binding.tvCancelledAt.text = "Cancelado em $cancelledAtText"
            binding.tvCancelledBy.text = "Por: $cancelledByText"
            binding.tvCancellationReason.text = order.cancellationReason?.ifEmpty { "Sem motivo informado" } ?: "Sem motivo informado"
            
            // Mostrar informações de reembolso se cancelado pelo cliente e aguardando reembolso
            if (order.cancelledBy == "client" && order.refundStatus == "pending") {
                binding.cardRefundInfo.visibility = View.VISIBLE
                binding.tvRefundInfo.text = "💳 O valor pago será reembolsado em até 24 horas.\n\nVocê receberá uma notificação quando o reembolso for processado."
            } else {
                binding.cardRefundInfo.visibility = View.GONE
            }
        } else {
            binding.cardCancellationInfo.visibility = View.GONE
            binding.cardRefundInfo.visibility = View.GONE
        }
        
        // Configurar complemento
        if (order.complement != null && order.complement.isNotEmpty()) {
            binding.tvComplement.text = order.complement
            binding.tvComplement.visibility = View.VISIBLE
        }
        
        // Configurar imagens
        if (order.images.isNotEmpty()) {
            binding.cardImages.visibility = View.VISIBLE
            val imageAdapter = ImageAdapter(
                context = this,
                imageUrls = order.images,
                onImageClick = { imageUrl, _ ->
                    val intent = Intent(this, ImagePreviewActivity::class.java).apply {
                        putStringArrayListExtra("image_uris", ArrayList(order.images))
                        putStringArrayListExtra("file_names", ArrayList(List(order.images.size) { "imagem_${it + 1}.jpg" }))
                        putExtra("file_sizes", LongArray(order.images.size) { 0L })
                        putStringArrayListExtra("image_types", ArrayList(List(order.images.size) { "ORDER" }))
                        putExtra("current_position", order.images.indexOf(imageUrl))
                    }
                    startActivity(intent)
                }
            )
            binding.rvImages.layoutManager = GridLayoutManager(this, 3)
            binding.rvImages.adapter = imageAdapter
        } else {
            binding.cardImages.visibility = View.GONE
        }
        
        // Configurar prestador (se atribuído)
        if (!order.assignedProviderName.isNullOrEmpty()) {
            binding.cardProvider.visibility = View.VISIBLE
            binding.tvProviderName.text = order.assignedProviderName
            binding.tvProviderRating.text = "⭐ Prestador Atribuído"
            // Carregar nota média real e foto do prestador
            if (!order.assignedProvider.isNullOrEmpty()) {
                loadProviderRating(order.assignedProvider!!)
                loadProviderImage(order.assignedProvider!!)
            }
        } else {
            binding.cardProvider.visibility = View.VISIBLE
            binding.tvProviderName.text = when (order.status) {
                "distributing" -> "Não atribuído"
                "pending" -> "Não atribuído"
                "cancelled" -> "Cancelado"
                "expired" -> "Expirado"
                else -> "Não atribuído"
            }
            binding.tvProviderRating.text = "⏳ Em distribuição"
        }
        
        // Configurar botões de ação
        setActionButtons(order)

        // Exibir botão de chat quando o pedido estiver atribuído ou em andamento
        val canChat = order.status == OrderData.STATUS_ASSIGNED || order.status == OrderData.STATUS_IN_PROGRESS
        binding.btnContactProvider.visibility = if (canChat) View.VISIBLE else View.GONE
        binding.btnContactProvider.text = if (isProviderView) "Chat com Cliente" else "Chat com Prestador"
    }

    /**
     * Define o ícone baseado no nicho de serviço
     */
    private fun setServiceIcon(serviceNiche: String) {
        val iconRes = when (serviceNiche.lowercase()) {
            "elétrica" -> R.drawable.ic_electrician
            "encanador", "hidráulica" -> R.drawable.ic_plumber
            "pintura" -> R.drawable.ic_painter
            "limpeza" -> R.drawable.ic_cleaning
            "jardinagem" -> R.drawable.ic_gardening
            "marcenaria" -> R.drawable.ic_carpentry
            "informática" -> R.drawable.ic_it
            "mudanças" -> R.drawable.ic_moving
            else -> R.drawable.ic_services
        }
        binding.ivServiceIcon.setImageResource(iconRes)
    }

    /**
     * Define as informações de status
     */
    private fun setStatusInfo(status: String) {
        val normalized = status.lowercase()
        val (text, backgroundRes) = when (normalized) {
            OrderData.STATUS_AWAITING_PAYMENT -> "AGUARDANDO PAGAMENTO" to R.drawable.status_pending_background
            OrderData.STATUS_PENDING -> "PENDENTE" to R.drawable.status_pending_background
            "quotes_received" -> "COTAÇÕES" to R.drawable.status_pending_background
            OrderData.STATUS_ASSIGNED -> "ATRIBUIDO" to R.drawable.status_pending_background
            OrderData.STATUS_IN_PROGRESS -> "EM ANDAMENTO" to R.drawable.status_pending_background
            OrderData.STATUS_COMPLETED -> "CONCLUÍDO" to R.drawable.status_pending_background
            OrderData.STATUS_CANCELLED -> "CANCELADO" to R.drawable.status_cancelled_background
            OrderData.STATUS_EXPIRED -> "EXPIRADO" to R.drawable.status_cancelled_background
            OrderData.STATUS_DISTRIBUTING -> "EM DISTRIBUIÇÃO" to R.drawable.status_pending_background
            else -> "PENDENTE" to R.drawable.status_pending_background
        }
        
        binding.tvStatus.text = text
        binding.tvStatus.setBackgroundResource(backgroundRes)
    }

    /**
     * Define as informações de preço
     */
    private fun setPriceInfo(order: OrderData) {
        when {
            order.estimatedPrice > 0 -> {
                if (isProviderView && order.providerCommission > 0) {
                    // Para prestador, mostrar APENAS a comissão (não o valor total)
                    binding.tvPrice.text = "💰 Você ganha: R$ %.2f".format(
                        order.providerCommission
                    ).replace(".", ",")
                } else {
                    // Para cliente, mostrar apenas valor total
                    binding.tvPrice.text = "R$ %.2f".format(order.estimatedPrice).replace(".", ",")
                }
            }
            else -> {
                binding.tvPrice.text = "Aguardando"
            }
        }
    }

    /**
     * Define os badges
     */
    private fun setBadges(order: OrderData) {
        // Badge de emergência
        binding.tvEmergency.visibility = View.GONE
        
        // Badge de tipo de serviço
        binding.tvServiceType.text = if (order.serviceType == "SIMPLE") "💰 PREÇO FIXO" else "📋 ORÇAMENTO"
    }

    /**
     * Define os botões de ação
     */
    private fun setActionButtons(order: OrderData) {
        val (primaryText, secondaryText) = if (isProviderView) {
            when (order.status) {
                OrderData.STATUS_DISTRIBUTING, OrderData.STATUS_PENDING -> "Aceitar Pedido" to "Ver Detalhes"
                OrderData.STATUS_ASSIGNED -> "Iniciar OS" to "Chat"
                OrderData.STATUS_IN_PROGRESS -> {
                    val checklist = osChecklist
                    when {
                        checklist == null -> "Iniciar OS" to "Chat"
                        checklist.status == OsChecklistData.STATUS_COMPLETED -> "Ver OS" to "Chat"
                        else -> "Continuar OS" to "Chat"
                    }
                }
                OrderData.STATUS_COMPLETED -> {
                    if (osChecklist != null) "Ver OS" to "—" else "Ver Detalhes" to "—"
                }
                OrderData.STATUS_CANCELLED, OrderData.STATUS_EXPIRED -> "Ver Detalhes" to "—"
                else -> "Ver Detalhes" to "—"
            }
        } else {
            when (order.status) {
                OrderData.STATUS_AWAITING_PAYMENT -> {
                    "⏳ Aguardando Pagamento" to "Cancelar Pedido"
                }
                OrderData.STATUS_DISTRIBUTING -> {
                    "⏳ Em Distribuição" to "Cancelar Pedido"
                }
                OrderData.STATUS_PENDING -> {
                    if (order.serviceType == "SIMPLE") {
                        "Aguardando Prestador" to "Cancelar Pedido"
                    } else {
                        "Aguardando Cotações" to "Cancelar Pedido"
                    }
                }
                "quotes_received" -> "Ver Cotações" to "Cancelar Pedido"
                OrderData.STATUS_ASSIGNED -> "Iniciar Serviço" to "Cancelar Pedido"
                OrderData.STATUS_IN_PROGRESS -> "Confirmar Conclusão" to "Reportar Problema"
                OrderData.STATUS_COMPLETED -> {
                    if (order.rating != null && order.rating > 0) {
                        "⭐ Já Avaliado (${order.rating} estrelas)" to "Ver Avaliação"
                    } else {
                        "Avaliar Serviço" to "—"
                    }
                }
                OrderData.STATUS_CANCELLED -> "Ver Detalhes" to "Criar Novo Pedido"
                OrderData.STATUS_EXPIRED -> "Ver Detalhes" to "Criar Novo Pedido"
                else -> "Ver Detalhes" to "Cancelar Pedido"
            }
        }
        
        // Definir textos dos botões
        binding.btnPrimaryAction.text = primaryText
        
        // Configurar botão secundário
        if (secondaryText == "—") {
            binding.btnSecondaryAction.visibility = View.GONE
        } else {
            binding.btnSecondaryAction.visibility = View.VISIBLE
            binding.btnSecondaryAction.text = secondaryText
            binding.btnSecondaryAction.setTextColor(ContextCompat.getColor(this, R.color.error_red))
        }
        
        // Configurar visibilidade e estado dos botões
        if (isProviderView) {
            when (order.status) {
                OrderData.STATUS_DISTRIBUTING, OrderData.STATUS_PENDING -> {
                    binding.btnPrimaryAction.isEnabled = true
                    binding.btnSecondaryAction.isEnabled = true
                }
                OrderData.STATUS_ASSIGNED, OrderData.STATUS_IN_PROGRESS -> {
                    binding.btnPrimaryAction.isEnabled = true
                    binding.btnSecondaryAction.isEnabled = true
                    binding.btnSecondaryAction.visibility = View.VISIBLE
                }
                OrderData.STATUS_COMPLETED, OrderData.STATUS_CANCELLED, OrderData.STATUS_EXPIRED -> {
                    binding.btnPrimaryAction.isEnabled = true
                    binding.btnSecondaryAction.visibility = View.GONE
                }
                else -> {
                    binding.btnPrimaryAction.isEnabled = true
                    binding.btnSecondaryAction.isEnabled = true
                }
            }
            // Para provider, usar cor verde no botão primário de OS
            if (primaryText.contains("OS", ignoreCase = true) || primaryText == "Continuar OS" || primaryText == "Ver OS") {
                binding.btnPrimaryAction.backgroundTintList = ContextCompat.getColorStateList(this, R.color.secondary_color)
            } else {
                binding.btnPrimaryAction.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary_color)
            }
        } else {
            when (order.status) {
                "distributing" -> {
                    binding.btnPrimaryAction.isEnabled = false // Em distribuição, não há ação primária
                    binding.btnSecondaryAction.isEnabled = true // Sempre pode cancelar
                }
                "pending" -> {
                    binding.btnPrimaryAction.isEnabled = false
                    binding.btnSecondaryAction.isEnabled = true
                }
                "completed" -> {
                    val alreadyRated = order.rating != null && order.rating > 0
                    binding.btnPrimaryAction.isEnabled = !alreadyRated
                    binding.btnSecondaryAction.isEnabled = true
                    // Para pedidos concluídos, usar cor verde
                    binding.btnSecondaryAction.setStrokeColorResource(R.color.secondary_color)
                    binding.btnSecondaryAction.setTextColor(ContextCompat.getColor(this, R.color.secondary_color))
                    // Mostrar botão de recorrer
                    binding.btnAppeal.visibility = View.VISIBLE
                }
                "cancelled", "expired" -> {
                    binding.btnPrimaryAction.isEnabled = true
                    binding.btnSecondaryAction.isEnabled = true
                    // Para pedidos cancelados, usar cor laranja
                    binding.btnSecondaryAction.setStrokeColorResource(R.color.primary_color)
                    binding.btnSecondaryAction.setTextColor(ContextCompat.getColor(this, R.color.primary_color))
                    // Mostrar botão de recorrer
                    binding.btnAppeal.visibility = View.VISIBLE
                }
                else -> {
                    binding.btnPrimaryAction.isEnabled = true
                    binding.btnSecondaryAction.isEnabled = true
                    // Para outros status, manter vermelho (padrão)
                }
            }
        }
    }

    /**
     * Trata a ação principal do botão
     */
    private fun handlePrimaryAction() {
        order?.let { order ->
            if (isProviderView) {
                when (order.status) {
                    OrderData.STATUS_DISTRIBUTING, OrderData.STATUS_PENDING -> acceptOrderAsProvider(order)
                    OrderData.STATUS_ASSIGNED -> {
                        // Iniciar OS (checklist)
                        startOsFlow(order)
                    }
                    OrderData.STATUS_IN_PROGRESS -> {
                        val checklist = osChecklist
                        when {
                            checklist == null -> startOsFlow(order)
                            checklist.status == OsChecklistData.STATUS_COMPLETED -> viewOsHistory(order)
                            else -> continueOs(checklist)
                        }
                    }
                    OrderData.STATUS_COMPLETED -> {
                        if (osChecklist != null) viewOsHistory(order) else openChat()
                    }
                    else -> openChat()
                }
            } else {
                when (order.status) {
                    "quotes_received" -> openQuotesScreen(order.id)
                    OrderData.STATUS_ASSIGNED -> startService(order)
                    OrderData.STATUS_IN_PROGRESS -> confirmCompletion(order, actor = "client")
                    OrderData.STATUS_COMPLETED -> {
                        if (order.rating != null && order.rating > 0) {
                            showToast("Você já avaliou este serviço (${order.rating} estrelas)")
                        } else {
                            val intent = Intent(this, RatingActivity::class.java)
                            intent.putExtra("order_id", order.id)
                            intent.putExtra("provider_name", order.assignedProviderName)
                            ratingResultLauncher.launch(intent)
                        }
                    }
                    OrderData.STATUS_CANCELLED, OrderData.STATUS_EXPIRED -> {
                        // Pedido cancelado - não há ação primária
                        showToast("Este pedido foi cancelado")
                    }
                    else -> showToast("Ação não disponível para este status")
                }
            }
        }
    }
    
    /**
     * Finaliza o serviço solicitando código do cliente
     */
    private fun finishServiceWithCode(order: OrderData) {
        VerificationCodeDialog.showCodeInputDialog(
            context = this,
            onCodeEntered = { code ->
                lifecycleScope.launch {
                    try {
                        val result = orderManager.completeOrderWithVerification(order.id, code)
                        
                        if (result.isSuccess) {
                            showSuccessMessage("✅ Serviço finalizado com sucesso!")
                            
                            // Navegar de volta para a tela inicial do prestador após finalizar
                            if (isProviderView) {
                                // Aguardar um pouco para mostrar a mensagem de sucesso
                                delay(1000)
                                
                                // Criar intent para voltar à tela inicial do prestador
                                val intent = Intent(this@OrderDetailsActivity, ProviderHomeActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                                startActivity(intent)
                                finish() // Finalizar esta activity
                            } else {
                                loadOrderDetails()
                            }
                        } else {
                            val errorMessage = result.exceptionOrNull()?.message ?: "Erro desconhecido"
                            if (errorMessage.contains("incorreto", ignoreCase = true)) {
                                showErrorMessage("❌ Código incorreto! Verifique o código fornecido pelo cliente.")
                            } else {
                                showErrorMessage("❌ Erro ao finalizar: $errorMessage")
                            }
                        }
                    } catch (e: Exception) {
                        showErrorMessage("❌ Erro ao finalizar serviço: ${e.message}")
                    }
                }
            },
            onCancel = {
                // Usuário cancelou
            }
        )
    }

    /**
     * Carrega a nota média do prestador do Firebase
     */
    private fun loadProviderRating(providerId: String) {
        lifecycleScope.launch {
            try {
                val providerDoc = FirebaseFirestore.getInstance()
                    .collection("providers").document(providerId).get().await()
                if (providerDoc.exists()) {
                    val rating = providerDoc.getDouble("rating") ?: 0.0
                    val totalRatings = (providerDoc.getLong("totalRatings") ?: 0L).toInt()
                    if (rating > 0) {
                        binding.tvProviderRating.text = "⭐ ${String.format("%.1f", rating)} ($totalRatings avaliações)"
                    } else {
                        binding.tvProviderRating.text = "⭐ Sem avaliações"
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("OrderDetails", "Erro ao carregar rating: ${e.message}")
            }
        }
    }

    private fun loadProviderImage(providerId: String) {
        lifecycleScope.launch {
            try {
                val providerDoc = FirebaseFirestore.getInstance()
                    .collection("providers").document(providerId).get().await()
                if (providerDoc.exists()) {
                    val imageUrl = providerDoc.getString("profileImageUrl")
                    if (!imageUrl.isNullOrEmpty()) {
                        binding.ivProviderPhoto.setPadding(0, 0, 0, 0)
                        binding.ivProviderPhoto.imageTintList = null
                        Glide.with(this@OrderDetailsActivity)
                            .load(imageUrl)
                            .transform(CircleCrop())
                            .placeholder(R.drawable.ic_person)
                            .error(R.drawable.ic_person)
                            .into(binding.ivProviderPhoto)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("OrderDetails", "Erro ao carregar foto do prestador: ${e.message}")
            }
        }
    }

    /**
     * Abre WhatsApp para recorrer ao serviço
     */
    private fun openAppealWhatsApp() {
        // TODO: Substituir pelo link do WhatsApp definitivo
        val whatsappUrl = "https://wa.me/SEUNUMEROAQUI?text=Olá, gostaria de recorrer ao serviço. Protocolo: ${order?.protocol ?: "N/A"}"
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(whatsappUrl))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir o WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Trata a ação secundária do botão
     */
    private fun handleSecondaryAction() {
        order?.let { order ->
            if (isProviderView) {
                when (order.status) {
                    OrderData.STATUS_ASSIGNED, OrderData.STATUS_IN_PROGRESS -> {
                        openChat()
                    }
                    OrderData.STATUS_DISTRIBUTING, OrderData.STATUS_PENDING -> {
                        binding.contentLayout.smoothScrollTo(0, 0)
                    }
                    else -> {}
                }
            } else {
                when (order.status) {
                    OrderData.STATUS_DISTRIBUTING, OrderData.STATUS_PENDING, "quotes_received", OrderData.STATUS_ASSIGNED -> showCancelOrderDialog(order)
                    OrderData.STATUS_IN_PROGRESS -> showReportProblemDialog(order)
                    OrderData.STATUS_COMPLETED -> showRatingDetailsDialog(order)
                    OrderData.STATUS_CANCELLED, OrderData.STATUS_EXPIRED -> createNewOrder()
                    else -> showCancelOrderDialog(order)
                }
            }
        }
    }

    private fun acceptOrderAsProvider(order: OrderData) {
        lifecycleScope.launch {
            val result = orderManager.acceptOrderAsProvider(order.id)
            if (result.isSuccess) {
                showSuccessMessage("✅ Pedido aceito com sucesso!")
                loadOrderDetails()
            } else {
                showErrorMessage("❌ Não foi possível aceitar: ${result.exceptionOrNull()?.message ?: "erro desconhecido"}")
            }
        }
    }

    /**
     * Abre a tela de cotações
     */
    private fun openQuotesScreen(orderId: String) {
        // val intent = Intent(this, QuotesActivity::class.java)
        // intent.putExtra("order_id", orderId)
        // startActivity(intent)
        showToast("💰 Tela de cotações em desenvolvimento")
    }


    /**
     * Inicia o serviço
     */
    private fun startService(order: OrderData) {
        lifecycleScope.launch {
            try {
                val result = orderManager.startService(order.id)
                if (result.isSuccess) {
                    showSuccessMessage("🚀 Serviço iniciado!")
                } else {
                    showErrorMessage(result.exceptionOrNull()?.message ?: "Falha ao iniciar serviço")
                }
                loadOrderDetails() // Recarregar dados
            } catch (e: Exception) {
                showErrorMessage("Erro ao iniciar serviço")
            }
        }
    }

    /**
     * Conclui o serviço
     */
    private fun confirmCompletion(order: OrderData, actor: String) {
        lifecycleScope.launch {
            try {
                val result = orderManager.confirmCompletion(order.id, actor)
                if (result.isSuccess) {
                    if (actor == "client") {
                        showSuccessMessage("✅ Sua confirmação foi registrada. Aguarde a confirmação do prestador.")
                    } else {
                        showSuccessMessage("✅ Confirmação do prestador registrada.")
                    }
                } else {
                    showErrorMessage(result.exceptionOrNull()?.message ?: "Falha ao confirmar conclusão")
                }
                loadOrderDetails()
            } catch (e: Exception) {
                showErrorMessage("Erro ao confirmar conclusão")
            }
        }
    }

    /**
     * Exibe o código de verificação no campo da interface
     */
    private fun showVerificationCodeField(code: String) {
        binding.cardVerificationCode.visibility = View.VISIBLE
        binding.tvVerificationCode.text = code
        
        // Configurar botão de copiar
        binding.btnCopyCode.setOnClickListener {
            copyCodeToClipboard(code)
        }
    }
    
    /**
     * Copia o código para a área de transferência
     */
    private fun copyCodeToClipboard(code: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Código de Finalização", code)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "✅ Código copiado!", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Abre o chat
     */
    private fun openChat() {
        order?.let { order ->
            // Verificar se o chat pode ser acessado (5 minutos após aceitação)
            val (canAccess, message) = com.aquiresolve.app.utils.ChatAccessHelper.canAccessChat(order)
            
            if (!canAccess) {
                AlertDialog.Builder(this)
                    .setTitle("Chat Indisponível")
                    .setMessage(message ?: "O chat ainda não está disponível.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            
            // Buscar foto do contato antes de abrir o chat
            lifecycleScope.launch {
                try {
                    if (isProviderView) {
                        // Prestador conversa com o cliente - buscar foto do cliente
                        val clientPhotoUrl = try {
                            db.collection("users").document(order.clientId)
                                .get().await()
                                .getString("profileImageUrl")
                        } catch (e: Exception) {
                            null
                        }
                        
                        val intent = Intent(this@OrderDetailsActivity, ProviderChatActivity::class.java)
                        intent.putExtra("order_id", order.id)
                        intent.putExtra("client_id", order.clientId)
                        intent.putExtra("client_name", order.clientName)
                        intent.putExtra("client_photo", clientPhotoUrl)
                        intent.putExtra("order_title", order.serviceName)
                        intent.putExtra("order_description", order.description)
                        startActivity(intent)
                    } else {
                        // Cliente conversa com o prestador - buscar foto do prestador
                        val providerPhotoUrl = try {
                            val providerId = order.assignedProvider ?: ""
                            // Tentar buscar de providers primeiro, fallback para users
                            db.collection("providers").document(providerId)
                                .get().await()
                                .getString("profileImageUrl")
                                ?: db.collection("users").document(providerId)
                                    .get().await()
                                    .getString("profileImageUrl")
                        } catch (e: Exception) {
                            null
                        }
                        
                        val intent = Intent(this@OrderDetailsActivity, ClientChatActivity::class.java)
                        intent.putExtra("order_id", order.id)
                        intent.putExtra("provider_id", order.assignedProvider)
                        intent.putExtra("provider_name", order.assignedProviderName)
                        intent.putExtra("provider_photo", providerPhotoUrl)
                        intent.putExtra("order_title", order.serviceName)
                        startActivity(intent)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OrderDetails", "Erro ao abrir chat: ${e.message}")
                    showToast("Erro ao abrir chat")
                }
            }
        }
    }

    /**
     * Mostra diálogo de cancelamento
     */
    private fun showCancelOrderDialog(order: OrderData) {
        val statusText = when (order.status) {
            OrderData.STATUS_AWAITING_PAYMENT -> "aguardando pagamento"
            OrderData.STATUS_DISTRIBUTING -> "em distribuição"
            OrderData.STATUS_PENDING -> "pendente"
            "quotes_received" -> "com cotações recebidas"
            OrderData.STATUS_ASSIGNED -> "atribuído a um prestador"
            else -> "neste status"
        }
        
        val message = when (order.status) {
            OrderData.STATUS_AWAITING_PAYMENT -> "Este pedido ainda está aguardando a confirmação do pagamento. Tem certeza que deseja cancelá-lo?"
            OrderData.STATUS_DISTRIBUTING -> "Este pedido ainda está sendo distribuído para prestadores. Tem certeza que deseja cancelá-lo?"
            OrderData.STATUS_PENDING -> "Este pedido está aguardando resposta de prestadores. Tem certeza que deseja cancelá-lo?"
            "quotes_received" -> "Este pedido já recebeu cotações de prestadores. Tem certeza que deseja cancelá-lo?"
            OrderData.STATUS_ASSIGNED -> "Este pedido já foi atribuído a um prestador. Tem certeza que deseja cancelá-lo?"
            else -> "Tem certeza que deseja cancelar este pedido? Esta ação não pode ser desfeita."
        }
        
        // Mensagem sobre reembolso — só faz sentido se o pedido realmente foi pago.
        val refundMessage = if (orderWasPaid(order)) {
            "\n\n💳 IMPORTANTE: O valor pago será reembolsado na sua conta em até 24 horas após o cancelamento."
        } else {
            ""
        }

        AlertDialog.Builder(this)
            .setTitle("❌ Cancelar Pedido")
            .setMessage(message + refundMessage)
            .setPositiveButton("Sim, Cancelar") { _, _ ->
                cancelOrder(order)
            }
            .setNegativeButton("Não, Manter") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Mais Informações") { _, _ ->
                showCancelInfoDialog(order)
            }
            .show()
    }
    
    /**
     * Mostra informações adicionais sobre o cancelamento
     */
    private fun showCancelInfoDialog(order: OrderData) {
        val info = when (order.status) {
            OrderData.STATUS_AWAITING_PAYMENT -> "• O pedido será removido antes de entrar em distribuição\n• Nenhum prestador será notificado\n• Você pode criar um novo pedido a qualquer momento"
            "distributing" -> "• O pedido será removido da lista de distribuição\n• Nenhum prestador será notificado\n• Você pode criar um novo pedido a qualquer momento"
            "pending" -> "• O pedido será removido da lista de pendentes\n• Prestadores que já viram o pedido serão notificados\n• Você pode criar um novo pedido a qualquer momento"
            "quotes_received" -> "• Todas as cotações serão perdidas\n• Prestadores serão notificados do cancelamento\n• Você pode criar um novo pedido a qualquer momento"
            "assigned" -> "• O prestador será notificado do cancelamento\n• Pode haver taxas de cancelamento\n• Você pode criar um novo pedido a qualquer momento"
            else -> "• O pedido será marcado como cancelado\n• Você pode criar um novo pedido a qualquer momento"
        }
        
        val refundInfo = if (orderWasPaid(order)) {
            "\n\n💳 REEMBOLSO:\n• O valor pago será reembolsado\n• O reembolso será processado em até 24 horas\n• O valor retornará na mesma forma de pagamento utilizada\n• Você receberá uma notificação quando o reembolso for processado"
        } else {
            ""
        }

        AlertDialog.Builder(this)
            .setTitle("ℹ️ Informações sobre Cancelamento")
            .setMessage(info + refundInfo)
            .setPositiveButton("Entendi") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /** Indica se o pedido realmente teve pagamento confirmado (logo, gera reembolso). */
    private fun orderWasPaid(order: OrderData): Boolean =
        order.paymentStatus?.lowercase() in setOf("paid", "captured", "approved", "confirmed")

    /**
     * Cancela o pedido
     */
    private fun cancelOrder(order: OrderData) {
        lifecycleScope.launch {
            try {
                showLoading(true)
                
                android.util.Log.d("OrderDetails", "🔄 Iniciando cancelamento do pedido: ${order.id}")
                
                // Cancelar o pedido no Firebase
                val result = orderManager.cancelOrder(
                    orderId = order.id,
                    cancelledBy = "client",
                    reason = "Cancelado pelo cliente"
                )
                
                if (result.isSuccess) {
                    android.util.Log.d("OrderDetails", "✅ Pedido cancelado com sucesso no Firebase")
                    
                    // Mostrar mensagem de sucesso com informação sobre reembolso
                    val successMessage = if (orderWasPaid(order)) {
                        "Seu pedido foi cancelado com sucesso!\n\n💳 O valor pago será reembolsado na sua conta em até 24 horas.\n\nVocê receberá uma notificação quando o reembolso for processado."
                    } else {
                        "Seu pedido foi cancelado com sucesso!"
                    }
                    AlertDialog.Builder(this@OrderDetailsActivity)
                        .setTitle("✅ Pedido Cancelado")
                        .setMessage(successMessage)
                        .setPositiveButton("Entendi") { _, _ ->
                            // Recarregar dados para atualizar a interface
                            loadOrderDetails()
                        }
                        .setCancelable(false)
                        .show()
                    
                    // Recarregar dados para atualizar a interface
                    loadOrderDetails()
                    
                    // Aguardar um pouco para garantir que o Firebase atualizou
                    kotlinx.coroutines.delay(500)
                    
                    // Verificar se o status foi atualizado
                    val updatedResult = orderManager.getOrderById(order.id)
                    if (updatedResult.isSuccess) {
                        val updatedOrder = updatedResult.getOrNull()
                        android.util.Log.d("OrderDetails", "📊 Status após cancelamento: ${updatedOrder?.status}")
                        
                        if (updatedOrder?.status == OrderData.STATUS_CANCELLED) {
                            android.util.Log.d("OrderDetails", "✅ Status confirmado como cancelado no Firebase")
                        } else {
                            android.util.Log.w("OrderDetails", "⚠️ Status não atualizado corretamente: ${updatedOrder?.status}")
                        }
                    }
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Erro desconhecido"
                    android.util.Log.e("OrderDetails", "❌ Erro ao cancelar pedido: $error")
                    showErrorMessage("Erro ao cancelar pedido: $error")
                }
                
            } catch (e: Exception) {
                android.util.Log.e("OrderDetails", "❌ Exceção ao cancelar pedido: ${e.message}", e)
                showErrorMessage("Erro ao cancelar pedido: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    /**
     * Mostra diálogo de reportar problema
     */
    private fun showReportProblemDialog(order: OrderData) {
        // TODO: Implementar diálogo de reportar problema
        showToast("📝 Funcionalidade de reportar problema em desenvolvimento")
    }

    /**
     * Mostra os detalhes da avaliação do pedido (somente leitura)
     */
    private fun showRatingDetailsDialog(order: OrderData) {
        if (order.rating == null || order.rating <= 0) {
            showToast("Este pedido ainda não possui avaliação")
            return
        }

        val detailed = mutableListOf<String>()
        order.qualityRating?.let { detailed.add("Qualidade: $it/5") }
        order.punctualityRating?.let { detailed.add("Pontualidade: $it/5") }
        order.communicationRating?.let { detailed.add("Comunicação: $it/5") }
        order.cleanlinessRating?.let { detailed.add("Limpeza: $it/5") }

        val message = buildString {
            append("Nota geral: ${order.rating}/5")
            if (detailed.isNotEmpty()) {
                append("\n\nDetalhes:\n")
                append(detailed.joinToString("\n"))
            }
            if (!order.review.isNullOrBlank()) {
                append("\n\nComentário:\n")
                append(order.review)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Avaliação do Serviço")
            .setMessage(message)
            .setPositiveButton("Fechar", null)
            .show()
    }

    /**
     * Navega para aba Serviços (único local onde cliente pode fazer pedido)
     */
    private fun createNewOrder() {
        val intent = Intent(this, ServicesActivity::class.java)
        startActivity(intent)
        finish()
    }

    /**
     * Mostra diálogo de mais opções
     */
    private fun showMoreOptionsDialog() {
        val options = arrayOf("Compartilhar", "Imprimir", "Exportar")
        
        AlertDialog.Builder(this)
            .setTitle("Mais Opções")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareOrder()
                    1 -> printOrder()
                    2 -> exportOrder()
                }
            }
            .show()
    }

    /**
     * Compartilha o pedido
     */
    private fun shareOrder() {
        // TODO: Implementar compartilhamento
        showToast("📤 Funcionalidade de compartilhamento em desenvolvimento")
    }

    /**
     * Imprime o pedido
     */
    private fun printOrder() {
        // TODO: Implementar impressão
        showToast("🖨️ Funcionalidade de impressão em desenvolvimento")
    }

    /**
     * Exporta o pedido
     */
    private fun exportOrder() {
        // TODO: Implementar exportação
        showToast("📄 Funcionalidade de exportação em desenvolvimento")
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
        Toast.makeText(this, "❌ $message", Toast.LENGTH_LONG).show()
    }

    /**
     * Exibe uma mensagem toast
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Ajusta o zoom para enquadrar prestador + cliente, garantindo que o MapView já
     * tenha sido medido (largura/altura > 0). Sem isso, o zoomToBoundingBox no primeiro
     * render (view ainda não laid-out) falha silenciosamente e o mapa abre desenquadrado.
     */
    private fun fitMapToPoints(map: org.osmdroid.views.MapView, points: List<GeoPoint>) {
        if (points.size < 2) return
        val box = org.osmdroid.util.BoundingBox.fromGeoPoints(points).increaseByScale(1.4f)
        val apply = Runnable {
            runCatching { map.zoomToBoundingBox(box, false) }
        }
        if (map.width > 0 && map.height > 0) apply.run() else map.post(apply)
    }

    /**
     * Configura mapa e distância (ponto do cliente + rota real até localização atual do prestador).
     * Se não houver coordenadas do cliente, oculta o cartão.
     */
    private fun setupMapAndDistance(order: OrderData) {
        val coords = order.coordinates
        if (coords == null) {
            binding.tvDistance.visibility = View.GONE
            binding.cardMap.visibility = View.GONE
            return
        }
        binding.cardMap.visibility = View.VISIBLE
        binding.tvDistance.visibility = View.GONE

        val map = binding.mapOrder
        val clientPoint = GeoPoint(coords.latitude, coords.longitude)
        currentClientPoint = clientPoint

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.5)
        map.controller.setCenter(clientPoint)

        // Salvar posição anterior do prestador antes de limpar
        val previousProviderPos = providerMarker?.position

        // Limpar overlays anteriores
        map.overlays.remove(clientMarker)
        map.overlays.remove(providerMarker)
        map.overlays.remove(routeLine)
        providerMarker = null
        routeLine = null

        // Cliente
        clientMarker = Marker(map).apply {
            position = clientPoint
            title = "Local do serviço"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.overlays.add(clientMarker)

        // Restaurar marcador do prestador se havia posição anterior
        if (previousProviderPos != null) {
            providerMarker = Marker(map).apply {
                position = previousProviderPos
                title = if (isProviderView) "Você" else "Prestador"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(this@OrderDetailsActivity, R.drawable.ic_location)
            }
            map.overlays.add(providerMarker)
            fitMapToPoints(map, listOf(previousProviderPos, clientPoint))
        }

        // Adicionar overlay único para detectar clique no mapa e expandir fullscreen
        // (o setOnClickListener no cardMap não funciona porque o MapView intercepta os toques)
        mapTapOverlay?.let { map.overlays.remove(it) }
        mapTapOverlay = object : org.osmdroid.views.overlay.Overlay() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent?, mapView: org.osmdroid.views.MapView?): Boolean {
                openFullScreenMap()
                return true
            }
        }
        mapTapOverlay?.let { map.overlays.add(it) }

        if (isProviderView) {
            // Prestador vendo: usar GPS do dispositivo
            startProviderLocationUpdates(clientPoint, map)
        } else {
            // Cliente vendo: buscar localização do prestador via Firestore
            val providerId = order.assignedProvider
            if (!providerId.isNullOrEmpty()) {
                startProviderLocationFromFirestore(providerId, clientPoint, map)
            }
        }
    }

    private fun openFullScreenMap() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = layoutInflater.inflate(R.layout.dialog_fullscreen_map, null)
        dialog.setContentView(view)

        val fullMap = view.findViewById<org.osmdroid.views.MapView>(R.id.mapFullscreen)
        val btnClose = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCloseMap)

        fullMap.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
        fullMap.setMultiTouchControls(true)

        // Adicionar marcador do cliente
        if (currentClientPoint != null) {
            val cm = org.osmdroid.views.overlay.Marker(fullMap).apply {
                position = currentClientPoint
                title = "Local do serviço"
                setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
            }
            fullMap.overlays.add(cm)
        }

        // Adicionar marcador do prestador
        val providerPos = providerMarker?.position
        if (providerPos != null) {
            val pm = org.osmdroid.views.overlay.Marker(fullMap).apply {
                position = providerPos
                title = if (isProviderView) "Você" else "Prestador"
                setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(this@OrderDetailsActivity, R.drawable.ic_location)
            }
            fullMap.overlays.add(pm)
        }

        // Desenhar a mesma rota (trajeto) do mini-mapa, se já calculada
        routeLine?.actualPoints?.let { routePoints ->
            if (routePoints.isNotEmpty()) {
                val rl = org.osmdroid.views.overlay.Polyline().apply {
                    outlinePaint.color = ContextCompat.getColor(this@OrderDetailsActivity, R.color.primary_color)
                    outlinePaint.strokeWidth = 10f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    setPoints(routePoints)
                }
                fullMap.overlays.add(rl)
            }
        }

        // Ajustar zoom
        val points = listOfNotNull(currentClientPoint, providerPos)
        if (points.size == 2) {
            val bb = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
            fullMap.post {
                fullMap.zoomToBoundingBox(bb.increaseByScale(1.3f), false)
            }
        } else if (currentClientPoint != null) {
            fullMap.controller.setZoom(15.5)
            fullMap.controller.setCenter(currentClientPoint)
        }

        btnClose.setOnClickListener {
            fullMap.onDetach()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            fullMap.onDetach()
        }

        dialog.show()
    }

    /**
     * Escuta localização do prestador via Firestore (para visão do cliente).
     */
    private fun startProviderLocationFromFirestore(providerId: String, clientPoint: GeoPoint, map: org.osmdroid.views.MapView) {
        providerLocationListener?.remove()
        providerLocationListener = db.collection("users").document(providerId)
            .addSnapshotListener { doc, error ->
                if (error != null || doc == null || !doc.exists()) return@addSnapshotListener
                val lat = doc.getDouble("latitude") ?: return@addSnapshotListener
                val lng = doc.getDouble("longitude") ?: return@addSnapshotListener

                val providerPoint = GeoPoint(lat, lng)
                if (providerMarker == null) {
                    providerMarker = Marker(map).apply {
                        position = providerPoint
                        title = "Prestador"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = ContextCompat.getDrawable(this@OrderDetailsActivity, R.drawable.ic_location)
                    }
                    map.overlays.add(providerMarker)
                } else {
                    providerMarker?.position = providerPoint
                }

                if (shouldUpdateRoute(providerPoint)) {
                    fetchRouteFromOSRM(providerPoint, clientPoint, map)
                    lastRouteUpdateAt = System.currentTimeMillis()
                    lastProviderPoint = providerPoint
                }

                fitMapToPoints(map, listOf(providerPoint, clientPoint))
                map.invalidate()
            }
    }

    /**
     * Inicia atualização contínua da localização do prestador.
     */
    private fun startProviderLocationUpdates(clientPoint: GeoPoint, map: org.osmdroid.views.MapView) {
        // Evitar múltiplos callbacks ativos
        stopProviderLocationUpdates()

        val fine = androidx.core.app.ActivityCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.app.ActivityCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!fine && !coarse) {
            binding.tvDistance.text = "Permissão de localização não concedida."
            binding.tvDistance.visibility = View.VISIBLE
            pendingLocationPermissionPurpose = LocationPermissionPurpose.MAP
            requestLocationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        if (!LocationPermissionHelper.isLocationEnabled(this)) {
            binding.tvDistance.text = "Ative a localização do dispositivo para calcular sua rota."
            binding.tvDistance.visibility = View.VISIBLE
            LocationPermissionHelper.showEnableLocationDialog(this)
            return
        }

        binding.tvDistance.text = "Obtendo sua localização para calcular a rota..."
        binding.tvDistance.visibility = View.VISIBLE
        ProviderLocationForegroundService.start(this)

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setMinUpdateDistanceMeters(10f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                updateProviderLocation(loc, clientPoint, map)
            }
        }

        // Primeira tentativa rápida
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                updateProviderLocation(loc, clientPoint, map)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest!!,
            locationCallback as LocationCallback,
            Looper.getMainLooper()
        )
    }

    /**
     * Interrompe atualizações de localização.
     */
    private fun stopProviderLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        locationRequest = null
        providerLocationListener?.remove()
        providerLocationListener = null
    }

    /**
     * Atualiza marcador do prestador e recalcula rota quando necessário.
     */
    private fun updateProviderLocation(loc: Location, clientPoint: GeoPoint, map: org.osmdroid.views.MapView) {
        val providerPoint = GeoPoint(loc.latitude, loc.longitude)
        if (providerMarker == null) {
            providerMarker = Marker(map).apply {
                position = providerPoint
                title = "Você"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(this@OrderDetailsActivity, R.drawable.ic_location)
            }
            map.overlays.add(providerMarker)
        } else {
            providerMarker?.position = providerPoint
        }

        if (shouldUpdateRoute(providerPoint)) {
            fetchRouteFromOSRM(providerPoint, clientPoint, map)
            lastRouteUpdateAt = System.currentTimeMillis()
            lastProviderPoint = providerPoint
        }

        // Ajustar zoom para mostrar ambos os pontos
        fitMapToPoints(map, listOf(providerPoint, clientPoint))
        map.invalidate()
    }

    /**
     * Atualiza rota se o prestador se moveu ou passou tempo suficiente.
     */
    private fun shouldUpdateRoute(newPoint: GeoPoint): Boolean {
        val now = System.currentTimeMillis()
        if (lastProviderPoint == null) return true
        val currentPoint = lastProviderPoint ?: return true
        val results = FloatArray(1)
        Location.distanceBetween(
            currentPoint.latitude,
            currentPoint.longitude,
            newPoint.latitude,
            newPoint.longitude,
            results
        )
        val movedEnough = results[0] >= 25f
        val timeEnough = now - lastRouteUpdateAt >= 30_000L
        return movedEnough || timeEnough
    }

    /**
     * Busca rota real via OSRM (gratuito, sem API key) e desenha no mapa.
     * Fallback para linha reta se a API falhar.
     */
    private fun fetchRouteFromOSRM(from: GeoPoint, to: GeoPoint, map: org.osmdroid.views.MapView) {
        lifecycleScope.launch {
            try {
                val path = "/route/v1/driving/" +
                    "${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
                    "?overview=full&geometries=geojson"

                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    var parsed: Triple<MutableList<GeoPoint>, Double, Double>? = null

                    // 1) Fonte primária: proxy de rota no nosso backend (TLS confiável).
                    try {
                        val url = "$routeProxyBase?from=${from.longitude},${from.latitude}" +
                            "&to=${to.longitude},${to.latitude}"
                        val request = okhttp3.Request.Builder()
                            .url(url)
                            .header("User-Agent", "AquiResolve/1.0")
                            .build()
                        osrmClient.newCall(request).execute().use { response ->
                            val body = response.body?.string()
                            if (response.isSuccessful && body != null) {
                                val json = org.json.JSONObject(body)
                                if (json.optBoolean("ok", false) && json.has("coordinates")) {
                                    val coordinates = json.getJSONArray("coordinates")
                                    val points = mutableListOf<GeoPoint>()
                                    for (i in 0 until coordinates.length()) {
                                        val coord = coordinates.getJSONArray(i)
                                        points.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
                                    }
                                    if (points.isNotEmpty()) {
                                        parsed = Triple(points, json.getDouble("distance"), json.getDouble("duration"))
                                    }
                                }
                            } else {
                                android.util.Log.w("OrderDetails", "Backend route HTTP ${response.code}")
                            }
                            Unit
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("OrderDetails", "Backend route falhou: ${e.message}")
                    }

                    // 2) Fallback: OSRM público direto (funciona em dispositivos com TLS 1.3 OK).
                    if (parsed != null) return@withContext parsed
                    for (host in osrmHosts) {
                        try {
                            val request = okhttp3.Request.Builder()
                                .url(host + path)
                                .header("User-Agent", "AquiResolve/1.0")
                                .build()
                            osrmClient.newCall(request).execute().use { response ->
                                val body = response.body?.string()
                                if (response.isSuccessful && body != null) {
                                    val json = org.json.JSONObject(body)
                                    val routes = json.getJSONArray("routes")
                                    if (routes.length() > 0) {
                                        val route = routes.getJSONObject(0)
                                        val distanceMeters = route.getDouble("distance")
                                        val durationSeconds = route.getDouble("duration")
                                        val geometry = route.getJSONObject("geometry")
                                        val coordinates = geometry.getJSONArray("coordinates")
                                        val points = mutableListOf<GeoPoint>()
                                        for (i in 0 until coordinates.length()) {
                                            val coord = coordinates.getJSONArray(i)
                                            points.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
                                        }
                                        parsed = Triple(points, distanceMeters, durationSeconds)
                                    }
                                } else {
                                    android.util.Log.e("OrderDetails", "OSRM HTTP ${response.code} em $host")
                                }
                                Unit
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("OrderDetails", "OSRM host $host falhou: ${e.message}")
                        }
                        if (parsed != null) break
                    }
                    parsed
                }

                if (result != null) {
                    val (points, distanceMeters, durationSeconds) = result

                    // Desenhar rota real
                    map.overlays.remove(routeLine)
                    routeLine = Polyline().apply {
                        outlinePaint.color = ContextCompat.getColor(this@OrderDetailsActivity, R.color.primary_color)
                        outlinePaint.strokeWidth = 8f
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                        setPoints(points)
                    }
                    map.overlays.add(routeLine)
                    map.invalidate()

                    // Mostrar distância e tempo
                    val distText = if (distanceMeters >= 1000) {
                        "%.1f km".format(distanceMeters / 1000.0)
                    } else {
                        "%.0f m".format(distanceMeters)
                    }
                    val minutes = (durationSeconds / 60).toInt()
                    val timeText = if (minutes >= 60) {
                        "${minutes / 60}h${minutes % 60}min"
                    } else {
                        "${minutes} min"
                    }
                    binding.tvDistance.text = "Distância: $distText ~ $timeText de carro"
                    binding.tvDistance.visibility = View.VISIBLE
                } else {
                    drawFallbackRoute(from, to, map)
                }
            } catch (e: Exception) {
                android.util.Log.e("OrderDetails", "OSRM falhou: ${e.message}", e)
                drawFallbackRoute(from, to, map)
            }
        }
    }

    /**
     * Desenha linha reta como fallback quando OSRM não está disponível.
     */
    private fun drawFallbackRoute(from: GeoPoint, to: GeoPoint, map: org.osmdroid.views.MapView) {
        map.overlays.remove(routeLine)
        routeLine = Polyline().apply {
            outlinePaint.color = ContextCompat.getColor(this@OrderDetailsActivity, R.color.primary_color)
            outlinePaint.strokeWidth = 6f
            addPoint(from)
            addPoint(to)
        }
        map.overlays.add(routeLine)

        val results = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, results)
        val distText = if (results[0] >= 1000) {
            "%.1f km".format(results[0] / 1000.0)
        } else {
            "%.0f m".format(results[0])
        }
        binding.tvDistance.text = "Distância: $distText (linha reta)"
        binding.tvDistance.visibility = View.VISIBLE
        map.invalidate()
    }

    override fun onResume() {
        super.onResume()
        binding.mapOrder.onResume()
        currentClientPoint?.let { clientPoint ->
            if (isProviderView) {
                startProviderLocationUpdates(clientPoint, binding.mapOrder)
            } else {
                val providerId = order?.assignedProvider
                if (!providerId.isNullOrEmpty()) {
                    startProviderLocationFromFirestore(providerId, clientPoint, binding.mapOrder)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopProviderLocationUpdates()
        binding.mapOrder.onPause()
    }

    override fun onDestroy() {
        stopProviderLocationUpdates()
        runCatching { binding.mapOrder.onDetach() }
        super.onDestroy()
    }
    
    /**
     * Controla o estado de carregamento
     */
    private fun showLoading(loading: Boolean) {
        if (loading) {
            binding.loadingState.visibility = View.VISIBLE
            binding.contentLayout.visibility = View.GONE
        } else {
            binding.loadingState.visibility = View.GONE
            binding.contentLayout.visibility = View.VISIBLE
        }
    }

    /**
     * Inicia o fluxo da OS: registra GPS, cria checklist, navega para ChecklistActivity
     */
    private fun startOsFlow(order: OrderData) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this)
                .setTitle("Permissão de Localização")
                .setMessage("Para iniciar o serviço, precisamos da sua localização GPS.")
                .setPositiveButton("Permitir") { _, _ ->
                    pendingLocationPermissionPurpose = LocationPermissionPurpose.START_OS
                    requestLocationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        lifecycleScope.launch {
            try {
                showLoading(true)

                var latitude: Double? = null
                var longitude: Double? = null
                try {
                    val location = fusedLocationClient.lastLocation.await()
                    if (location != null) {
                        latitude = location.latitude
                        longitude = location.longitude
                    }
                } catch (e: Exception) {
                    android.util.Log.w("OrderDetails", "GPS não disponível: ${e.message}")
                }

                val statusResult = orderManager.startService(order.id)
                if (statusResult.isFailure) {
                    showErrorMessage("Erro ao iniciar serviço: ${statusResult.exceptionOrNull()?.message}")
                    showLoading(false)
                    return@launch
                }

                val checklistResult = checklistManager.startService(order.id, latitude, longitude)
                if (checklistResult.isFailure) {
                    showErrorMessage("Erro ao criar checklist")
                    showLoading(false)
                    return@launch
                }

                showLoading(false)
                showSuccessMessage("Serviço iniciado!")

                val intent = Intent(this@OrderDetailsActivity, ChecklistActivity::class.java).apply {
                    putExtra("order_id", order.id)
                    putExtra("is_provider_view", true)
                }
                startActivity(intent)

            } catch (e: Exception) {
                showLoading(false)
                showErrorMessage("Erro ao iniciar OS: ${e.message}")
            }
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (pendingLocationPermissionPurpose) {
                LocationPermissionPurpose.START_OS -> order?.let { startOsFlow(it) }
                LocationPermissionPurpose.MAP -> currentClientPoint?.let {
                    startProviderLocationUpdates(it, binding.mapOrder)
                }
                null -> Unit
            }
        } else {
            if (pendingLocationPermissionPurpose == LocationPermissionPurpose.MAP) {
                binding.tvDistance.text = "Permissão de localização necessária para calcular a rota."
                binding.tvDistance.visibility = View.VISIBLE
            } else {
                showErrorMessage("Permissão de localização necessária para iniciar o serviço")
            }
        }
        pendingLocationPermissionPurpose = null
    }

    /**
     * Continua o checklist da OS
     */
    private fun continueOs(checklist: OsChecklistData) {
        val intent = Intent(this, when (checklist.status) {
            OsChecklistData.STATUS_CHECKLIST_PENDING -> ChecklistActivity::class.java
            OsChecklistData.STATUS_PHOTOS_PENDING -> PhotoEvidenceActivity::class.java
            OsChecklistData.STATUS_SIGNATURES_PENDING -> DigitalSignatureActivity::class.java
            else -> OsHistoryActivity::class.java
        }).apply {
            putExtra("order_id", checklist.orderId)
            putExtra("is_provider_view", true)
            putExtra("client_name", order?.clientName)
            putExtra("order_protocol", order?.protocol)
        }
        startActivity(intent)
    }

    /**
     * Visualiza o histórico completo da OS
     */
    private fun viewOsHistory(order: OrderData) {
        val intent = Intent(this, OsHistoryActivity::class.java).apply {
            putExtra("order_id", order.id)
        }
        startActivity(intent)
    }

} 
