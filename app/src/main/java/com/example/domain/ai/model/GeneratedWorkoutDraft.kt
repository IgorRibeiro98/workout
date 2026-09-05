package com.example.domain.ai.model

/**
 * Um treino **proposto**, já validado, e ainda não persistido.
 *
 * Deliberadamente não é `@Entity`: não tem tabela, não tem migration e não é salvo
 * automaticamente. Ele existe entre a resposta do modelo e a confirmação do usuário; se o
 * usuário descartar ou sair, ele simplesmente deixa de existir.
 *
 * A identidade de cada exercício continua sendo o `exerciseId` canônico que o app enviou; o
 * nome vem do catálogo do app, nunca do texto do modelo.
 */
data class GeneratedWorkoutDraft(
    val requestId: String,
    val name: String,
    val explanation: String,
    val exercises: List<GeneratedWorkoutDraftExercise>
)

/**
 * Um exercício do rascunho, no formato que o domínio realmente suporta.
 *
 * Os campos espelham `WorkoutTemplateExerciseEntity`: o Spark trabalha com faixa de repetições
 * (`minReps`..`maxReps`), descanso em segundos e carga planejada opcional. Nenhum campo foi
 * criado só para acomodar a IA.
 */
data class GeneratedWorkoutDraftExercise(
    val exerciseId: String,
    /** Nome resolvido pelo catálogo do app a partir de [exerciseId]. */
    val name: String,
    val sortOrder: Int,
    val sets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restSeconds: Int,
    /** `null` quando não há carga registrada: sem evidência, o app não inventa peso. */
    val weightKg: Float? = null,
    val reason: String
)

/** O que uma geração produziu para a apresentação. */
sealed interface GenerateWorkoutResult {
    data class Success(val draft: GeneratedWorkoutDraft) : GenerateWorkoutResult

    /**
     * O catálogo filtrado não sustenta o pedido.
     *
     * Quando é o app que descobre isso, nenhuma chamada ao provider acontece.
     */
    data object InsufficientCandidates : GenerateWorkoutResult

    data class Failure(
        val kind: AiCoachErrorKind,
        val detail: String? = null
    ) : GenerateWorkoutResult
}

/** O que a confirmação explícita do usuário produziu. */
sealed interface SaveGeneratedWorkoutResult {
    data class Saved(val templateId: Long, val name: String) : SaveGeneratedWorkoutResult
    data class Failure(val reason: String) : SaveGeneratedWorkoutResult
}
