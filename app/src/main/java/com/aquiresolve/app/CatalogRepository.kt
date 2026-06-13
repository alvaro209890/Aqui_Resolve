package com.aquiresolve.app

import android.util.Log
import com.aquiresolve.app.utils.ServiceNicheCatalog
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Fonte única do catálogo de serviços (nichos) exibido no app.
 *
 * Lê a coleção `service_categories` (mantida pelo painel admin em /dashboard/servicos/catalogo-app),
 * filtra os ativos, ordena por `displayOrder` e alimenta o [ServiceNicheCatalog] para que o
 * matching prestador↔pedido reconheça também os nichos cadastrados no painel.
 *
 * Campos lidos de forma defensiva pois o painel grava variações redundantes:
 *  - ativo:   `active` | `isActive` | `enabled`
 *  - ordem:   `displayOrder` | `order` | `sortOrder`
 *  - nome:    `name` | `title` | `label`
 *  - apelidos:`aliases` | `keywords`
 *
 * Se o Firestore estiver vazio/indisponível, mantém o fallback estático do [ServiceNicheCatalog]
 * (comportamento idêntico ao anterior — zero regressão).
 */
object CatalogRepository {

    private const val TAG = "CatalogRepository"

    data class CatalogNiche(
        val name: String,
        val aliases: List<String>,
        val displayOrder: Int
    )

    @Volatile
    private var cache: List<CatalogNiche>? = null

    private val db: FirebaseFirestore
        get() = FirebaseFirestore.getInstance()

    /** Nomes dos nichos em cache; se vazio, usa o fallback estático. */
    fun cachedNicheNames(): List<String> {
        return cache?.map { it.name }?.takeIf { it.isNotEmpty() }
            ?: ServiceNicheCatalog.providerSelectableNiches
    }

    fun hasCache(): Boolean = cache?.isNotEmpty() == true

    private fun readBoolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        is Number -> value.toInt() != 0
        null -> true // ausência => considerado ativo
        else -> true
    }

    private fun readInt(vararg values: Any?): Int {
        for (v in values) {
            when (v) {
                is Number -> return v.toInt()
                is String -> v.toIntOrNull()?.let { return it }
                else -> {}
            }
        }
        return 0
    }

    private fun readString(vararg values: Any?): String {
        for (v in values) {
            val s = v?.toString()?.trim()
            if (!s.isNullOrEmpty()) return s
        }
        return ""
    }

    @Suppress("UNCHECKED_CAST")
    private fun readAliases(data: Map<String, Any?>): List<String> {
        val raw = (data["aliases"] as? List<*>) ?: (data["keywords"] as? List<*>) ?: emptyList<Any?>()
        return raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Carrega o catálogo do Firestore e aplica ao [ServiceNicheCatalog].
     * Retorna a lista carregada (vazia em caso de falha/coleção vazia, mantendo o fallback).
     */
    suspend fun load(): List<CatalogNiche> {
        return try {
            val snapshot = db.collection("service_categories").get().await()
            val niches = snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                if (!readBoolean(data["active"] ?: data["isActive"] ?: data["enabled"])) {
                    return@mapNotNull null
                }
                val name = readString(data["name"], data["title"], data["label"])
                if (name.isEmpty()) return@mapNotNull null
                CatalogNiche(
                    name = name,
                    aliases = readAliases(data),
                    displayOrder = readInt(data["displayOrder"], data["order"], data["sortOrder"])
                )
            }.sortedBy { it.displayOrder }

            if (niches.isNotEmpty()) {
                cache = niches
                ServiceNicheCatalog.applyDynamicCatalog(
                    niches.map { ServiceNicheCatalog.DynamicNiche(it.name, it.aliases) }
                )
                Log.d(TAG, "Catálogo carregado do Firestore: ${niches.size} nichos ativos")
            } else {
                Log.d(TAG, "service_categories vazio/sem ativos — mantendo fallback estático")
            }
            niches
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao carregar catálogo: ${e.message}")
            cache ?: emptyList()
        }
    }
}
