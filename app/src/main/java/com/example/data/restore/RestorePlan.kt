package com.example.data.restore

import com.example.data.backup.BackupMetadataDto
import com.example.data.backup.BackupSummary
import com.example.data.local.WorkoutDao
import com.example.data.sync.dto.ExerciseRefDto

/**
 * Um restore pronto para ser aplicado (T16.5).
 *
 * ## Por que existe uma estrutura no meio
 *
 * ```text
 * snapshot → validação → RestorePlan → preview
 *                              ↓
 *                        transação Room
 * ```
 *
 * O plano é o que separa **entender o backup** de **escrever no banco**. Sem ele, a transação
 * crítica teria que reparsear JSON, resolver referência de catálogo e contar item enquanto segura o
 * lock do banco — e uma falha no meio disso aconteceria já com o dataset antigo apagado.
 *
 * Aqui dentro já está tudo resolvido: agregados tipados, referências de exercício traduzidas para o
 * catálogo **deste** aparelho, contagens e avisos. A transação vira o que ela deve ser: escrever.
 *
 * O preview também sai daqui, e não do JSON cru: mostrar "186 sessões" a partir de um número
 * declarado na metadata seria mostrar o que o servidor diz, não o que o snapshot contém.
 */
data class RestorePlan(

    /** O backup escolhido, na metadata **do servidor**. */
    val backup: BackupMetadataDto,

    /** A conta que será dona do dataset resultante. */
    val ownerUid: String,

    /** O conteúdo validado. */
    val snapshot: ValidatedRestoreSnapshot,

    /**
     * As referências de exercício de catálogo já traduzidas para `localId` **deste** aparelho.
     *
     * Resolvidas na construção do plano, e não durante a transação: se faltar alguma, o restore é
     * recusado **antes** de qualquer escrita, com [RestoreError.MISSING_CATALOG_EXERCISE].
     */
    val canonicalExerciseIds: Map<String, Long>,

    /** O que existe no snapshot — os números que o preview mostra. */
    val counts: RestoreCounts,

    /** O que existe hoje no aparelho — o outro lado da comparação do preview. */
    val localCounts: BackupSummary,

    /** O que o usuário precisa saber antes de confirmar. */
    val warnings: List<RestoreWarning>
) {

    /** `true` quando o aparelho tem dado pessoal que a substituição vai descartar. */
    val replacesLocalData: Boolean
        get() = localCounts.total > 0
}

/**
 * As contagens do snapshot validado.
 *
 * Contagem, nunca conteúdo: o preview precisa dizer **quanto** será restaurado, e não listar o que
 * a pessoa treina. É a mesma regra da confirmação de adoção da T16.4.
 */
data class RestoreCounts(
    val programs: Int,
    val templates: Int,
    val completedSessions: Int,
    val customExercises: Int,
    val bodyMeasurements: Int,
    val checkIns: Int,
    val exerciseOverrides: Int,
    val weeklyGoals: Int,
    val includesPreferences: Boolean
) {
    val total: Int
        get() = programs + templates + completedSessions + customExercises +
            bodyMeasurements + checkIns + exerciseOverrides + weeklyGoals +
            (if (includesPreferences) 1 else 0)
}

/**
 * Um aviso que o preview mostra antes da confirmação.
 *
 * Eles existem porque restore é **substituição**, e uma substituição silenciosa é a forma mais
 * fácil de destruir dado com um botão de aparência inofensiva.
 */
sealed interface RestoreWarning {

    /** O aparelho tem dado pessoal, e ele será substituído. */
    data class ReplacesLocalData(val localItems: Int) : RestoreWarning

    /**
     * O backup é mais antigo que o histórico local: sessões criadas depois vão desaparecer.
     *
     * [localSessionsAfterBackup] é quantas sessões concluídas locais são posteriores ao instante em
     * que o backup foi criado. Não é uma estimativa amigável: é o número que o usuário precisa para
     * decidir.
     */
    data class OlderThanLocalHistory(
        val backupCreatedAt: Long,
        val localSessionsAfterBackup: Int
    ) : RestoreWarning

    /** Havia alterações locais ainda não cobertas por backup; a substituição as descarta. */
    data class PendingChangesDiscarded(val pendingMutations: Int) : RestoreWarning

    /**
     * Gamificação será recalculada a partir do histórico restaurado.
     *
     * XP, conquistas, recordes e streak são **derivados** (matriz de dados, Grupo B) e não viajam
     * no backup. Depois do restore eles voltam a ser calculados pelas mesmas regras de sempre,
     * sobre o histórico que passou a existir — nada é premiado de novo.
     */
    data object GamificationRecalculated : RestoreWarning

