package com.aquiresolve.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.aquiresolve.app.databinding.ActivityServicesBinding
import kotlinx.coroutines.launch

/**
 * ServicesActivity - Tela de serviços simplificada
 * 
 * Interface moderna para seleção de nichos de serviços
 * Segue o padrão da tela inicial com cards de categorias
 */
class ServicesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServicesBinding
    private lateinit var serviceManager: FirebaseServiceManager
    
    // Estado
    private var searchQuery = ""
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityServicesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        serviceManager = FirebaseServiceManager()
        
        setupWindowInsets()
        setupUI()
        setupClickListeners()
        setupSearchListener()
        populateSampleDataIfNeeded()
    }

    /**
     * Configura a interface
     */
    private fun setupUI() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.primary_color)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.background_color)
        
        hideEmptyState()
        hideLoadingState()
    }

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.appBarLayout.updatePadding(top = systemBars.top)
            binding.contentScroll.updatePadding(bottom = dpToPx(32) + systemBars.bottom)

            windowInsets
        }
    }

    /**
     * Configura os listeners de clique
     */
    private fun setupClickListeners() {
        // Botão voltar
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // Cards de nichos de serviços (novas categorias)
        binding.cardLimpeza.setOnClickListener {
            navigateToServiceCategory("estofados", "Limpeza de estofados")
        }
        
        binding.cardManutencao.setOnClickListener {
            navigateToServiceCategory("encanador", "Encanador")
        }
        
        binding.cardEletrica.setOnClickListener {
            navigateToServiceCategory("eletrica", "Elétrica")
        }
        
        binding.cardEncanamento.setOnClickListener {
            navigateToServiceCategory("instalacao", "Instalação")
        }
        
        binding.cardPintura.setOnClickListener {
            navigateToServiceCategory("caixa_dagua", "Caixa d'água")
        }
        
        binding.cardJardinagem.setOnClickListener {
            navigateToServiceCategory("desentupimento_manual", "Desentupimento manual")
        }
        
        binding.cardMudancas.setOnClickListener {
            navigateToServiceCategory("desentupimento_maquinario_2m", "Desentupimento com maquinário até 2 m")
        }
        
        binding.cardTecnologia.setOnClickListener {
            navigateToServiceCategory("caca_vazamentos", "Caça-vazamentos")
        }
        
        // Novos cards adicionados ao layout
        binding.cardArCondicionado.setOnClickListener {
            navigateToServiceCategory("ar_condicionado", "Ar condicionado")
        }
        binding.cardEletrodomesticos.setOnClickListener {
            navigateToServiceCategory("eletrodomesticos", "Eletrodomésticos")
        }
        binding.cardChaveiroResidencial.setOnClickListener {
            navigateToServiceCategory("chaveiro_residencial", "Chaveiro residencial")
        }
        binding.cardServicosAutomotivos.setOnClickListener {
            navigateToServiceCategory("servicos_automotivos", "Serviços automotivos")
        }
        binding.cardMontagemMoveis.setOnClickListener {
            navigateToServiceCategory("montagem_moveis", "Montagem de móveis")
        }
        binding.cardFaxina.setOnClickListener {
            navigateToServiceCategory("faxina", "Faxina")
        }
        // Botão popular dados (apenas para desenvolvimento)
        binding.btnPopulateData.setOnClickListener {
            populateSampleData()
        }
    }

    /**
     * Configura o listener de busca
     */
    private fun setupSearchListener() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                filterServiceCards()
            }
        })
    }

    /**
     * Filtra os cards de serviços baseado na busca
     */
    private fun filterServiceCards() {
        val cards = listOf(
            binding.cardLimpeza to "Limpeza de estofados",
            binding.cardManutencao to "Encanador",
            binding.cardEletrica to "Elétrica",
            binding.cardEncanamento to "Instalação",
            binding.cardPintura to "Caixa d'água",
            binding.cardJardinagem to "Desentupimento manual",
            binding.cardMudancas to "Desentupimento com maquinário até 2 m",
            binding.cardTecnologia to "Caça-vazamentos",
            binding.cardArCondicionado to "Ar condicionado",
            binding.cardEletrodomesticos to "Eletrodomésticos",
            binding.cardChaveiroResidencial to "Chaveiro residencial",
            binding.cardServicosAutomotivos to "Serviços automotivos",
            binding.cardMontagemMoveis to "Montagem de móveis",
            binding.cardFaxina to "Faxina"
        )
        
        if (searchQuery.isEmpty()) {
            // Mostrar todos os cards
            cards.forEach { (card, _) ->
                card.visibility = View.VISIBLE
            }
            hideEmptyState()
        } else {
            // Filtrar cards baseado na busca
            var visibleCards = 0
            cards.forEach { (card, name) ->
                if (name.contains(searchQuery, ignoreCase = true)) {
                    card.visibility = View.VISIBLE
                    visibleCards++
                } else {
                    card.visibility = View.GONE
                }
            }
            
            // Mostrar estado vazio se nenhum card estiver visível
            if (visibleCards == 0) {
                showEmptyState()
            } else {
                hideEmptyState()
            }
        }
    }

    /**
     * Navega para uma categoria específica de serviço
     */
    private fun navigateToServiceCategory(categoryId: String, categoryName: String) {
        android.util.Log.d("ServicesActivity", "🔄 Navegando para categoria: $categoryName ($categoryId)")
        
        // Por enquanto, navegar diretamente para criação de pedido com a categoria pré-selecionada
        val intent = Intent(this, CreateOrderActivity::class.java).apply {
            putExtra("service_niche", categoryId)
            putExtra("service_category_name", categoryName)
        }
        startActivity(intent)
        
        showToast("📋 Categoria selecionada: $categoryName")
    }

    /**
     * Popula dados de exemplo se necessário
     */
    private fun populateSampleDataIfNeeded() {
        // Por enquanto, não precisamos popular dados automaticamente
        // Os cards já estão funcionando com navegação direta
        android.util.Log.d("ServicesActivity", "✅ Tela de serviços carregada com sucesso")
    }

    /**
     * Popula dados de exemplo (apenas para desenvolvimento)
     */
    private fun populateSampleData() {
        lifecycleScope.launch {
            try {
                setLoadingState(true)
                showToast("🔄 Populando dados de exemplo...")
                
                serviceManager.populateSampleData()
                
                setLoadingState(false)
                showSuccessMessage("✅ Dados de exemplo criados com sucesso!")
                
            } catch (e: Exception) {
                setLoadingState(false)
                showErrorMessage("Erro ao popular dados: ${e.message}")
            }
        }
    }

    /**
     * Controla o estado de carregamento
     */
    private fun setLoadingState(loading: Boolean) {
        isLoading = loading
        if (loading) {
            showLoadingState()
        } else {
            hideLoadingState()
        }
    }

    /**
     * Mostra estado de carregamento
     */
    private fun showLoadingState() {
        binding.loadingState.visibility = View.VISIBLE
    }

    /**
     * Esconde estado de carregamento
     */
    private fun hideLoadingState() {
        binding.loadingState.visibility = View.GONE
    }

    /**
     * Mostra estado vazio
     */
    private fun showEmptyState() {
        binding.emptyState.visibility = View.VISIBLE
    }

    /**
     * Esconde estado vazio
     */
    private fun hideEmptyState() {
        binding.emptyState.visibility = View.GONE
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

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
