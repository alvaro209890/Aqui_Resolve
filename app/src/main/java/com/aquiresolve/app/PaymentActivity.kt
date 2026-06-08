package com.aquiresolve.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aquiresolve.app.constants.PaymentResultCodes
import com.aquiresolve.app.databinding.ActivityPaymentBinding
import com.aquiresolve.app.models.payment.*
import com.aquiresolve.app.payment.CardValidationResult
import com.aquiresolve.app.payment.PagarMeManager
import com.aquiresolve.app.payment.PaymentResult
import kotlinx.coroutines.launch

/**
 * Activity para processar pagamento com cartão de crédito via Pagar.me
 */
class PaymentActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "PaymentActivity"
        const val EXTRA_ORDER_ID = "order_id"
        const val EXTRA_ORDER_DESCRIPTION = "order_description"
        const val EXTRA_ORDER_AMOUNT = "order_amount"
        const val EXTRA_CLIENT_NAME = "client_name"
        const val EXTRA_CLIENT_EMAIL = "client_email"
        const val EXTRA_CLIENT_PHONE = "client_phone"
        const val EXTRA_CLIENT_ADDRESS = "client_address"
        const val EXTRA_CLIENT_CITY = "client_city"
        const val EXTRA_CLIENT_STATE = "client_state"
        const val EXTRA_CLIENT_CPF = "client_cpf"
    }
    
    private lateinit var binding: ActivityPaymentBinding
    private lateinit var pagarMeManager: PagarMeManager
    
    private var orderId: String = ""
    private var orderDescription: String = ""
    private var orderAmount: Double = 0.0
    private var clientName: String = ""
    private var clientEmail: String = ""
    private var clientPhone: String = ""
    private var clientAddress: String = ""
    private var clientCity: String = ""
    private var clientState: String = ""
    private var clientCpf: String = ""

    // Cashback
    private val cashbackManager = CashbackManager()
    private var cashbackBalance: Double = 0.0
    private var appliedCashback: Double = 0.0
    private var cashbackConfig: CashbackManager.CashbackConfig = CashbackManager.CashbackConfig()
    private val clientId: String
        get() = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private val pixPaymentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "PixPaymentActivity finalizada")
        handlePixPaymentResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityPaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Inicializar PagarMeManager
        pagarMeManager = PagarMeManager(this)
        
        // Configurar toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
        
        // Obter dados da intent
        getIntentData()
        
        // Configurar UI
        setupUI()
        setupTextWatchers()
        setupClickListeners()
        setupCashback()
    }

    /**
     * Valor efetivamente cobrado (valor do pedido menos o cashback aplicado).
     */
    private fun effectiveAmount(): Double = (orderAmount - appliedCashback).coerceAtLeast(0.0)

    /**
     * Carrega config + saldo de cashback e, se elegível, exibe a opção de uso.
     */
    private fun setupCashback() {
        lifecycleScope.launch {
            try {
                cashbackConfig = cashbackManager.getConfig()
                cashbackBalance = if (clientId.isNotEmpty()) cashbackManager.getBalance(clientId) else 0.0

                val eligible = cashbackConfig.enabled &&
                    cashbackConfig.allowRedeem &&
                    cashbackBalance > 0.0 &&
                    clientId.isNotEmpty()

                if (!eligible) {
                    binding.layoutCashback.visibility = View.GONE
                    return@launch
                }

                binding.layoutCashback.visibility = View.VISIBLE
                binding.tvCashbackAvailable.text =
                    String.format("Disponível: R$ %.2f", cashbackBalance)

                binding.switchUseCashback.setOnCheckedChangeListener { _, isChecked ->
                    applyCashback(isChecked)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao preparar cashback: ${e.message}")
                binding.layoutCashback.visibility = View.GONE
            }
        }
    }

    /**
     * Aplica (ou remove) o desconto de cashback, respeitando o teto definido
     * pelo painel admin (maxRedeemPercentage) e o saldo disponível.
     */
    private fun applyCashback(use: Boolean) {
        if (use) {
            val maxByOrder = orderAmount * cashbackConfig.maxRedeemFraction
            appliedCashback = minOf(cashbackBalance, maxByOrder)
            appliedCashback = Math.round(appliedCashback * 100.0) / 100.0
        } else {
            appliedCashback = 0.0
        }
        updateCashbackUI()
    }

    private fun updateCashbackUI() {
        if (appliedCashback > 0.0) {
            binding.layoutDiscountRow.visibility = View.VISIBLE
            binding.layoutFinalTotalRow.visibility = View.VISIBLE
            binding.tvCashbackDiscount.text = String.format("- R$ %.2f", appliedCashback)
            binding.tvFinalAmount.text = String.format("R$ %.2f", effectiveAmount())

            // Avisar quando o saldo não pôde ser usado totalmente (teto do admin)
            if (appliedCashback < cashbackBalance) {
                binding.tvCashbackAvailable.text = String.format(
                    "Disponível: R$ %.2f (limite de %.0f%% por pedido)",
                    cashbackBalance, cashbackConfig.maxRedeemPercentage
                )
            }
        } else {
            binding.layoutDiscountRow.visibility = View.GONE
            binding.layoutFinalTotalRow.visibility = View.GONE
        }
    }

    /**
     * Resgata o cashback aplicado após um pagamento bem-sucedido.
     */
    private fun redeemAppliedCashbackIfAny() {
        if (appliedCashback <= 0.0 || clientId.isEmpty()) return
        lifecycleScope.launch {
            cashbackManager.redeem(clientId, appliedCashback, orderId)
        }
    }
    
    /**
     * Obter dados passados pela intent
     */
    private fun getIntentData() {
        orderId = intent.getStringExtra(EXTRA_ORDER_ID) ?: ""
        orderDescription = intent.getStringExtra(EXTRA_ORDER_DESCRIPTION) ?: ""
        orderAmount = intent.getDoubleExtra(EXTRA_ORDER_AMOUNT, 0.0)
        clientName = intent.getStringExtra(EXTRA_CLIENT_NAME) ?: ""
        clientEmail = intent.getStringExtra(EXTRA_CLIENT_EMAIL) ?: ""
        clientPhone = intent.getStringExtra(EXTRA_CLIENT_PHONE) ?: ""
        clientAddress = intent.getStringExtra(EXTRA_CLIENT_ADDRESS) ?: ""
        clientCity = intent.getStringExtra(EXTRA_CLIENT_CITY) ?: ""
        clientState = intent.getStringExtra(EXTRA_CLIENT_STATE) ?: ""
        clientCpf = intent.getStringExtra(EXTRA_CLIENT_CPF) ?: ""
    }
    
    /**
     * Configurar elementos da UI
     */
    private fun setupUI() {
        // Exibir resumo do pedido
        binding.tvOrderDescription.text = orderDescription
        binding.tvOrderAmount.text = String.format("R$ %.2f", orderAmount)
    }
    
    /**
     * Configurar observadores de texto para formatação automática
     */
    private fun setupTextWatchers() {
        // Formatar número do cartão
        binding.etCardNumber.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true
                
                val formatted = pagarMeManager.formatCardNumber(s.toString())
                binding.etCardNumber.setText(formatted)
                binding.etCardNumber.setSelection(formatted.length)
                
                isUpdating = false
            }
        })
        
        // Formatar data de expiração
        binding.etExpiryDate.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true
                
                val formatted = pagarMeManager.formatExpiryDate(s.toString())
                binding.etExpiryDate.setText(formatted)
                binding.etExpiryDate.setSelection(formatted.length)
                
                isUpdating = false
            }
        })
        
        // Formatar CPF
        binding.etCpf.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true
                
                val clean = s.toString().replace(Regex("[^\\d]"), "")
                val formatted = when {
                    clean.length <= 3 -> clean
                    clean.length <= 6 -> "${clean.substring(0, 3)}.${clean.substring(3)}"
                    clean.length <= 9 -> "${clean.substring(0, 3)}.${clean.substring(3, 6)}.${clean.substring(6)}"
                    else -> "${clean.substring(0, 3)}.${clean.substring(3, 6)}.${clean.substring(6, 9)}-${clean.substring(9, minOf(11, clean.length))}"
                }
                
                binding.etCpf.setText(formatted)
                binding.etCpf.setSelection(formatted.length)
                
                isUpdating = false
            }
        })
        
        // Formatar CEP
        binding.etZipCode.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true
                
                val clean = s.toString().replace(Regex("[^\\d]"), "")
                val formatted = if (clean.length > 5) {
                    "${clean.substring(0, 5)}-${clean.substring(5, minOf(8, clean.length))}"
                } else {
                    clean
                }
                
                binding.etZipCode.setText(formatted)
                binding.etZipCode.setSelection(formatted.length)
                
                isUpdating = false
            }
        })
        
        // Formatar telefone
        binding.etPhone.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                isUpdating = true
                
                val clean = s.toString().replace(Regex("[^\\d]"), "")
                val formatted = when {
                    clean.length <= 2 -> clean
                    clean.length <= 6 -> "(${clean.substring(0, 2)}) ${clean.substring(2)}"
                    clean.length <= 10 -> "(${clean.substring(0, 2)}) ${clean.substring(2, 6)}-${clean.substring(6)}"
                    else -> "(${clean.substring(0, 2)}) ${clean.substring(2, 7)}-${clean.substring(7, minOf(11, clean.length))}"
                }
                
                binding.etPhone.setText(formatted)
                binding.etPhone.setSelection(formatted.length)
                
                isUpdating = false
            }
        })
    }
    
    /**
     * Configurar listeners de cliques
     */
    private fun setupClickListeners() {
        binding.btnPayWithPix.setOnClickListener {
            navigateToPixPayment()
        }
        
        binding.btnPayWithCard.setOnClickListener {
            // Já está na tela de cartão, não faz nada
            Toast.makeText(this, "Você já está na opção de pagamento com cartão", Toast.LENGTH_SHORT).show()
        }
        
        binding.btnPay.setOnClickListener {
            processPayment()
        }
    }
    
    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Cancelar Pagamento?")
            .setMessage("Se você sair agora, seu pedido NÃO será criado.\n\nPara criar o pedido, é necessário efetuar o pagamento.")
            .setPositiveButton("Sair e Cancelar") { _, _ ->
                // Retornar como cancelado
                setResult(RESULT_CANCELED)
                super.onBackPressed()
            }
            .setNegativeButton("Continuar Aqui", null)
            .show()
    }
    
    /**
     * Navegar para pagamento PIX
     */
    private fun navigateToPixPayment() {
        val intent = Intent(this, PixPaymentActivity::class.java).apply {
            putExtra(PixPaymentActivity.EXTRA_ORDER_ID, orderId)
            putExtra(PixPaymentActivity.EXTRA_ORDER_DESCRIPTION, orderDescription)
            putExtra(PixPaymentActivity.EXTRA_ORDER_AMOUNT, effectiveAmount())
            putExtra(PixPaymentActivity.EXTRA_CLIENT_NAME, clientName)
            putExtra(PixPaymentActivity.EXTRA_CLIENT_EMAIL, clientEmail)
            putExtra(PixPaymentActivity.EXTRA_CLIENT_PHONE, clientPhone)
            putExtra(PixPaymentActivity.EXTRA_CLIENT_CPF, clientCpf)
        }
        pixPaymentLauncher.launch(intent)
    }

    /**
     * Processar pagamento
     */
    private fun processPayment() {
        // Limpar erros anteriores
        binding.tilCardNumber.error = null
        binding.tilCardHolder.error = null
        binding.tilExpiryDate.error = null
        binding.tilCvv.error = null
        binding.tilCpf.error = null
        binding.tilZipCode.error = null
        binding.tilPhone.error = null
        
        // Obter valores dos campos
        val cardNumber = binding.etCardNumber.text.toString().trim()
        val cardHolder = binding.etCardHolder.text.toString().trim()
        val expiryDate = binding.etExpiryDate.text.toString().trim()
        val cvv = binding.etCvv.text.toString().trim()
        val cpf = binding.etCpf.text.toString().replace(Regex("[^\\d]"), "")
        val zipCode = binding.etZipCode.text.toString().replace(Regex("[^\\d]"), "")
        val phone = binding.etPhone.text.toString().replace(Regex("[^\\d]"), "")
        
        // Validar campos básicos
        if (!validateFields(cardNumber, cardHolder, expiryDate, cvv, cpf, zipCode, phone)) {
            return
        }
        
        // Validar dados do cartão
        val validationResult = pagarMeManager.validateCardData(cardNumber, cardHolder, expiryDate, cvv)
        
        when (validationResult) {
            is CardValidationResult.Invalid -> {
                showValidationErrors(validationResult.errors)
                return
            }
            is CardValidationResult.Valid -> {
                // Prosseguir com pagamento
                confirmAndPay(cardNumber, cardHolder, expiryDate, cvv, cpf, zipCode, phone)
            }
        }
    }

    /**
     * Validar campos básicos
     */
    private fun validateFields(
        cardNumber: String,
        cardHolder: String,
        expiryDate: String,
        cvv: String,
        cpf: String,
        zipCode: String,
        phone: String
    ): Boolean {
        var isValid = true
        
        if (cardNumber.isBlank()) {
            binding.tilCardNumber.error = "Informe o número do cartão"
            isValid = false
        }
        
        if (cardHolder.isBlank()) {
            binding.tilCardHolder.error = "Informe o nome do titular"
            isValid = false
        }
        
        if (expiryDate.isBlank()) {
            binding.tilExpiryDate.error = "Informe a validade"
            isValid = false
        }
        
        if (cvv.isBlank()) {
            binding.tilCvv.error = "Informe o CVV"
            isValid = false
        }
        
        if (cpf.length != 11) {
            binding.tilCpf.error = "CPF inválido"
            isValid = false
        }
        
        if (zipCode.length != 8) {
            binding.tilZipCode.error = "CEP inválido"
            isValid = false
        }
        
        if (phone.length < 10) {
            binding.tilPhone.error = "Telefone inválido"
            isValid = false
        }
        
        return isValid
    }
    
    /**
     * Mostrar erros de validação
     */
    private fun showValidationErrors(errors: List<String>) {
        val message = errors.joinToString("\n• ", prefix = "• ")
        
        AlertDialog.Builder(this)
            .setTitle("Dados Inválidos")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Confirmar e processar pagamento
     */
    private fun confirmAndPay(
        cardNumber: String,
        cardHolder: String,
        expiryDate: String,
        cvv: String,
        cpf: String,
        zipCode: String,
        phone: String
    ) {
        val cashbackLine = if (appliedCashback > 0.0)
            "\nCashback aplicado: - R$ ${String.format("%.2f", appliedCashback)}" else ""
        val message = """
            Confirma o pagamento de R$ ${String.format("%.2f", effectiveAmount())}?$cashbackLine

            Cartão: **** **** **** ${cardNumber.takeLast(4)}
            Titular: $cardHolder
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("Confirmar Pagamento")
            .setMessage(message)
            .setPositiveButton("Confirmar") { _, _ ->
                executePayment(cardNumber, cardHolder, expiryDate, cvv, cpf, zipCode, phone)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Executar pagamento
     */
    private fun executePayment(
        cardNumber: String,
        cardHolder: String,
        expiryDate: String,
        cvv: String,
        cpf: String,
        zipCode: String,
        phone: String
    ) {
        // Mostrar loading
        setLoading(true)
        
        lifecycleScope.launch {
            try {
                // Preparar dados do cartão
                val cleanCardNumber = cardNumber.replace(" ", "")
                val cleanExpiryDate = expiryDate.replace("/", "")
                
                val cardData = CardData(
                    cardNumber = cleanCardNumber,
                    cardHolderName = cardHolder.uppercase(),
                    cardExpirationDate = cleanExpiryDate,
                    cardCvv = cvv
                )
                
                // Preparar dados do cliente
                val areaCode = if (phone.length >= 2) phone.substring(0, 2) else "11"
                val phoneNumber = if (phone.length > 2) phone.substring(2) else phone
                
                val customerInfo = CustomerInfo(
                    name = clientName,
                    email = clientEmail,
                    document = cpf,
                    documentType = "cpf",
                    type = "individual",
                    phones = PhoneInfo(
                        mobilePhone = PhoneDetails(
                            countryCode = "55",
                            areaCode = areaCode,
                            number = phoneNumber
                        )
                    )
                )
                
                // Preparar endereço de cobrança
                val billingAddress = BillingAddress(
                    line1 = clientAddress,
                    line2 = null,
                    zipCode = zipCode,
                    city = clientCity,
                    state = clientState,
                    country = "BR"
                )
                
                // Processar pagamento
                val result = pagarMeManager.processPayment(
                    cardData = cardData,
                    customerInfo = customerInfo,
                    billingAddress = billingAddress,
                    amount = effectiveAmount(),
                    description = orderDescription,
                    orderId = orderId
                )
                
                // Processar resultado
                handlePaymentResult(result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar pagamento", e)
                setLoading(false)
                showError("Erro ao processar pagamento: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Processar resultado do pagamento
     */
    private fun handlePaymentResult(result: PaymentResult) {
        setLoading(false)
        
        when (result) {
            is PaymentResult.Success -> {
                Log.d(TAG, "Pagamento aprovado")

                // Resgatar o cashback aplicado, se houver
                redeemAppliedCashbackIfAny()

                // Retornar sucesso para a activity anterior
                val resultIntent = Intent().apply {
                    putExtra(PaymentResultCodes.EXTRA_TRANSACTION_ID, result.transactionId)
                    putExtra(PaymentResultCodes.EXTRA_PAYMENT_STATUS, result.status)
                    putExtra(PaymentResultCodes.EXTRA_PAYMENT_METHOD, "Cartão de Crédito")
                }
                setResult(PaymentResultCodes.RESULT_PAYMENT_SUCCESS, resultIntent)

                // Finalizar para retornar resultado ao CreateOrderActivity
                finish()
            }
            
            is PaymentResult.Pending -> {
                Log.d(TAG, "Pagamento pendente")
                
                val resultIntent = Intent().apply {
                    putExtra(PaymentResultCodes.EXTRA_TRANSACTION_ID, result.transactionId)
                    putExtra(PaymentResultCodes.EXTRA_PAYMENT_STATUS, "pending")
                    putExtra(PaymentResultCodes.EXTRA_PAYMENT_METHOD, "Cartão de Crédito")
                }
                setResult(PaymentResultCodes.RESULT_PAYMENT_PENDING, resultIntent)
                
                // Finalizar para retornar resultado ao CreateOrderActivity
                finish()
            }
            
            is PaymentResult.Error -> {
                Log.e(TAG, "Erro no pagamento")
                
                val resultIntent = Intent().apply {
                    putExtra(PaymentResultCodes.EXTRA_ERROR_MESSAGE, result.message)
                }
                setResult(PaymentResultCodes.RESULT_PAYMENT_FAILED, resultIntent)
                
                showError(result.message)
            }
        }
    }
    
    /**
     * Navegar para tela de confirmação de pagamento
     */
    private fun navigateToPaymentConfirmation(
        transactionId: String,
        paymentMethod: String,
        cardLastDigits: String
    ) {
        val confirmationIntent = Intent(this, PaymentConfirmationActivity::class.java).apply {
            putExtra(PaymentConfirmationActivity.EXTRA_TRANSACTION_ID, transactionId)
            putExtra(PaymentConfirmationActivity.EXTRA_AMOUNT, effectiveAmount())
            putExtra(PaymentConfirmationActivity.EXTRA_PAYMENT_METHOD, paymentMethod)
            putExtra(PaymentConfirmationActivity.EXTRA_CARD_LAST_DIGITS, cardLastDigits)
            putExtra(PaymentConfirmationActivity.EXTRA_SERVICE_DESCRIPTION, orderDescription)
            // Protocolo será gerado na tela de confirmação
        }
        startActivity(confirmationIntent)
        finish()
    }

    /**
     * Mostrar erro
     */
    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("❌ Erro no Pagamento")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNegativeButton("Tentar Novamente") { _, _ ->
                // Usuário pode tentar novamente
            }
            .show()
    }

    /**
     * Controlar estado de loading
     */
    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnPay.isEnabled = !loading
        binding.btnPay.text = if (loading) "Processando..." else "Pagar Agora"
    }

    private fun handlePixPaymentResult(resultCode: Int, data: Intent?) {
        when (resultCode) {
            PaymentResultCodes.RESULT_PAYMENT_SUCCESS -> {
                // Resgatar o cashback aplicado somente quando o PIX é confirmado
                redeemAppliedCashbackIfAny()
                setResult(resultCode, data)
                finish()
            }
            PaymentResultCodes.RESULT_PAYMENT_PENDING,
            PaymentResultCodes.RESULT_PAYMENT_FAILED -> {
                setResult(resultCode, data)
                finish()
            }
            Activity.RESULT_CANCELED -> {
                setResult(Activity.RESULT_CANCELED)
            }
        }
    }
} 
