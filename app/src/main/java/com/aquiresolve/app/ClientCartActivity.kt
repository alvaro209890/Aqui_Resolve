package com.aquiresolve.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aquiresolve.app.adapters.CartItemsAdapter
import com.aquiresolve.app.constants.PaymentResultCodes
import com.aquiresolve.app.databinding.ActivityClientCartBinding
import com.aquiresolve.app.models.CartItemData
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClientCartActivity : AppCompatActivity() {

    companion object {
        private const val PENDING_CHECKOUT_PREFS = "pending_cart_checkout_prefs"
        private const val KEY_PENDING_CHECKOUT = "pending_checkout"
        private const val CART_CHECKOUT_PREFIX = "cart_checkout"
    }

    private data class PendingCartCheckoutSession(
        val checkoutCode: String,
        val orderIds: List<String>,
        val cartItemIds: List<String>,
        val orderCount: Int,
        val totalAmount: Double
    )

    private lateinit var binding: ActivityClientCartBinding
    private lateinit var cartAdapter: CartItemsAdapter
    private lateinit var cartManager: FirebaseCartManager
    private lateinit var authManager: FirebaseAuthManager

    private var cartItems: List<CartItemData> = emptyList()
    private var checkoutInProgress = false
    private var checkoutResultProcessed = false

    private val cashbackManager = CashbackManager()
    private val promotionManager = PromotionManager()
    private var promoConfig = CashbackManager.CashbackConfig()
    private var currentDiscount = PromotionManager.DiscountResult(0.0, 0.0, "")

    private fun resolveItemPrice(item: CartItemData): Double {
        if (item.estimatedPrice > 0) {
            return item.estimatedPrice
        }

        return com.aquiresolve.app.models.ServicePricing.getPrice(
            category = item.serviceNiche,
            serviceType = item.serviceType
        ) ?: com.aquiresolve.app.models.ServicePricing.getDefaultPrice(item.serviceNiche)
    }

    private val paymentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handlePaymentResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!FirebaseConfig.isInitialized()) {
            FirebaseConfig.initialize(this)
        }

        binding = ActivityClientCartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cartManager = FirebaseCartManager(this)
        authManager = FirebaseAuthManager(this)

        setupRecycler()
        setupClickListeners()
        loadCartItems()
    }

    override fun onResume() {
        super.onResume()
        if (!checkoutInProgress) {
            loadCartItems()
        }
    }

    private fun setupRecycler() {
        cartAdapter = CartItemsAdapter(
            onRemoveClick = { item -> confirmRemoveItem(item) }
        )

        binding.rvCartItems.apply {
            layoutManager = LinearLayoutManager(this@ClientCartActivity)
            adapter = cartAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnContinueShopping.setOnClickListener {
            startActivity(Intent(this, ServicesActivity::class.java))
        }

        binding.btnCheckoutCart.setOnClickListener {
            startCheckout()
        }
    }

    private fun loadCartItems() {
        val userId = authManager.getLocalUserData()?.uid
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            ?: return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val result = cartManager.getItems(userId)
                if (result.isSuccess) {
                    cartItems = result.getOrNull() ?: emptyList()
                    promoConfig = cashbackManager.getConfig()
                    cartAdapter.updateItems(cartItems)
                    updateSummary()
                } else {
                    showToast("❌ Erro ao carregar carrinho")
                }
            } catch (e: Exception) {
                showToast("❌ Erro ao carregar carrinho: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun updateSummary() {
        val subtotal = cartItems.sumOf(::resolveItemPrice)
        currentDiscount = promotionManager.computeDiscount(
            niches = cartItems.map { it.serviceNiche },
            subtotal = subtotal,
            config = promoConfig
        )
        val total = (subtotal - currentDiscount.amount).coerceAtLeast(0.0)

        binding.tvCartItemsCount.text = "${cartItems.size} item(ns)"
        binding.tvCartTotal.text = String.format("R$ %.2f", total)

        if (currentDiscount.hasDiscount) {
            binding.layoutSubtotal.visibility = View.VISIBLE
            binding.tvCartSubtotalValue.text = String.format("R$ %.2f", subtotal)
            binding.layoutDiscount.visibility = View.VISIBLE
            binding.tvDiscountLabel.text = currentDiscount.label
            binding.tvCartDiscount.text = String.format(
                "- R$ %.2f (%s)",
                currentDiscount.amount,
                CashbackManager.formatRate(currentDiscount.percent)
            )
        } else {
            binding.layoutSubtotal.visibility = View.GONE
            binding.layoutDiscount.visibility = View.GONE
        }

        val empty = cartItems.isEmpty()
        binding.tvEmptyCart.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvCartItems.visibility = if (empty) View.GONE else View.VISIBLE
        binding.layoutSummary.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun confirmRemoveItem(item: CartItemData) {
        val userId = authManager.getLocalUserData()?.uid
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            ?: return

        AlertDialog.Builder(this)
            .setTitle("Remover item")
            .setMessage("Deseja remover este serviço do carrinho?")
            .setPositiveButton("Remover") { _, _ ->
                lifecycleScope.launch {
                    val result = cartManager.removeItem(userId, item.id)
                    if (result.isSuccess) {
                        loadCartItems()
                        showToast("🗑️ Item removido")
                    } else {
                        showToast("❌ Não foi possível remover o item")
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startCheckout() {
        if (checkoutInProgress || cartItems.isEmpty()) {
            if (cartItems.isEmpty()) showToast("Seu carrinho está vazio")
            return
        }

        val userId = authManager.getLocalUserData()?.uid
            ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            ?: run {
                showToast("❌ Usuário não autenticado")
                return
            }

        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val user = authManager.getLocalUserData()
        val clientName = user?.fullName
            ?.takeIf { it.isNotBlank() }
            ?: currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: currentUser?.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: "Usuário"
        val clientEmail = user?.email
            ?.takeIf { it.isNotBlank() }
            ?: currentUser?.email
            ?.takeIf { it.isNotBlank() }
            ?: ""
        val clientPhone = user?.phone
            ?.takeIf { it.isNotBlank() }
            ?: currentUser?.phoneNumber
            ?.takeIf { it.isNotBlank() }
            ?: ""
        val totalAmount = cartItems.sumOf(::resolveItemPrice)

        if (totalAmount <= 0) {
            checkoutInProgress = false
            showToast("❌ O carrinho tem itens sem valor válido para pagamento")
            return
        }

        if (clientEmail.isBlank()) {
            checkoutInProgress = false
            showToast("❌ Não foi possível identificar o e-mail do cliente para o pagamento")
            return
        }

        checkoutInProgress = true
        checkoutResultProcessed = false
        setLoading(true)

        lifecycleScope.launch {
            var preparedCheckout: PreparedCartCheckout? = null

            try {
                val checkoutCode = buildCheckoutCode(userId)
                val preparationResult = cartManager.prepareCheckout(
                    userId = userId,
                    clientName = clientName,
                    clientEmail = clientEmail,
                    checkoutCode = checkoutCode,
                    clientPhone = clientPhone,
                    discountPercent = currentDiscount.percent
                )

                if (preparationResult.isFailure) {
                    throw preparationResult.exceptionOrNull()
                        ?: IllegalStateException("Não foi possível preparar o checkout")
                }

                val session = preparationResult.getOrNull()
                    ?: throw IllegalStateException("Sessão de checkout inválida")
                preparedCheckout = session
                savePendingCheckoutSession(session)

                val firstItem = cartItems.first()
                val intent = Intent(this@ClientCartActivity, PaymentActivity::class.java).apply {
                    putExtra(PaymentActivity.EXTRA_ORDER_ID, session.checkoutCode)
                    putExtra(
                        PaymentActivity.EXTRA_ORDER_DESCRIPTION,
                        "Carrinho (${session.orderCount} serviços)"
                    )
                    putExtra(PaymentActivity.EXTRA_ORDER_AMOUNT, session.totalAmount)
                    putExtra(PaymentActivity.EXTRA_CLIENT_NAME, clientName)
                    putExtra(PaymentActivity.EXTRA_CLIENT_EMAIL, clientEmail)
                    putExtra(PaymentActivity.EXTRA_CLIENT_PHONE, clientPhone)
                    putExtra(PaymentActivity.EXTRA_CLIENT_ADDRESS, firstItem.address)
                    putExtra(PaymentActivity.EXTRA_CLIENT_CITY, firstItem.city)
                    putExtra(PaymentActivity.EXTRA_CLIENT_STATE, firstItem.state)
                    putExtra(PaymentActivity.EXTRA_CLIENT_CPF, "")
                }

                setLoading(false)
                paymentLauncher.launch(intent)
            } catch (e: Exception) {
                preparedCheckout?.let { session ->
                    cartManager.cancelPreparedCheckout(session.orderIds)
                }
                clearPendingCheckoutSession()
                checkoutInProgress = false
                setLoading(false)
                showToast("❌ Erro ao preparar checkout: ${e.message}")
            }
        }
    }

    private fun handlePaymentResult(resultCode: Int, data: Intent?) {
        val pendingSession = getPendingCheckoutSession()
        if (pendingSession == null || checkoutResultProcessed) {
            checkoutInProgress = false
            return
        }

        checkoutInProgress = true

        when (resultCode) {
            PaymentResultCodes.RESULT_PAYMENT_SUCCESS -> {
                checkoutResultProcessed = true
                val transactionId = data?.getStringExtra(PaymentResultCodes.EXTRA_TRANSACTION_ID).orEmpty()
                val paymentMethod = data?.getStringExtra(PaymentResultCodes.EXTRA_PAYMENT_METHOD)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Pagamento Online"
                finalizeCheckout(
                    pendingSession = pendingSession,
                    transactionId = transactionId,
                    paymentStatus = data?.getStringExtra(PaymentResultCodes.EXTRA_PAYMENT_STATUS).orEmpty().ifBlank { "paid" },
                    paymentMethod = paymentMethod
                )
            }

            PaymentResultCodes.RESULT_PAYMENT_PENDING -> {
                checkoutResultProcessed = true
                val transactionId = data?.getStringExtra(PaymentResultCodes.EXTRA_TRANSACTION_ID).orEmpty()
                val paymentMethod = data?.getStringExtra(PaymentResultCodes.EXTRA_PAYMENT_METHOD)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Pagamento Online"
                finalizeCheckout(
                    pendingSession = pendingSession,
                    transactionId = transactionId,
                    paymentStatus = "pending",
                    paymentMethod = paymentMethod
                )
            }

            PaymentResultCodes.RESULT_PAYMENT_FAILED -> {
                val message = data?.getStringExtra(PaymentResultCodes.EXTRA_ERROR_MESSAGE)
                    ?: "Pagamento não aprovado"
                rollbackPreparedCheckout(pendingSession, "❌ $message")
            }

            Activity.RESULT_CANCELED -> {
                rollbackPreparedCheckout(pendingSession, "Pagamento cancelado")
            }

            else -> {
                checkoutInProgress = false
            }
        }
    }

    private fun finalizeCheckout(
        pendingSession: PendingCartCheckoutSession,
        transactionId: String,
        paymentStatus: String,
        paymentMethod: String
    ) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                if (transactionId.isNotBlank()) {
                    when (val syncResult = com.aquiresolve.app.payment.PagarMeManager(this@ClientCartActivity)
                        .checkPixPaymentStatus(transactionId)) {
                        is com.aquiresolve.app.payment.PixPaymentResult.Error -> {
                            android.util.Log.w(
                                "ClientCart",
                                "Backend ainda não confirmou a sincronização do carrinho: ${syncResult.message}"
                            )
                        }
                        else -> Unit
                    }
                }

                val createdOrders = pendingSession.orderIds
                val protocol = "CART-${SimpleDateFormat("yyyyMMddHHmm", Locale("pt", "BR")).format(Date())}"
                clearPendingCheckoutSession()
                checkoutInProgress = false

                val confirmationIntent = Intent(this@ClientCartActivity, PaymentConfirmationActivity::class.java).apply {
                    putExtra(PaymentConfirmationActivity.EXTRA_TRANSACTION_ID, transactionId)
                    putExtra(PaymentConfirmationActivity.EXTRA_AMOUNT, pendingSession.totalAmount)
                    putExtra(
                        PaymentConfirmationActivity.EXTRA_PAYMENT_METHOD,
                        if (paymentStatus == "pending") "$paymentMethod (Pendente)" else paymentMethod
                    )
                    putExtra(PaymentConfirmationActivity.EXTRA_SERVICE_TYPE, "Carrinho")
                    putExtra(
                        PaymentConfirmationActivity.EXTRA_SERVICE_DESCRIPTION,
                        "${createdOrders.size} serviço(s) finalizado(s) em conjunto"
                    )
                    putExtra(PaymentConfirmationActivity.EXTRA_PROTOCOL, protocol)
                }

                startActivity(confirmationIntent)
                finish()
            } catch (e: Exception) {
                checkoutInProgress = false
                showToast(
                    "❌ O pagamento foi processado, mas não foi possível sincronizar o carrinho: ${e.message}"
                )
            } finally {
                setLoading(false)
            }
        }
    }

    private fun rollbackPreparedCheckout(
        pendingSession: PendingCartCheckoutSession,
        message: String
    ) {
        setLoading(true)
        lifecycleScope.launch {
            val cleanupResult = cartManager.cancelPreparedCheckout(pendingSession.orderIds)
            clearPendingCheckoutSession()
            checkoutInProgress = false
            checkoutResultProcessed = false
            setLoading(false)

            if (cleanupResult.isFailure) {
                showToast(
                    "⚠️ O checkout foi interrompido, mas não foi possível remover os pedidos pendentes."
                )
            } else {
                showToast(message)
            }

            loadCartItems()
        }
    }

    private fun buildCheckoutCode(userId: String): String {
        return "${CART_CHECKOUT_PREFIX}_${userId}_${System.currentTimeMillis()}"
    }

    private fun savePendingCheckoutSession(session: PreparedCartCheckout) {
        val payload = JSONObject().apply {
            put("checkoutCode", session.checkoutCode)
            put("orderCount", session.orderCount)
            put("totalAmount", session.totalAmount)
            put("orderIds", JSONArray(session.orderIds))
            put("cartItemIds", JSONArray(session.cartItemIds))
        }

        getSharedPreferences(PENDING_CHECKOUT_PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_CHECKOUT, payload.toString())
            .apply()
    }

    private fun getPendingCheckoutSession(): PendingCartCheckoutSession? {
        val rawPayload = getSharedPreferences(PENDING_CHECKOUT_PREFS, MODE_PRIVATE)
            .getString(KEY_PENDING_CHECKOUT, null)
            ?.trim()
            .orEmpty()

        if (rawPayload.isBlank()) {
            return null
        }

        return try {
            val payload = JSONObject(rawPayload)
            PendingCartCheckoutSession(
                checkoutCode = payload.optString("checkoutCode").trim(),
                orderIds = payload.optJSONArray("orderIds").toStringList(),
                cartItemIds = payload.optJSONArray("cartItemIds").toStringList(),
                orderCount = payload.optInt("orderCount", 0),
                totalAmount = payload.optDouble("totalAmount", 0.0)
            ).takeIf { session ->
                session.checkoutCode.isNotBlank() &&
                    session.orderIds.isNotEmpty() &&
                    session.cartItemIds.isNotEmpty()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun clearPendingCheckoutSession() {
        getSharedPreferences(PENDING_CHECKOUT_PREFS, MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_CHECKOUT)
            .apply()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until length()) {
                optString(index)
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnCheckoutCart.isEnabled = !loading
        binding.btnContinueShopping.isEnabled = !loading
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
