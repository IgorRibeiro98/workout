package com.example.domain.engine

import java.text.Normalizer
import java.util.Locale

/**
 * Intelligent search engine for exercises.
 * Matches exercise names, primary and secondary muscles, equipment, and aliases/synonyms
 * with accent-insensitivity and contextual query expansion.
 */
object ExerciseSearchEngine {

    private fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val normalized = Normalizer.normalize(text.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "").trim()
    }

    // Contextual aliases mapping search keywords to associated exercise terminology and variations
    private val SEARCH_ALIASES: Map<String, List<String>> = mapOf(
        "peito" to listOf("peitoral", "chest", "supino", "crucifixo", "crossover", "voador", "peck deck", "flexao"),
        "peitoral" to listOf("peito", "chest", "supino", "crucifixo", "crossover", "voador", "peck deck"),
        "costas" to listOf("dorsal", "lat", "lats", "back", "remada", "puxada", "barra fixa", "pulldown", "terra", "serrote"),
        "dorsal" to listOf("costas", "lat", "remada", "puxada", "pulldown"),
        "ombro" to listOf("ombros", "deltoide", "deltoides", "shoulder", "desenvolvimento", "elevacao lateral", "elevacao frontal", "arnold"),
        "ombros" to listOf("ombro", "deltoide", "deltoides", "shoulder", "desenvolvimento", "elevacao lateral"),
        "deltoide" to listOf("ombro", "ombros", "shoulder", "desenvolvimento", "elevacao lateral"),
        "biceps" to listOf("braco", "biceps", "rosca", "martelo", "scott", "concentrada"),
        "triceps" to listOf("braco", "triceps", "corda", "testa", "pulley", "frances", "mergulho", "paralelas"),
        "braco" to listOf("biceps", "triceps", "antebraco", "rosca"),
        "bracos" to listOf("biceps", "triceps", "antebraco", "rosca"),
        "perna" to listOf("pernas", "inferiores", "legs", "quadriceps", "posterior", "gluteo", "agachamento", "leg press", "extensora", "flexora", "stiff"),
        "pernas" to listOf("perna", "inferiores", "legs", "quadriceps", "posterior", "gluteo", "agachamento", "leg press", "extensora", "flexora", "stiff"),
        "coxa" to listOf("quadriceps", "posterior", "agachamento", "leg press", "extensora"),
        "quadriceps" to listOf("coxa", "perna", "agachamento", "leg press", "extensora", "hack", "afundo", "bulgaro"),
        "posterior" to listOf("isquiotibiais", "isquios", "mesa flexora", "cadeira flexora", "stiff", "rdl", "terra"),
        "gluteo" to listOf("gluteos", "glutes", "bumbum", "elevacao pelvica", "hip thrust", "coice", "abducao", "agachamento sumo"),
        "gluteos" to listOf("gluteo", "glutes", "bumbum", "elevacao pelvica", "hip thrust", "coice", "abducao"),
        "panturrilha" to listOf("panturrilhas", "gemeos", "soleo", "calf", "calves", "gemeo"),
        "abdomen" to listOf("abdominal", "abs", "core", "prancha", "crunch", "plank", "elevacao de pernas", "roda"),
        "abdominal" to listOf("abdomen", "abs", "core", "prancha", "crunch", "plank"),
        "core" to listOf("abdomen", "abdominal", "prancha", "lombar"),
        "lombar" to listOf("costas", "hiperextensao", "terra", "good morning"),
        "antebraco" to listOf("punho", "forearm", "flexao de punho", "extensao de punho")
    )

    private val EQUIPMENT_ALIASES: Map<String, List<String>> = mapOf(
        "halter" to listOf("halteres", "dumbbell", "dumbbells"),
        "halteres" to listOf("halter", "dumbbell", "dumbbells"),
        "barra" to listOf("barbell", "olimpica", "w"),
        "maquina" to listOf("machine", "aparelho", "guiado", "smith"),
        "cabo" to listOf("polia", "cable", "crossover", "pulley"),
        "polia" to listOf("cabo", "cable", "pulley"),
        "livre" to listOf("peso corporal", "bodyweight", "calistenia"),
        "peso corporal" to listOf("bodyweight", "livre", "calistenia", "barra fixa", "flexao", "paralelas"),
        "elastico" to listOf("band", "elástico", "miniband")
    )

    /**
     * Checks whether an exercise matches a given search query.
     */
    fun matches(
        query: String,
        name: String,
        primaryMuscle: String? = null,
        secondaryMuscles: String? = null,
        equipment: String? = null,
        notes: String? = null
    ): Boolean {
        val cleanQuery = normalize(query)
        if (cleanQuery.isBlank()) return true

        val normName = normalize(name)
        val normPrimary = normalize(primaryMuscle)
        val normSecondary = normalize(secondaryMuscles)
        val normEquip = normalize(equipment)
        val normNotes = normalize(notes)

        val fullTargetText = "$normName $normPrimary $normSecondary $normEquip $normNotes"

        // 1. Direct contains check
        if (fullTargetText.contains(cleanQuery)) {
            return true
        }

        // 2. Tokenized query check (all words in query present in target text)
        val queryTokens = cleanQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val allTokensMatch = queryTokens.all { token -> fullTargetText.contains(token) }
        if (allTokensMatch) {
            return true
        }

        // 3. Synonym / Alias expansion
        for (token in queryTokens) {
            val aliases = SEARCH_ALIASES[token].orEmpty() + EQUIPMENT_ALIASES[token].orEmpty()
            val anyAliasMatch = aliases.any { alias ->
                val normAlias = normalize(alias)
                fullTargetText.contains(normAlias)
            }
            if (anyAliasMatch) {
                return true
            }
        }

        return false
    }

    /**
     * Filters a list of items using the smart search matching.
     */
    fun <T> filter(
        items: List<T>,
        query: String,
        nameSelector: (T) -> String,
        primaryMuscleSelector: (T) -> String? = { null },
        secondaryMusclesSelector: (T) -> String? = { null },
        equipmentSelector: (T) -> String? = { null },
        notesSelector: (T) -> String? = { null }
    ): List<T> {
        val cleanQuery = normalize(query)
        if (cleanQuery.isBlank()) return items

        return items.filter { item ->
            matches(
                query = cleanQuery,
                name = nameSelector(item),
                primaryMuscle = primaryMuscleSelector(item),
                secondaryMuscles = secondaryMusclesSelector(item),
                equipment = equipmentSelector(item),
                notes = notesSelector(item)
            )
        }.sortedWith(compareByDescending { item ->
            val normName = normalize(nameSelector(item))
            when {
                normName.startsWith(cleanQuery) -> 3
                normName.contains(cleanQuery) -> 2
                else -> 1
            }
        })
    }
}
