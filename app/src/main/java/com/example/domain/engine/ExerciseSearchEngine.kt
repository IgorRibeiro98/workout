package com.example.domain.engine

import java.text.Normalizer
import java.util.Locale

/**
 * Contextual search engine for exercises.
 *
 * The user thinks in concepts ("quero treinar ombro") rather than in catalog names
 * ("elevação lateral com halteres"). The engine therefore matches against every piece of
 * metadata the catalog already carries — name, primary muscle, secondary muscles, equipment
 * and aliases — and expands the query through two vocabularies:
 *
 *  - [MUSCLE_SYNONYM_GROUPS] / [EQUIPMENT_SYNONYM_GROUPS]: bidirectional groups, so any member
 *    of a group finds what the other members would find ("ombro" == "shoulder" == "deltoide").
 *  - [CONCEPT_HINTS]: one-directional hints from a concept to the exercises that train it, so
 *    "ombro" also surfaces "desenvolvimento militar" or "arnold press" even when the stored
 *    muscle name does not spell the concept out.
 *
 * The engine never creates or replaces catalog entries: it only improves discovery over the
 * metadata that already exists.
 */
object ExerciseSearchEngine {

    private fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val normalized = Normalizer.normalize(text.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "").trim()
    }

    /**
     * Muscle/region vocabulary. Every term inside a group is equivalent to every other term,
     * in both directions.
     */
    private val MUSCLE_SYNONYM_GROUPS: List<List<String>> = listOf(
        listOf("peito", "peitoral", "peitorais", "chest", "pec", "pectoral"),
        listOf("costas", "dorsal", "dorsais", "latissimo", "lat", "lats", "back", "grande dorsal"),
        listOf("ombro", "ombros", "deltoide", "deltoides", "deltoid", "delt", "shoulder", "shoulders"),
        listOf("biceps", "bicipite", "biceps braquial"),
        listOf("triceps", "tricipite", "triceps braquial"),
        listOf("braco", "bracos", "arm", "arms"),
        listOf("antebraco", "antebracos", "forearm", "forearms", "punho"),
        listOf("trapezio", "trapezios", "trap", "traps"),
        listOf("lombar", "eretores", "eretores da espinha", "lower back"),
        listOf("perna", "pernas", "inferiores", "membros inferiores", "leg", "legs"),
        listOf("quadriceps", "quadricipite", "coxa", "quad", "quads"),
        listOf("posterior", "posteriores", "posterior de coxa", "isquiotibiais", "isquios", "hamstring", "hamstrings"),
        listOf("gluteo", "gluteos", "glute", "glutes", "bumbum"),
        listOf("panturrilha", "panturrilhas", "gemeos", "gemeo", "soleo", "calf", "calves"),
        listOf("abdomen", "abdominal", "abdominais", "abs", "core"),
        listOf("adutor", "adutores", "adductor"),
        listOf("abdutor", "abdutores", "abductor"),
        listOf("corpo inteiro", "full body", "corpo todo", "geral")
    )

    /**
     * Equipment vocabulary, also bidirectional.
     */
    private val EQUIPMENT_SYNONYM_GROUPS: List<List<String>> = listOf(
        listOf("halter", "halteres", "dumbbell", "dumbbells", "peso livre"),
        listOf("barra", "barbell", "barra olimpica", "barra w", "barra ez"),
        listOf("maquina", "maquinas", "machine", "aparelho", "guiado", "smith"),
        listOf("cabo", "cabos", "polia", "cable", "crossover", "pulley"),
        listOf("kettlebell", "kettlebells", "russo"),
        listOf("elastico", "elasticos", "band", "bands", "miniband", "faixa"),
        listOf("peso corporal", "peso do corpo", "bodyweight", "calistenia", "sem equipamento"),
        listOf("banco", "bench"),
        listOf("anilha", "anilhas", "plate", "plates"),
        listOf("bola", "bola suica", "swiss ball", "fitball")
    )

    /**
     * One-directional muscle hierarchy: searching for a region also reaches the muscles that
     * belong to it, so "perna" finds an exercise stored as "Quadríceps" and "costas" finds one
     * stored as "Trapézio". Keys must be normalized members of [MUSCLE_SYNONYM_GROUPS].
     */
    private val REGION_MUSCLES: Map<String, List<String>> = mapOf(
        "perna" to listOf("quadriceps", "posterior", "isquiotibiais", "gluteo", "gluteos", "panturrilha", "panturrilhas", "coxa", "soleo", "adutor", "abdutor"),
        "costas" to listOf("dorsal", "dorsais", "trapezio", "lombar", "romboide", "romboides", "redondo"),
        "braco" to listOf("biceps", "triceps", "antebraco", "braquial", "braquiorradial"),
        "ombro" to listOf("deltoide", "deltoides", "trapezio", "manguito"),
        "peito" to listOf("peitoral", "peitorais", "serratil"),
        "abdomen" to listOf("obliquo", "obliquos", "transverso", "reto abdominal", "lombar"),
        "corpo inteiro" to listOf("peitoral", "dorsal", "quadriceps", "gluteo", "deltoide", "abdomen")
    )

    /**
     * One-directional hints: searching for the concept also surfaces exercises whose names
     * contain any of these terms. Keys must be normalized members of the groups above.
     */
    private val CONCEPT_HINTS: Map<String, List<String>> = mapOf(
        "peito" to listOf("supino", "crucifixo", "crossover", "voador", "peck deck", "flexao", "paralelas"),
        "costas" to listOf("remada", "puxada", "barra fixa", "pulldown", "pullover", "serrote", "levantamento terra"),
        "ombro" to listOf("desenvolvimento", "elevacao lateral", "elevacao frontal", "arnold", "remada alta", "crucifixo inverso", "encolhimento"),
        "biceps" to listOf("rosca", "martelo", "scott", "concentrada"),
        "triceps" to listOf("triceps testa", "triceps corda", "triceps frances", "mergulho", "paralelas", "supino fechado"),
        "braco" to listOf("rosca", "triceps", "martelo"),
        "antebraco" to listOf("flexao de punho", "extensao de punho", "rosca inversa"),
        "trapezio" to listOf("encolhimento", "remada alta"),
        "lombar" to listOf("hiperextensao", "levantamento terra", "good morning", "extensao lombar"),
        "perna" to listOf("agachamento", "leg press", "extensora", "flexora", "stiff", "afundo", "avanco", "bulgaro", "panturrilha"),
        "quadriceps" to listOf("agachamento", "leg press", "cadeira extensora", "hack", "afundo", "avanco", "bulgaro"),
        "posterior" to listOf("mesa flexora", "cadeira flexora", "stiff", "rdl", "levantamento terra romeno", "bom dia"),
        "gluteo" to listOf("elevacao pelvica", "hip thrust", "coice", "abducao", "agachamento sumo", "afundo"),
        "panturrilha" to listOf("panturrilha em pe", "panturrilha sentado", "gemeos"),
        "abdomen" to listOf("prancha", "crunch", "abdominal", "plank", "elevacao de pernas", "roda abdominal", "prancha lateral"),
        "adutor" to listOf("cadeira adutora", "adducao"),
        "abdutor" to listOf("cadeira abdutora", "abducao")
    )

    /**
     * Maps every normalized term to the full set of terms it is equivalent to (itself included).
     */
    private val synonymIndex: Map<String, Set<String>> = buildSynonymIndex()

    /**
     * Maps every normalized term to the exercise-name hints reachable from it.
     */
    private val hintIndex: Map<String, Set<String>> = buildHintIndex()

    /**
     * Maps every normalized term to the muscle names reachable from it through the region
     * hierarchy.
     */
    private val regionIndex: Map<String, Set<String>> = buildExpansion(REGION_MUSCLES)

    private fun buildSynonymIndex(): Map<String, Set<String>> {
        val index = mutableMapOf<String, MutableSet<String>>()
        (MUSCLE_SYNONYM_GROUPS + EQUIPMENT_SYNONYM_GROUPS).forEach { group ->
            val normalizedGroup = group.map { normalize(it) }.filter { it.isNotBlank() }.toSet()
            normalizedGroup.forEach { term ->
                index.getOrPut(term) { mutableSetOf() }.addAll(normalizedGroup)
            }
        }
        return index
    }

    private fun buildHintIndex(): Map<String, Set<String>> = buildExpansion(CONCEPT_HINTS)

    /**
     * Turns a concept -> terms map into an index where every synonym of the concept reaches the
     * same terms, so "shoulder" expands exactly like "ombro".
     */
    private fun buildExpansion(source: Map<String, List<String>>): Map<String, Set<String>> {
        val index = mutableMapOf<String, MutableSet<String>>()
        source.forEach { (concept, terms) ->
            val normalizedConcept = normalize(concept)
            val normalizedTerms = terms.map { normalize(it) }.filter { it.isNotBlank() }.toSet()
            val reachedBy = synonymIndex[normalizedConcept] ?: setOf(normalizedConcept)
            reachedBy.forEach { term ->
                index.getOrPut(term) { mutableSetOf() }.addAll(normalizedTerms)
            }
        }
        return index
    }

    /** Every term equivalent to [token], including the token itself. */
    private fun synonymsOf(token: String): Set<String> = synonymIndex[token] ?: setOf(token)

    /** Exercise-name hints reachable from [token] and its synonyms. */
    private fun hintsOf(token: String): Set<String> = hintIndex[token].orEmpty()

    /**
     * Terms up to this length are matched as whole words. Without it short synonyms produce
     * nonsense hits — "lat" would match "elevacao lateral" and rank a shoulder raise as a
     * back exercise.
     */
    private const val WHOLE_WORD_MAX_LENGTH = 5

    private val wholeWordPatterns: Map<String, Regex> =
        (synonymIndex.keys + synonymIndex.values.flatten() + hintIndex.values.flatten() + regionIndex.values.flatten())
            .filter { it.length <= WHOLE_WORD_MAX_LENGTH }
            .associateWith { term ->
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])")
            }

    /**
     * Whether [text] contains [term]: as a whole word for short terms, as a substring otherwise
     * so that "peito" still reaches "peitoral".
     */
    private fun containsTerm(text: String, term: String): Boolean {
        if (text.isBlank() || term.isBlank()) return false
        val pattern = wholeWordPatterns[term] ?: return text.contains(term)
        return pattern.containsMatchIn(text)
    }

    /**
     * Checks whether an exercise matches a given search query.
     *
     * Kept as the primary entry point used by the exercise list and the template editor.
     */
    fun matches(
        query: String,
        name: String,
        primaryMuscle: String? = null,
        secondaryMuscles: String? = null,
        equipment: String? = null,
        notes: String? = null
    ): Boolean = score(query, name, primaryMuscle, secondaryMuscles, equipment, notes) > 0

    /**
     * Relevance score for an exercise against a query. Zero means "does not match".
     *
     * Higher scores mean a more literal match: an exact name prefix outranks a name substring,
     * which outranks a muscle/equipment match, which outranks a contextual expansion.
     */
    fun score(
        query: String,
        name: String,
        primaryMuscle: String? = null,
        secondaryMuscles: String? = null,
        equipment: String? = null,
        notes: String? = null
    ): Int {
        val cleanQuery = normalize(query)
        if (cleanQuery.isBlank()) return 1

        val normName = normalize(name)
        val normPrimary = normalize(primaryMuscle)
        val normSecondary = normalize(secondaryMuscles)
        val normEquip = normalize(equipment)
        val normNotes = normalize(notes)
        val fullText = "$normName $normPrimary $normSecondary $normEquip $normNotes"

        // 1. Literal matches on the whole query string.
        when {
            normName.startsWith(cleanQuery) -> return 100
            normName.contains(cleanQuery) -> return 80
        }

        val queryTokens = cleanQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (queryTokens.isEmpty()) return 1

        // 2. Every token present somewhere in the exercise metadata.
        if (queryTokens.all { fullText.contains(it) }) {
            return when {
                queryTokens.all { normName.contains(it) } -> 70
                queryTokens.all { normPrimary.contains(it) } -> 55
                queryTokens.all { normEquip.contains(it) } -> 45
                else -> 35
            }
        }

        // 3. Contextual expansion: each token must be satisfied by itself or by a synonym/hint.
        // A multi-word query that is itself a known concept ("peso corporal") expands as a whole
        // instead of being split into tokens that mean nothing on their own.
        val expansionTokens = if (synonymIndex.containsKey(cleanQuery) || hintIndex.containsKey(cleanQuery)) {
            listOf(cleanQuery)
        } else {
            queryTokens
        }

        var weakest = Int.MAX_VALUE
        for (token in expansionTokens) {
            val tokenScore = scoreToken(token, normName, normPrimary, normSecondary, normEquip, normNotes)
            if (tokenScore == 0) return 0
            if (tokenScore < weakest) weakest = tokenScore
        }
        return if (weakest == Int.MAX_VALUE) 0 else weakest
    }

    private fun scoreToken(
        token: String,
        normName: String,
        normPrimary: String,
        normSecondary: String,
        normEquip: String,
        normNotes: String
    ): Int {
        val synonyms = synonymsOf(token)

        // Direct or synonym hit on the strongest fields first.
        if (synonyms.any { containsTerm(normName, it) }) return 60
        if (synonyms.any { containsTerm(normPrimary, it) }) return 50
        if (synonyms.any { containsTerm(normEquip, it) }) return 40
        if (synonyms.any { containsTerm(normSecondary, it) }) return 30
        if (synonyms.any { containsTerm(normNotes, it) }) return 20

        // Region hierarchy: "perna" reaches an exercise stored as "Quadriceps".
        val regionMuscles = regionIndex[token].orEmpty()
        if (regionMuscles.any { containsTerm(normPrimary, it) }) return 34
        if (regionMuscles.any { containsTerm(normSecondary, it) }) return 25

        // Finally, the concept -> exercise-name hints ("ombro" finds "elevacao lateral").
        val hints = hintsOf(token)
        if (hints.any { containsTerm(normName, it) }) return 15
        if (hints.any { containsTerm(normNotes, it) }) return 10

        return 0
    }

    /**
     * Filters a list of items using the contextual search, ordered by relevance.
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

        return items
            .map { item ->
                item to score(
                    query = cleanQuery,
                    name = nameSelector(item),
                    primaryMuscle = primaryMuscleSelector(item),
                    secondaryMuscles = secondaryMusclesSelector(item),
                    equipment = equipmentSelector(item),
                    notes = notesSelector(item)
                )
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }
}
