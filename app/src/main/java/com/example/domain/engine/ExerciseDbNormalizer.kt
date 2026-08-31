package com.example.domain.engine

import java.text.Normalizer
import java.util.Locale

object ExerciseDbNormalizer {

    /**
     * Normalizes text for resilient matching:
     * - Strips accents/diacritics (e.g., "bíceps" -> "biceps", "máquina" -> "maquina")
     * - Converts to lowercase
     * - Normalizes hyphens, slashes, and punctuation to spaces
     * - Trims extra whitespace
     */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        val withoutAccents = nfd.replace("\\p{M}".toRegex(), "")
        return withoutAccents
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    // Equipment Aliases Map (normalized PT-BR -> Set of ExerciseDB normalized equipment terms)
    private val equipmentAliases = mapOf(
        "barra" to setOf("barbell", "olympic barbell"),
        "barra ez" to setOf("ez barbell", "barbell"),
        "barra fixa" to setOf("body weight", "pull up bar", "assisted", "leverage machine"),
        "barra halter maquina" to setOf("barbell", "dumbbell", "machine", "leverage machine", "cable"),
        "barra maquina" to setOf("barbell", "machine", "leverage machine", "smith machine"),
        "halteres" to setOf("dumbbell"),
        "halter" to setOf("dumbbell"),
        "banco halteres" to setOf("dumbbell", "bench"),
        "peso corporal" to setOf("body weight", "assisted", "weighted"),
        "peso corporal peso" to setOf("body weight", "weighted", "assisted"),
        "livre" to setOf("body weight", "barbell", "dumbbell"),
        "cabo" to setOf("cable", "band", "resistance band"),
        "polia" to setOf("cable", "band", "resistance band"),
        "cabo elastico" to setOf("cable", "band", "resistance band"),
        "smith" to setOf("smith machine", "barbell"),
        "kettlebell" to setOf("kettlebell"),
        "elastico" to setOf("band", "resistance band"),
        "faixa elastica" to setOf("band", "resistance band"),
        "maquina" to setOf("leverage machine", "machine", "cable", "sled machine", "smith machine", "assisted"),
        "maquina banco" to setOf("leverage machine", "machine", "cable", "bench"),
        "cadeira romana" to setOf("body weight", "leverage machine", "bench", "assisted"),
        "banco" to setOf("bench", "dumbbell", "barbell", "body weight"),
        "banco 45" to setOf("bench", "incline bench", "dumbbell", "barbell"),
        "banco 45o" to setOf("bench", "incline bench", "dumbbell", "barbell"),
        "anilha" to setOf("weight plate", "weighted", "barbell", "dumbbell"),
        "anilhas" to setOf("weight plate", "weighted", "barbell", "dumbbell"),
        "bola suica" to setOf("stability ball", "swiss ball", "bosu ball", "ball"),
        "roda abdominal" to setOf("wheel roller", "roller", "ab wheel", "body weight"),
        "trap bar" to setOf("trap bar", "barbell"),
        "trx" to setOf("suspension", "body weight"),
        "suspensao" to setOf("suspension", "body weight"),
        "medicine ball" to setOf("medicine ball")
    )

    // Muscle Aliases Map (normalized PT-BR -> Set of ExerciseDB normalized targetMuscles & bodyParts)
    private val muscleAliases = mapOf(
        "peitoral" to setOf("pectorals", "chest", "serratus anterior"),
        "peitoral superior" to setOf("pectorals", "chest", "upper chest"),
        "peito" to setOf("pectorals", "chest"),
        "dorsal" to setOf("lats", "back", "upper back", "spine", "traps"),
        "dorsais" to setOf("lats", "back", "upper back"),
        "costas" to setOf("lats", "back", "upper back", "spine", "traps", "lower back"),
        "romboides" to setOf("upper back", "back", "traps", "lats"),
        "eretores da coluna" to setOf("spine", "lower back", "back", "glutes", "hamstrings"),
        "lombar" to setOf("spine", "lower back", "back"),
        "biceps" to setOf("biceps", "upper arms"),
        "braquial" to setOf("biceps", "forearms", "upper arms", "lower arms"),
        "braquiorradial" to setOf("forearms", "lower arms", "biceps", "upper arms"),
        "braquial braquiorradial" to setOf("biceps", "forearms", "upper arms", "lower arms"),
        "triceps" to setOf("triceps", "upper arms"),
        "deltoide" to setOf("delts", "shoulders"),
        "deltoides" to setOf("delts", "shoulders"),
        "deltoide anterior" to setOf("delts", "shoulders", "chest", "pectorals"),
        "deltoide lateral" to setOf("delts", "shoulders", "upper arms"),
        "deltoide posterior" to setOf("delts", "shoulders", "upper back", "traps"),
        "ombros" to setOf("delts", "shoulders"),
        "ombro" to setOf("delts", "shoulders"),
        "quadriceps" to setOf("quads", "quadriceps", "upper legs"),
        "quadriceps gluteos" to setOf("quads", "glutes", "upper legs", "quadriceps"),
        "posterior de coxa" to setOf("hamstrings", "upper legs", "glutes"),
        "posterior" to setOf("hamstrings", "upper legs", "glutes"),
        "isquiotibiais" to setOf("hamstrings", "upper legs"),
        "isquios" to setOf("hamstrings", "upper legs"),
        "gluteos" to setOf("glutes", "upper legs"),
        "gluteo" to setOf("glutes", "upper legs"),
        "gluteo medio" to setOf("glutes", "abductors", "upper legs"),
        "gluteos lombar" to setOf("glutes", "spine", "lower back", "upper legs", "back"),
        "panturrilhas" to setOf("calves", "lower legs"),
        "panturrilha" to setOf("calves", "lower legs"),
        "soleo" to setOf("calves", "lower legs"),
        "tibial anterior" to setOf("calves", "lower legs", "shins"),
        "abdomen" to setOf("abs", "waist", "core"),
        "core" to setOf("abs", "waist", "core", "spine"),
        "obliquos" to setOf("abs", "waist", "core"),
        "adutores core" to setOf("adductors", "abs", "waist", "core", "upper legs"),
        "core lombar" to setOf("abs", "waist", "core", "spine", "lower back", "back"),
        "antebraco" to setOf("forearms", "lower arms"),
        "pegada" to setOf("forearms", "lower arms", "grip"),
        "pegada dorsal" to setOf("forearms", "lats", "back", "lower arms"),
        "pegada trapezio" to setOf("forearms", "traps", "upper back", "lower arms"),
        "adutores" to setOf("adductors", "upper legs"),
        "abdutores" to setOf("abductors", "glutes", "upper legs"),
        "trapezio" to setOf("traps", "upper back", "neck")
    )

