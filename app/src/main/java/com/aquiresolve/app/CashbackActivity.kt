package com.aquiresolve.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aquiresolve.app.adapters.CashbackTransactionAdapter
import com.aquiresolve.app.databinding.ActivityCashbackBinding
import com.aquiresolve.app.models.CashbackTransaction
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Tela de cashback do cliente: saldo, total acumulado e extrato.
 */
class CashbackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCashbackBinding
    private lateinit var authManager: FirebaseAuthManager
    private val cashbackManager = CashbackManager()

    private val transactions = mutableListOf<CashbackTransaction>()
    private lateinit var adapter: CashbackTransactionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!FirebaseConfig.isInitialized()) {
            FirebaseConfig.initialize(this)
        }

        binding = ActivityCashbackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = ContextCompat.getColor(this, R.color.primary_color)

        authManager = FirebaseAuthManager(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = CashbackTransactionAdapter(transactions)
        binding.rvTransactions.layoutManager = LinearLayoutManager(this)
        binding.rvTransactions.adapter = adapter

        loadCashback()
    }

    private fun loadCashback() {
        val user = authManager.getLocalUserData()
        if (user == null) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = "Faça login para ver seu cashback."
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            val summary = cashbackManager.getSummary(user.uid)
            val history = cashbackManager.getHistory(user.uid)

            bindSummary(summary)

            transactions.clear()
            transactions.addAll(history)
            adapter.notifyDataSetChanged()

            binding.progressBar.visibility = View.GONE
            binding.tvEmpty.visibility = if (transactions.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** Preenche saldo, nível atual, taxa e progresso para o próximo nível. */
    private fun bindSummary(summary: CashbackManager.CashbackSummary) {
        binding.tvBalance.text = formatCurrency(summary.balance)
        binding.tvTotalEarned.text = "Total acumulado: ${formatCurrency(summary.totalEarned)}"

        // Sem níveis (admin desligou): esconde toda a parte de fidelidade.
        if (!summary.tiersEnabled) {
            binding.layoutTier.visibility = View.GONE
            binding.layoutProgram.visibility = View.GONE
            return
        }

        binding.layoutTier.visibility = View.VISIBLE
        binding.layoutProgram.visibility = View.VISIBLE

        val tier = summary.tier
        binding.tvTierBadge.text = "${tier.emoji} Nível ${tier.displayName}"
        binding.tvCurrentRate.text =
            "${CashbackManager.formatRate(summary.currentRate)} de cashback"

        binding.progressTier.progress = (summary.progressToNext * 100).toInt()
        val next = summary.nextTier
        if (next != null) {
            binding.progressTier.visibility = View.VISIBLE
            binding.tvProgressLabel.text =
                "Faltam ${formatCurrency(summary.amountToNextTier)} para o nível ${next.displayName}"
        } else {
            binding.progressTier.progress = 100
            binding.tvProgressLabel.text = "Você está no nível máximo. Aproveite! 🎉"
        }

        highlightCurrentTier(tier)
    }

    /** Destaca, na lista de níveis, o nível atual do cliente. */
    private fun highlightCurrentTier(tier: CashbackManager.CashbackTier) {
        binding.tvCurrentBronze.visibility =
            if (tier == CashbackManager.CashbackTier.BRONZE) View.VISIBLE else View.GONE
        binding.tvCurrentPrata.visibility =
            if (tier == CashbackManager.CashbackTier.PRATA) View.VISIBLE else View.GONE
        binding.tvCurrentOuro.visibility =
            if (tier == CashbackManager.CashbackTier.OURO) View.VISIBLE else View.GONE

        // Realce sutil no card do nível ativo.
        val activeBg = ContextCompat.getColor(this, R.color.light_blue)
        val inactiveBg = ContextCompat.getColor(this, R.color.white)
        binding.rowBronze.setCardBackgroundColor(
            if (tier == CashbackManager.CashbackTier.BRONZE) activeBg else inactiveBg
        )
        binding.rowPrata.setCardBackgroundColor(
            if (tier == CashbackManager.CashbackTier.PRATA) activeBg else inactiveBg
        )
        binding.rowOuro.setCardBackgroundColor(
            if (tier == CashbackManager.CashbackTier.OURO) activeBg else inactiveBg
        )
    }

    private fun formatCurrency(value: Double): String =
        String.format(Locale("pt", "BR"), "R$ %.2f", value)
}
