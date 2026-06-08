package com.aquiresolve.app

/**
 * Calcula o desconto aplicável a um conjunto de serviços (itens do carrinho),
 * conforme a arte oficial do programa:
 *
 *  - **1ª Fase – Desconto direto** (só na fase `launch`): por número de serviços
 *    no carrinho — 2 → 5%, 3 → 10%, 4+ → 15%.
 *  - **Combos especiais** (valem nas duas fases): por combinação de categorias —
 *    Elétrica + Hidráulica + Instalações (15%), Elétrica + Hidráulica (10%),
 *    Instalações + Hidráulica (10%) e Manutenção de veículos (2+ serviços
 *    automotivos, 15%).
 *
 * O desconto final é o **maior** entre o de quantidade e o de combo aplicável.
 *
 * As categorias vêm do campo `serviceNiche` (nome da categoria, ex.: "Elétrica",
 * "Encanador", "Instalação", "Serviços automotivos").
 */
class PromotionManager {

    data class DiscountResult(
        val percent: Double,
        val amount: Double,
        val label: String
    ) {
        val hasDiscount: Boolean get() = percent > 0.0 && amount > 0.0
    }

    companion object {
        // Grupos lógicos da imagem -> nomes de categoria reais usados no app.
        private val ELETRICA = listOf("Elétrica")
        private val HIDRAULICA = listOf(
            "Encanador", "Hidráulica", "Caixa d'água",
            "Desentupimento manual", "Desentupimento com maquinário até 2 m",
            "Caça-vazamentos"
        )
        private val INSTALACOES = listOf("Instalação", "Eletrodomésticos", "Ar condicionado")
        private val VEICULOS = listOf("Serviços automotivos")

        private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

        private fun matches(niche: String, group: List<String>): Boolean =
            group.any { it.equals(niche.trim(), ignoreCase = true) }
    }

    /**
     * Calcula o desconto para uma lista de categorias (uma por serviço/item) e
     * um subtotal. Retorna percentual, valor em reais e um rótulo descritivo.
     */
    fun computeDiscount(
        niches: List<String>,
        subtotal: Double,
        config: CashbackManager.CashbackConfig
    ): DiscountResult {
        if (niches.isEmpty() || subtotal <= 0.0 || !config.enabled) {
            return DiscountResult(0.0, 0.0, "")
        }

        // Desconto por quantidade (só na 1ª Fase – lançamento).
        val quantityPercent =
            if (config.isLaunchPhase && config.directDiscountEnabled) {
                quantityDiscount(niches.size, config)
            } else 0.0

        // Melhor combo aplicável (vale nas duas fases).
        val combo = if (config.combosEnabled) bestCombo(niches, config) else null
        val comboPercent = combo?.second ?: 0.0

        val percent = maxOf(quantityPercent, comboPercent)
        if (percent <= 0.0) return DiscountResult(0.0, 0.0, "")

        val amount = round2(subtotal * percent / 100.0)
        val label = if (combo != null && comboPercent >= quantityPercent) {
            combo.first
        } else {
            "Desconto por ${niches.size} serviços"
        }
        return DiscountResult(percent, amount, label)
    }

    private fun quantityDiscount(count: Int, config: CashbackManager.CashbackConfig): Double =
        when {
            count >= 4 -> config.directDiscount4Plus
            count == 3 -> config.directDiscount3
            count == 2 -> config.directDiscount2
            else -> 0.0
        }

    /** Retorna (rótulo, percentual) do melhor combo aplicável, ou null. */
    private fun bestCombo(
        niches: List<String>,
        config: CashbackManager.CashbackConfig
    ): Pair<String, Double>? {
        val hasEletrica = niches.any { matches(it, ELETRICA) }
        val hasHidraulica = niches.any { matches(it, HIDRAULICA) }
        val hasInstalacoes = niches.any { matches(it, INSTALACOES) }
        val veiculosCount = niches.count { matches(it, VEICULOS) }

        val candidates = mutableListOf<Pair<String, Double>>()
        if (hasEletrica && hasHidraulica && hasInstalacoes) {
            candidates.add("Combo Elétrica + Hidráulica + Instalações" to config.comboEletricaHidraulicaInstalacoes)
        }
        if (veiculosCount >= 2) {
            candidates.add("Combo manutenção de veículos" to config.comboVeiculos)
        }
        if (hasEletrica && hasHidraulica) {
            candidates.add("Combo Elétrica + Hidráulica" to config.comboEletricaHidraulica)
        }
        if (hasInstalacoes && hasHidraulica) {
            candidates.add("Combo Instalações + Hidráulica" to config.comboInstalacoesHidraulica)
        }
        return candidates.filter { it.second > 0.0 }.maxByOrNull { it.second }
    }
}
