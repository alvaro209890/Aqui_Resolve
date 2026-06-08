package com.aquiresolve.app.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.aquiresolve.app.R
import com.aquiresolve.app.databinding.ItemCashbackTransactionBinding
import com.aquiresolve.app.models.CashbackTransaction
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Adapter do extrato de cashback do cliente.
 */
class CashbackTransactionAdapter(
    private val transactions: List<CashbackTransaction>
) : RecyclerView.Adapter<CashbackTransactionAdapter.TxViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale("pt", "BR"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TxViewHolder {
        val binding = ItemCashbackTransactionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TxViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TxViewHolder, position: Int) {
        holder.bind(transactions[position])
    }

    override fun getItemCount(): Int = transactions.size

    inner class TxViewHolder(private val binding: ItemCashbackTransactionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(tx: CashbackTransaction) {
            val context = binding.root.context
            binding.tvDescription.text = tx.description
            binding.tvDate.text = dateFormat.format(tx.createdAt.toDate())

            val isCredit = tx.type == CashbackTransaction.TYPE_EARN
            val sign = if (isCredit) "+" else "-"
            binding.tvAmount.text = String.format(Locale("pt", "BR"), "%sR$ %.2f", sign, Math.abs(tx.amount))
            binding.tvAmount.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isCredit) R.color.success_color else R.color.error_color
                )
            )
            binding.tvIcon.text = if (isCredit) "💸" else "🛒"
        }
    }
}
