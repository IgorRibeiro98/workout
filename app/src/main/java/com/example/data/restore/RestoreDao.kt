package com.example.data.restore

import androidx.room.Dao
import androidx.room.Query

/**
 * As consultas que **só** o restore usa (T16.5).
 *
 * ## Por que um DAO separado
 *
 * As instruções aqui apagam o dataset pessoal inteiro. Elas não podem morar no `WorkoutDao`, ao
 * lado de `insertTemplate` e `getSessionById`, onde alguém as encontraria procurando outra coisa —
 * um `deleteAllWorkoutSessions()` disponível no DAO de uso diário é um acidente esperando o dia
 * em que alguém precise "limpar para testar".
 *
 * Elas são chamadas de **um** lugar: [RestoreTransaction], dentro de uma transação, depois de
 * download, hash, validação, preview e confirmação explícita.
 *
 * ## O que este DAO deliberadamente não apaga
 *
 * ```text
 * exercises com canonicalId        catálogo — vem do manifesto versionado, não do usuário
 * exercise_education/media/...     enriquecimento premium — idem
 * exercise_alternatives            conteúdo do catálogo (o único escritor é o ManifestImporter)
 * backup_attempts / restore_attempts / cloud_data_binding
 *                                  mecanismo interno; o vínculo é escrito pela própria transação
 * ```
 *
 * Apagar catálogo transformaria um restore em uma reinstalação: o usuário perderia os exercícios do
 * app até o próximo import, e as referências do próprio backup deixariam de resolver.
 */
@Dao
interface RestoreDao {

    // ---- Limpeza do dataset pessoal ---------------------------------------------------------
    //
    // A ordem das chamadas é responsabilidade de quem apaga (filhos antes de pais). As instruções
    // são separadas em vez de um `deleteEverything()` porque cada uma nomeia exatamente o que
    // remove — e porque a transação que as usa precisa poder ser lida de cima a baixo.

    @Query("DELETE FROM set_logs")
    suspend fun deleteAllSetLogs()

    @Query("DELETE FROM exercise_sessions")
    suspend fun deleteAllExerciseSessions()

    @Query("DELETE FROM workout_sessions")
    suspend fun deleteAllWorkoutSessions()

    @Query("DELETE FROM workout_template_exercises")
    suspend fun deleteAllTemplateExercises()

    @Query("DELETE FROM workout_templates")
    suspend fun deleteAllTemplates()

    @Query("DELETE FROM workout_programs")
    suspend fun deleteAllPrograms()

    @Query("DELETE FROM check_ins")
    suspend fun deleteAllCheckIns()

    @Query("DELETE FROM body_measurements")
    suspend fun deleteAllBodyMeasurements()

    @Query("DELETE FROM weekly_goal_history")
    suspend fun deleteAllWeeklyGoals()

    @Query("DELETE FROM exercise_user_overrides")
    suspend fun deleteAllExerciseOverrides()

    /**
     * Só os exercícios **criados pelo usuário**.
     *
     * O catálogo canônico continua onde está: ele é conteúdo do app, identificado por
     * `canonicalId`, e é a ele que as referências restauradas apontam.
     */
    @Query("DELETE FROM exercises WHERE isUserCreated = 1")
    suspend fun deleteAllCustomExercises()

    // ---- Derivados (matriz de dados, Grupo B) ------------------------------------------------
    //
    // XP, conquistas, recordes e eventos são **calculados a partir do histórico**, não copiados: a
    // matriz decidiu isso na T16.0 e a T16.4 confirmou ao deixá-los fora do backup. Depois de
    // substituir o histórico, o que sobrasse aqui descreveria sessões que não existem mais — e
    // pior, as `dedupeKey` desses eventos citam `localId` de sessões que o restore regenerou.
    //
    // Então eles são limpos junto, e as reconciliações que já rodam na abertura do app
    // (`XpReconciler`, `AchievementReconciler`, `MissionReconciler`) os reconstroem pelas regras
    // vigentes. Nada é premiado por isso: reconciliar é recalcular, não comemorar.

    @Query("DELETE FROM personal_records")
    suspend fun deleteAllPersonalRecords()

    @Query("DELETE FROM gamification_events")
    suspend fun deleteAllGamificationEvents()

    @Query("DELETE FROM xp_transactions")
    suspend fun deleteAllXpTransactions()

    @Query("DELETE FROM achievement_unlocks")
    suspend fun deleteAllAchievementUnlocks()

    // ---- Outbox ------------------------------------------------------------------------------

    /**
     * Zera a Outbox como **parte do commit** do restore.
     *
     * Depois de uma substituição bem-sucedida, o estado local é exatamente o snapshot que o
     * servidor já tem: não existe alteração posterior a propagar, e a fila precisa dizer isso. As
     * mutações que existiam antes descrevem um dataset que deixou de existir.
     *
     * Ela é chamada **dentro** da transação de aplicação, nunca antes: limpar a fila e falhar o
     * download depois seria perda de dado sem conserto.
     */
    @Query("DELETE FROM sync_outbox")
    suspend fun deleteAllOutboxEntries()

    // ---- Leituras de decisão -----------------------------------------------------------------

    /**
     * Sessões concluídas **posteriores** a um instante — o aviso de rollback temporal do preview.
     *
     * `finishedAt` quando existe, `startedAt` como âncora quando não: uma sessão concluída sem
     * `finishedAt` é dado antigo, e ignorá-la esconderia do usuário exatamente o que ele pode
     * perder.
     */
    @Query(
        """
        SELECT COUNT(*) FROM workout_sessions
        WHERE status = 'COMPLETED' AND COALESCE(finishedAt, startedAt) > :instant
        """
    )
    suspend fun countCompletedSessionsAfter(instant: Long): Int

    /**
     * Treinos vivos neste aparelho.
     *
     * Execução em andamento é dado **local** (matriz, Grupo C) e não vem no backup. Substituir o
     * dataset com um treino aberto destruiria a sessão que a pessoa está fazendo agora — então o
     * restore recusa começar, em vez de escolher por ela.
     */
    @Query("SELECT COUNT(*) FROM workout_sessions WHERE status IN ('IN_PROGRESS', 'PAUSED')")
    suspend fun countActiveSessions(): Int
}
