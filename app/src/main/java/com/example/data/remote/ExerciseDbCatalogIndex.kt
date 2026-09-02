package com.example.data.remote

import com.example.domain.engine.ExerciseDbNormalizer

/**
 * Índice em memória sobre o instantâneo do catálogo, usado para casar os exercícios
 * locais sem tocar na rede.
 *
 * Substitui a busca `GET /exercises/search` por exercício, que além de esbarrar no
 * rate limit devolvia apenas `exerciseId`, `name` e `gifUrl` — deixando a pontuação
 * de equipamento e músculo sempre zerada. Os itens do catálogo completo trazem
 * `equipments`, `targetMuscles` e `bodyParts`, então o scoring passa a valer.
 */
class ExerciseDbCatalogIndex(items: List<ExternalExerciseDto>) {

    private data class Entry(
        val dto: ExternalExerciseDto,
        val normalizedName: String,
        val tokens: Set<String>
    )

    private val entries: List<Entry> = items
        .filter { !it.gifUrl.isNullOrBlank() }
        .map { dto ->
            val norm = ExerciseDbNormalizer.normalize(dto.name)
            Entry(dto, norm, norm.split(" ").filter { it.length > 2 }.toSet())
        }

    private val byToken: Map<String, List<Entry>> = buildMap<String, MutableList<Entry>> {
        entries.forEach { entry ->
            entry.tokens.forEach { token -> getOrPut(token) { mutableListOf() }.add(entry) }
        }
    }

    val size: Int get() = entries.size

    /**
     * Candidatos plausíveis para [query], ordenados por sobreposição de termos e
     * limitados a [limit] para não diluir a checagem de ambiguidade da avaliação.
     */
    fun candidatesFor(query: String, limit: Int = 40): List<ExternalExerciseDto> {
        val normQuery = ExerciseDbNormalizer.normalize(query)
        if (normQuery.isEmpty() || entries.isEmpty()) return emptyList()

        val queryTokens = normQuery.split(" ").filter { it.length > 2 }.toSet()

        val pool = LinkedHashSet<Entry>()
        entries.firstOrNull { it.normalizedName == normQuery }?.let { pool.add(it) }
        queryTokens.forEach { token -> byToken[token]?.let { pool.addAll(it) } }
        entries.filter { it.normalizedName.contains(normQuery) || normQuery.contains(it.normalizedName) }
            .let { pool.addAll(it) }

        if (pool.isEmpty()) return emptyList()

        return pool
            .sortedWith(
                compareByDescending<Entry> { if (it.normalizedName == normQuery) 1 else 0 }
                    .thenByDescending { it.tokens.count { token -> queryTokens.contains(token) } }
                    .thenBy { it.normalizedName.length }
            )
            .take(limit)
            .map { it.dto }
    }

    fun findById(externalId: String): ExternalExerciseDto? {
        val clean = externalId.trim()
        if (clean.isEmpty()) return null
        return entries.firstOrNull { it.dto.realId == clean }?.dto
    }
}