    /** Fotos personalizadas de exercício são arquivos deste aparelho e não vêm no backup. */
    data object MediaStaysLocal : RestoreWarning
}

/**
 * Monta o [RestorePlan] a partir de um snapshot validado (T16.5).
 *
 * ## O que ele resolve, e por que aqui
 *
 * Um backup pode citar um exercício de catálogo (`canonicalId`) que **este** aparelho não tem —
 * catálogo desatualizado, manifesto ainda importando. Resolver isso agora tem uma consequência
 * concreta: a recusa acontece no preview, com o banco intacto, e não no meio da transação com o
 * dataset antigo já apagado.
 *
 * E a recusa é uma recusa: não existe "usa o exercício mais parecido". O Spark não adivinha qual
 * agachamento o usuário fez em 2024.
 *
 * Referência de exercício **dentro de sessão concluída** é a exceção documentada: ela pode não
 * resolver, e a sessão continua íntegra porque carrega `exerciseNameSnapshot`. É o contrato
 * (`contracts/backup/v1/README.md` §6), e é o que faz um histórico sobreviver ao exercício que o
 * originou.
 */
class RestorePlanBuilder(
    private val workoutDao: WorkoutDao
) {

    suspend fun build(
        backup: BackupMetadataDto,
        ownerUid: String,
        snapshot: ValidatedRestoreSnapshot,
        localSummary: BackupSummary,
        localSessionsAfterBackup: Int,
        pendingMutations: Int
    ): RestorePlan {
        val canonicalIds = resolveCanonicalReferences(snapshot)

        val counts = RestoreCounts(
            programs = snapshot.programs.size,
            templates = snapshot.templates.size,
            completedSessions = snapshot.sessions.size,
            customExercises = snapshot.customExercises.size,
            bodyMeasurements = snapshot.measurements.size,
            checkIns = snapshot.checkIns.size,
            exerciseOverrides = snapshot.overrides.size,
            weeklyGoals = snapshot.weeklyGoals.size,
            includesPreferences = snapshot.preferences != null
        )

        val warnings = buildList {
            if (localSummary.total > 0) add(RestoreWarning.ReplacesLocalData(localSummary.total))
            if (localSessionsAfterBackup > 0) {
                add(
                    RestoreWarning.OlderThanLocalHistory(
                        backupCreatedAt = backup.createdAt,
                        localSessionsAfterBackup = localSessionsAfterBackup
                    )
                )
            }
            if (pendingMutations > 0) add(RestoreWarning.PendingChangesDiscarded(pendingMutations))
            add(RestoreWarning.GamificationRecalculated)
            add(RestoreWarning.MediaStaysLocal)
        }

        return RestorePlan(
            backup = backup,
            ownerUid = ownerUid,
            snapshot = snapshot,
            canonicalExerciseIds = canonicalIds,
            counts = counts,
            localCounts = localSummary,
            warnings = warnings
        )
    }

    /**
     * Traduz todo `canonicalId` que o restore **precisa** materializar em uma linha local.
     *
     * Precisam resolver: exercícios de treino (`workout_template_exercises.exerciseId` é chave
     * estrangeira obrigatória) e customizações (`exercise_user_overrides.exerciseId` é a própria
     * chave primária). Não precisam: referências dentro de sessão concluída.
     */
    private suspend fun resolveCanonicalReferences(
        snapshot: ValidatedRestoreSnapshot
    ): Map<String, Long> {
        val required = buildSet {
            snapshot.templates.forEach { template ->
                template.exercises.forEach { entry ->
                    if (entry.exercise.kind == ExerciseRefDto.CANONICAL) add(entry.exercise.id)
                }
            }
            snapshot.overrides.forEach { override ->
                if (override.exercise.kind == ExerciseRefDto.CANONICAL) add(override.exercise.id)
            }
        }

        val resolved = mutableMapOf<String, Long>()
        val missing = mutableListOf<String>()
        required.forEach { canonicalId ->
            val exercise = workoutDao.getExerciseByCanonicalId(canonicalId)
            if (exercise == null) missing += canonicalId else resolved[canonicalId] = exercise.id
        }

        if (missing.isNotEmpty()) {
            // A razão diz **quantos** faltam, não quais: um `canonicalId` é conteúdo do catálogo e
            // não ajuda o usuário, e a mensagem acaba em tela.
            throw RestoreException(
                RestoreError.MISSING_CATALOG_EXERCISE,
                "${missing.size} exercício(s) de catálogo ausente(s) neste aparelho"
            )
        }
        return resolved
    }
}
