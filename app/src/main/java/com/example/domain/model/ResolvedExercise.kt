package com.example.domain.model

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.domain.engine.ResolvedMedia

enum class ExerciseExecutionMode {
    REPS,
    DURATION
}

data class ResolvedExercise(
    val id: Long,
    val canonicalId: String?,
    val slug: String?,
    val displayName: String,
    val nameEn: String?,
    val primaryMuscle: String?,
    val secondaryMuscles: List<String>,
    val equipment: String?,
    val movementPattern: String?,
    val substitutionGroup: String?,
    val notes: String?,
    val resolvedMedia: ResolvedMedia,
    val defaultRestSeconds: Int?,
    val isUserCreated: Boolean,
    val isCustomPhoto: Boolean,
    val rawExercise: ExerciseEntity,
    val override: ExerciseUserOverrideEntity? = null
) {
    val executionMode: ExerciseExecutionMode
        get() {
            val name = displayName.lowercase()
            val movement = movementPattern?.lowercase() ?: ""
            val rawCategory = rawExercise.category?.lowercase() ?: ""
            val notesStr = (notes ?: "").lowercase()
            return if (
                name.contains("prancha") ||
                name.contains("plank") ||
                name.contains("isometria") ||
                name.contains("isométrico") ||
                name.contains("isometric") ||
                name.contains("suspens") ||
                name.contains("hang") ||
                name.contains("wall sit") ||
                name.contains("esteira") ||
                name.contains("bicicleta") ||
                name.contains("cardio") ||
                movement.contains("isometric") ||
                rawCategory.contains("cardio") ||
                notesStr.contains("isometria")
            ) {
                ExerciseExecutionMode.DURATION
            } else {
                ExerciseExecutionMode.REPS
            }
        }

    /**
     * O exercício é executado com o peso do próprio corpo.
     *
     * A regra **era** escrita dentro de `ExecutionScreen`, num `val isBodyweight` de nove linhas
     * com substrings de nome (auditoria 2026-09-12) — uma regra de domínio morando na composição,
     * que o `AllSetsBottomSheet` da mesma tela já contradizia por usar outra heurística.
     *
     * Ela continua sendo uma heurística, e isso é deliberado: o catálogo em `assets` **não traz**
     * o campo `isBodyweight` (nenhuma das 406 entradas o declara), então confiar só em
     * `rawExercise.isBodyweight` faria 21 exercícios — flexões, barras fixas, paralelas — voltarem
     * a pedir carga. Enquanto o catálogo não declarar o campo, a heurística é a melhor resposta
     * disponível; o que muda é que agora existe **uma** cópia dela, do lado do domínio, ao lado de
     * [executionMode], que segue exatamente o mesmo desenho.
     */
    val isBodyweight: Boolean
        get() {
            if (rawExercise.isBodyweight) return true
            val equipmentText = (equipment ?: rawExercise.equipment)?.lowercase() ?: ""
            if (equipmentText.contains("body") || equipmentText.contains("corporal")) return true
            return isBodyweightName(displayName)
        }

    companion object {

        /**
         * A parte da regra de [isBodyweight] que depende só do nome.
         *
         * Existe separada porque a tela de execução precisa responder a mesma pergunta quando o
         * exercício **não** pôde ser resolvido (linha do catálogo ausente ou inativa) e tudo o que
         * resta é o `exerciseNameSnapshot` da sessão. Sem isto, aquele caso voltaria a pedir carga
         * numa flexão — ou teria uma segunda cópia da lista de nomes na camada de UI.
         */
        fun isBodyweightName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("flexão") ||
                lower.contains("barra fixa") ||
                lower.contains("paralelas") ||
                lower.contains("abdominal") ||
                lower.contains("prancha") ||
                lower.contains("peso corporal")
        }
    }
}