    fun getEquipmentAliases(ptEquipment: String?): Set<String> {
        val norm = normalize(ptEquipment)
        if (norm.isEmpty()) return emptySet()
        return equipmentAliases[norm] ?: setOf(norm)
    }

    fun getMuscleAliases(ptMuscle: String?): Set<String> {
        val norm = normalize(ptMuscle)
        if (norm.isEmpty()) return emptySet()
        return muscleAliases[norm] ?: setOf(norm)
    }

    /**
     * Evaluates equipment compatibility:
     * +20 if compatible alias matched
     * 0 if generic, empty, unknown, or acceptable difference
     * -10 if confirmed incompatible (e.g. dumbbell vs barbell)
     */
    fun evaluateEquipmentScore(ptEquipment: String?, candEquipments: List<String>): Int {
        val normPt = normalize(ptEquipment)
        if (normPt.isEmpty() || candEquipments.isEmpty()) return 0

        val normCands = candEquipments.map { normalize(it) }.filter { it.isNotEmpty() }
        if (normCands.isEmpty()) return 0

        val aliases = getEquipmentAliases(ptEquipment)
        val isCompatible = normCands.any { cand ->
            aliases.any { alias -> cand.contains(alias) || alias.contains(cand) } ||
            cand.contains(normPt) || normPt.contains(cand)
        }

        if (isCompatible) return 20

        // Incompatibility check: only penalize if both sides are specific and distinctly incompatible
        val isCandDumbbell = normCands.any { it.contains("dumbbell") }
        val isCandBarbell = normCands.any { it.contains("barbell") }
        val isCandCable = normCands.any { it.contains("cable") }
        val isCandBodyWeight = normCands.any { it.contains("body weight") }

        val isPtDumbbell = aliases.contains("dumbbell")
        val isPtBarbell = aliases.contains("barbell") || aliases.contains("olympic barbell")
        val isPtCable = aliases.contains("cable")
        val isPtBodyWeight = aliases.contains("body weight")

        val distinctIncompatible = (isPtDumbbell && isCandBarbell) ||
                (isPtBarbell && isCandDumbbell) ||
                (isPtBodyWeight && (isCandBarbell || isCandDumbbell)) ||
                (isPtCable && (isCandBarbell || isCandDumbbell))

        return if (distinctIncompatible) -10 else 0
    }

    /**
     * Evaluates muscle compatibility:
     * +20 if compatible alias matched
     * 0 if empty or unknown
     * -10 if confirmed incompatible (e.g. chest vs calves)
     */
    fun evaluateMuscleScore(ptMuscle: String?, candMusclesAndParts: List<String>): Int {
        val normPt = normalize(ptMuscle)
        if (normPt.isEmpty() || candMusclesAndParts.isEmpty()) return 0

        val normCands = candMusclesAndParts.map { normalize(it) }.filter { it.isNotEmpty() }
        if (normCands.isEmpty()) return 0

        val aliases = getMuscleAliases(ptMuscle)
        val isCompatible = normCands.any { cand ->
            aliases.any { alias -> cand.contains(alias) || alias.contains(cand) } ||
            cand.contains(normPt) || normPt.contains(cand)
        }

        if (isCompatible) return 20

        // Incompatibility check: major opposing anatomical regions
        val isUpperCand = normCands.any { it in setOf("chest", "pectorals", "lats", "back", "upper back", "biceps", "triceps", "delts", "shoulders", "traps") }
        val isLowerCand = normCands.any { it in setOf("quads", "quadriceps", "hamstrings", "glutes", "calves", "lower legs", "upper legs") }

        val isUpperPt = aliases.any { it in setOf("chest", "pectorals", "lats", "back", "upper back", "biceps", "triceps", "delts", "shoulders", "traps") }
        val isLowerPt = aliases.any { it in setOf("quads", "quadriceps", "hamstrings", "glutes", "calves", "lower legs", "upper legs") }

        val distinctIncompatible = (isUpperPt && isLowerCand && !isUpperCand) ||
                (isLowerPt && isUpperCand && !isLowerCand)

        return if (distinctIncompatible) -10 else 0
    }
}
