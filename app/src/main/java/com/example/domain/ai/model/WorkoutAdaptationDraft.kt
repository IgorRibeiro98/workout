package com.example.domain.ai.model

/**
 * O que o Coach pode propor mudar em um treino existente.
 *
 * Conjunto pequeno e explícito: cada tipo corresponde a um campo que
 * `WorkoutTemplateExerciseEntity` realmente guarda. Nada aqui existe sem suporte no domínio.
 */
enum class WorkoutAdaptationType(val label: String) {
    ADJUST_LOAD("Ajustar carga"),
    ADJUST_REPS("Ajustar repetições"),
    ADJUST_SETS("Ajustar séries"),
    ADJUST_REST("Ajustar descanso"),
    REPLACE_EXERCISE("Substituir exercício");

    /**
     * Se o tipo depende de desempenho registrado para fazer sentido.
     *
     * Sem sessão concluída não há progressão a inferir: com evidência `INSUFFICIENT` o app nem
     * oferece esses tipos ao modelo.
     */
    val requiresPerformanceEvidence: Boolean
        get() = this == ADJUST_LOAD || this == ADJUST_REPS || this == ADJUST_SETS
}

/**
 * O valor de um campo do treino, antes ou depois.
 *
 * Existe para a UI conseguir mostrar ANTES → DEPOIS sem interpretar strings, e para a aplicação
 * comparar o valor atual do template com o que o modelo alegou.
 */
sealed interface WorkoutAdaptationValue {
    /** `null` quando o treino não tem carga planejada — ausência é um valor legítimo. */
    data class Load(val weightKg: Float?) : WorkoutAdaptationValue
    data class Reps(val minReps: Int, val maxReps: Int) : WorkoutAdaptationValue
    data class Sets(val sets: Int) : WorkoutAdaptationValue
    data class Rest(val restSeconds: Int) : WorkoutAdaptationValue

    /** A identidade continua sendo [exerciseId]; [name] é leitura. */
    data class Exercise(val exerciseId: String, val name: String) : WorkoutAdaptationValue
}

/**
 * Uma mudança proposta, independente e identificável.
 *
 * [id] é determinístico (`TIPO:exerciseId`) para a seleção do usuário sobreviver a recomposição
 * sem depender de posição na lista. Toda mudança carrega [reason] e [evidence]: o usuário precisa
 * ver de onde a sugestão saiu antes de aceitá-la.
 */
data class WorkoutAdaptationChange(
    val id: String,
    val type: WorkoutAdaptationType,
    /** O exercício do template que a mudança altera. */
    val exerciseId: String,
    val exerciseName: String,
    val currentValue: WorkoutAdaptationValue,
    val suggestedValue: WorkoutAdaptationValue,
    val reason: String,
    val evidence: String,
    val confidence: Double
) {
    companion object {
        /** Um tipo de mudança por exercício: o id é a própria regra de unicidade. */
        fun idOf(type: WorkoutAdaptationType, exerciseId: String): String = "${type.name}:$exerciseId"
    }
}

/**
 * Uma proposta de adaptação, já validada e ainda não aplicada.
 *
 * Não é `@Entity`: não tem tabela, não tem migration e não é salva. Ela vive entre a resposta do
 * modelo e a confirmação do usuário.
 *
 * [sourceRevision] é a impressão determinística do template no instante em que a proposta foi
 * montada. Se o template mudar antes da aplicação, a proposta é considerada obsoleta em vez de
 * sobrescrever a edição mais nova.
 */
data class WorkoutAdaptationDraft(
    val requestId: String,
    val templateId: Long,
    val templateName: String,
    val sourceRevision: String,
    val summary: String,
    val dataQuality: AiCoachDataQuality,
    val changes: List<WorkoutAdaptationChange>
) {
    /** A mudança com este id, ou `null` quando ela não existe mais nesta proposta. */
    fun change(changeId: String): WorkoutAdaptationChange? = changes.firstOrNull { it.id == changeId }
}

/** O que uma solicitação de adaptação produziu. */
sealed interface AdaptWorkoutResult {
    data class Success(val draft: WorkoutAdaptationDraft) : AdaptWorkoutResult

    /** O Coach analisou e não viu motivo para mudar nada. É resposta válida, não erro. */
    data class NoChanges(val summary: String, val dataQuality: AiCoachDataQuality) : AdaptWorkoutResult

    data class Failure(
        val kind: AiCoachErrorKind,
        val detail: String? = null
    ) : AdaptWorkoutResult
}

/** O que a confirmação do usuário produziu. */
sealed interface ApplyWorkoutAdaptationResult {
    data class Applied(val templateId: Long, val appliedChanges: Int) : ApplyWorkoutAdaptationResult

    /** Nenhuma mudança selecionada: nada é escrito, nem um update vazio. */
    data object NothingSelected : ApplyWorkoutAdaptationResult

    /** O treino mudou depois que a proposta foi montada. Nada é aplicado. */
    data object StaleDraft : ApplyWorkoutAdaptationResult

    data class Failure(val reason: String) : ApplyWorkoutAdaptationResult
}
