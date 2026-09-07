package com.example.data.restore

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.SessionStatus
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A ida e a volta: aparelho A → backup → servidor → aparelho B (T16.5).
 *
 * ```text
 * Dataset A ──BackupSnapshotBuilder──▶ snapshot ──▶ servidor ──▶ download ──▶ restore ──▶ Dataset B
 *
 *                          Dataset A  ≡  Dataset B   (semanticamente)
 * ```
 *
 * Este é o teste mais importante da tarefa, e a comparação é deliberadamente **semântica**: o que
 * precisa ser igual é a identidade portátil, o conteúdo, a ordem e as relações. O que **não** pode
 * ser exigido é `localId` — o aparelho B numera as linhas dele como quiser, e depender disso seria
 * exatamente o defeito que o `syncId` existe para evitar.
 *
 * O banco B nasce com o catálogo canônico (que vem do manifesto, como em qualquer instalação) e
 * **nada** de pessoal — é uma instalação limpa.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class RestoreRoundTripTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase
    private lateinit var sourceHarness: RestoreHarness
    private lateinit var targetHarness: RestoreHarness

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val uid = "uid-da-conta-a"

    @Before
    fun setUp() = runTest {
        source = RestoreHarness.database(context)
        target = RestoreHarness.database(context)
        sourceHarness = RestoreHarness(
            database = source,
            context = context,
            filesRoot = File(folder.newFolder("origem"), "restore")
        )
        targetHarness = RestoreHarness(
            database = target,
            context = context,
            filesRoot = File(folder.newFolder("destino"), "restore")
        )

        RestoreDatasetFixture.seedCatalog(source)
        RestoreDatasetFixture.seedPersonalData(source)
        // O aparelho novo tem o catálogo do app e nada mais.
        RestoreDatasetFixture.seedCatalog(target)
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    // ------------------------------------------------------------------- o caminho feliz

    @Test
    fun `aparelho novo restaura o backup e fica semanticamente igual ao de origem`() = runTest {
        val expected = sourceHarness.semanticFingerprint()
        val backup = publishSourceSnapshot()

        val preparation = targetHarness.repository.prepare(backup, uid)
        assertTrue("o snapshot precisa ser válido", preparation is RestorePreparation.Ready)
        val ready = preparation as RestorePreparation.Ready

        val outcome = targetHarness.repository.confirm(
            plan = ready.plan,
            restoreAttemptId = ready.restoreAttemptId,
            currentUid = uid,
            confirmed = true
        )

        assertTrue("o restore precisa concluir", outcome is RestoreOutcome.Success)
        assertEquals(
            "o dataset restaurado precisa ser semanticamente idêntico ao de origem",
            expected,
            targetHarness.semanticFingerprint()
        )
    }

    @Test
    fun `o preview mostra as contagens do snapshot, e elas batem com a origem`() = runTest {
        val backup = publishSourceSnapshot()

        val ready = prepare(backup)

        assertEquals(1, ready.plan.counts.programs)
        assertEquals(2, ready.plan.counts.templates)
        assertEquals(3, ready.plan.counts.completedSessions)
        assertEquals(1, ready.plan.counts.customExercises)
        assertEquals(1, ready.plan.counts.bodyMeasurements)
        assertEquals(1, ready.plan.counts.checkIns)
        assertEquals(1, ready.plan.counts.exerciseOverrides)
        assertEquals(1, ready.plan.counts.weeklyGoals)
        assertTrue(ready.plan.counts.includesPreferences)
        // Aparelho limpo: não há dado local a substituir.
        assertTrue("o aparelho novo não tem dado pessoal", !ready.plan.replacesLocalData)
    }

    // ------------------------------------------------------------------- estrutura restaurada

    @Test
    fun `o treino complexo volta com a ordem e a referencia ao exercicio personalizado`() = runTest {
        restoreSourceIntoTarget()

        val template = target.workoutDao().getTemplateBySyncId(RestoreDatasetFixture.TEMPLATE_A_SYNC_ID)
        assertNotNull(template)
        val exercises = target.workoutDao().getTemplateExercisesWithDetails(template!!.id)

        assertEquals("três exercícios no treino A", 3, exercises.size)
        assertEquals(
            "a ordem persistida é a do snapshot, não a de inserção",
            listOf("Supino reto", "Rosca martelo no banco inclinado", "Remada curvada"),
            exercises.map { it.exercise.name }
        )
        assertEquals(listOf(0, 1, 2), exercises.map { it.templateExercise.sortOrder })

        val custom = exercises[1].exercise
        assertEquals(
            "o exercício personalizado é reconhecido pela identidade portátil",
            RestoreDatasetFixture.CUSTOM_EXERCISE_SYNC_ID,
            custom.syncId
        )
        assertTrue("ele continua sendo do usuário", custom.isUserCreated)

        val supino = exercises[0].exercise
        assertEquals(
            "o exercício de catálogo é resolvido pelo canonicalId local",
            RestoreDatasetFixture.CANONICAL_SUPINO,
            supino.canonicalId
        )
        assertEquals(62.5f, exercises[0].templateExercise.plannedWeight)
        assertEquals("Banco 3", exercises[0].templateExercise.machineLabel)
    }

    @Test
    fun `o historico complexo volta fiel, serie por serie`() = runTest {
        restoreSourceIntoTarget()

        val session = target.workoutDao()
            .getSessionWithDetailsBySyncId(RestoreDatasetFixture.SESSION_1_SYNC_ID)
        assertNotNull(session)
        val details = session!!

        assertEquals(SessionStatus.COMPLETED.name, details.session.status)
        assertEquals(1_690_000_000_000L, details.session.startedAt)
        assertEquals(1_690_003_600_000L, details.session.finishedAt)
        assertEquals("Treino puxado", details.session.notes)
        assertEquals("Treino A", details.session.templateNameSnapshot)

        val first = details.sortedExercises[0]
        assertEquals("Supino reto", first.exerciseSession.exerciseNameSnapshot)
        assertEquals(90, first.exerciseSession.restDurationSecondsSnapshot)

        val sets = first.sets.sortedBy { it.setNumber }
        assertEquals(3, sets.size)
        assertEquals(60f, sets[0].weight)
        assertEquals(10, sets[0].repetitions)
        assertEquals(2, sets[0].rir)
        assertEquals(62.5f, sets[1].weight)
        assertEquals(8.5f, sets[1].rpe)
        assertEquals(42, sets[1].durationSeconds)
        // A série não concluída volta não concluída: histórico não é "arrumado" no caminho.
        assertEquals(false, sets[2].completed)

        val second = details.sortedExercises[1]
        assertEquals("Aparelho ocupado", second.exerciseSession.replacementReason)
        assertEquals(
            "o exercício executado é diferente do planejado, e os dois voltam",
            target.workoutDao().getExerciseByCanonicalId(RestoreDatasetFixture.CANONICAL_REMADA)!!.id,
            second.exerciseSession.actualExerciseId
        )
        assertEquals(
            target.workoutDao().getExerciseBySyncId(RestoreDatasetFixture.CUSTOM_EXERCISE_SYNC_ID)!!.id,
            second.exerciseSession.plannedExerciseId
        )
        assertEquals("WARMUP", second.sets.sortedBy { it.setNumber }[0].type)
    }

    @Test
    fun `sessao sem template e sem exercicio resolvido continua integra`() = runTest {
        restoreSourceIntoTarget()

        val session = target.workoutDao()
            .getSessionWithDetailsBySyncId(RestoreDatasetFixture.SESSION_3_SYNC_ID)
        assertNotNull(session)
        assertNull("ela não tem treino de origem", session!!.session.templateId)
        assertEquals(
            "o nome histórico é o que preserva o passado",
            "Alongamento",
            session.sortedExercises[0].exerciseSession.exerciseNameSnapshot
        )
        assertNull(session.sortedExercises[0].exerciseSession.actualExerciseId)
    }

    @Test
    fun `check-in volta ligado a sessao certa por identidade portatil`() = runTest {
        restoreSourceIntoTarget()

        val checkIn = target.workoutDao().getCheckInBySyncId(RestoreDatasetFixture.CHECK_IN_SYNC_ID)
        val session = target.workoutDao()
            .getSessionWithDetailsBySyncId(RestoreDatasetFixture.SESSION_1_SYNC_ID)

        assertNotNull(checkIn)
        assertEquals(
            "a relação é reconstruída pelo syncId, não pelo localId de origem",
            session!!.session.id,
            checkIn!!.sessionId
        )
    }

    @Test
    fun `medidas, metas e customizacoes voltam`() = runTest {
        restoreSourceIntoTarget()

        val measurement = target.bodyMeasurementDao()
            .getMeasurementBySyncId(RestoreDatasetFixture.MEASUREMENT_SYNC_ID)
        assertNotNull(measurement)
        assertEquals(79.4f, measurement!!.weightKg)
        assertEquals(18.5f, measurement.bodyFatPercentage)

        assertEquals(1, target.weeklyGoalDao().getAllGoals().size)
        assertEquals(4, target.weeklyGoalDao().getAllGoals().first().goal)

        val supinoId = target.workoutDao()
            .getExerciseByCanonicalId(RestoreDatasetFixture.CANONICAL_SUPINO)!!.id
        val override = target.workoutDao().getOverrideForExercise(supinoId)
        assertNotNull(override)
        assertEquals("Supino reto (barra olímpica)", override!!.displayName)
        assertEquals(75, override.defaultRestSeconds)
        // Mídia local não atravessa: `content://` não resolve em outro aparelho.
        assertNull("a foto do aparelho de origem não é restaurada", override.customPhotoUri)
    }

    // ------------------------------------------------------------------- identidade

    @Test
    fun `syncId e preservado e localId e novo`() = runTest {
        val sourceTemplate = source.workoutDao()
            .getTemplateBySyncId(RestoreDatasetFixture.TEMPLATE_A_SYNC_ID)!!
        // O aparelho de destino recebe ids próprios: o alvo começa com o catálogo já numerado, e
        // as linhas pessoais entram depois.
        restoreSourceIntoTarget()

        val restored = target.workoutDao()
            .getTemplateBySyncId(RestoreDatasetFixture.TEMPLATE_A_SYNC_ID)!!

        assertEquals(
            "a identidade portátil é a mesma",
            sourceTemplate.syncId,
            restored.syncId
        )
        assertTrue("e o banco de destino numera como quiser", restored.id > 0)

        val sourceSession = source.workoutDao()
            .getSessionWithDetailsBySyncId(RestoreDatasetFixture.SESSION_1_SYNC_ID)!!
        val restoredSession = target.workoutDao()
            .getSessionWithDetailsBySyncId(RestoreDatasetFixture.SESSION_1_SYNC_ID)!!
        assertEquals(sourceSession.session.syncId, restoredSession.session.syncId)
        assertEquals(
            "os exercícios da sessão apontam para a sessão local nova",
            restoredSession.session.id,
            restoredSession.sortedExercises.first().exerciseSession.sessionId
        )
    }

    @Test
    fun `o catalogo canonico do aparelho nao e apagado nem duplicado`() = runTest {
        val before = target.workoutDao().getAllExercisesSync()
            .filter { !it.isUserCreated }
            .mapNotNull { it.canonicalId }
            .sorted()

        restoreSourceIntoTarget()

        val after = target.workoutDao().getAllExercisesSync()
            .filter { !it.isUserCreated }
            .mapNotNull { it.canonicalId }
            .sorted()

        assertEquals("o catálogo é conteúdo do app e não muda com o restore", before, after)
        assertEquals(
            "e ele não ganha cópias",
            after.size,
            after.toSet().size
        )
    }

    // ------------------------------------------------------------------- reaplicação

    @Test
    fun `restaurar duas vezes o mesmo backup produz o mesmo dataset`() = runTest {
        val backup = publishSourceSnapshot()
        restore(backup)
        val first = targetHarness.semanticFingerprint()

        restore(backup)

        assertEquals(
            "o restore é uma substituição, não uma soma: aplicar duas vezes não duplica nada",
            first,
            targetHarness.semanticFingerprint()
        )
        assertEquals(3, target.workoutDao().getCompletedSessionSyncIds().size)
    }

    @Test
    fun `restore nao altera o aparelho de origem`() = runTest {
        val before = sourceHarness.semanticFingerprint()

        restoreSourceIntoTarget()

        assertEquals("o backup é leitura; a origem continua intacta", before, sourceHarness.semanticFingerprint())
    }

    @Test
    fun `o backup baixado e o mesmo contrato do backup enviado`() = runTest {
        val body = sourceHarness.canonicalSnapshotOfCurrentState()
        val backup = targetHarness.api.publish(body)

        val ready = prepare(backup)

        assertEquals(
            "o mesmo documento que o BackupSnapshotBuilder produziu é o que o leitor valida",
            backup.itemCount,
            ready.plan.snapshot.itemCount
        )
        assertNotEquals(0, ready.plan.snapshot.itemCount)
    }

    // ------------------------------------------------------------------- helpers

    private suspend fun publishSourceSnapshot() =
        targetHarness.api.publish(sourceHarness.canonicalSnapshotOfCurrentState())

    private suspend fun restoreSourceIntoTarget() {
        restore(publishSourceSnapshot())
    }

    private suspend fun restore(backup: com.example.data.backup.BackupMetadataDto) {
        val ready = prepare(backup)
        val outcome = targetHarness.repository.confirm(
            plan = ready.plan,
            restoreAttemptId = ready.restoreAttemptId,
            currentUid = uid,
            confirmed = true
        )
        assertTrue("o restore precisa concluir: $outcome", outcome is RestoreOutcome.Success)
    }

    private suspend fun prepare(
        backup: com.example.data.backup.BackupMetadataDto
    ): RestorePreparation.Ready {
        val preparation = targetHarness.repository.prepare(backup, uid)
        assertTrue("a preparação precisa dar certo: $preparation", preparation is RestorePreparation.Ready)
        return preparation as RestorePreparation.Ready
    }
}
