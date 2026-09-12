package com.example.domain.engine

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.data.local.SessionStatus
import com.example.data.local.SetType
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O motor de execução, exercitado de verdade (auditoria 2026-09-12).
 *
 * A auditoria encontrou que **nenhum teste instanciava `WorkoutEngine`**: `WorkoutExecutionFlowQATest`
 * e `ExecutionIntelligenceTest` reimplementam a lógica inline, então não podem falhar quando o
 * código de produção mudar. Este arquivo fecha esse buraco para os caminhos que a auditoria
 * apontou como defeituosos — e é por isso que ele usa banco real em vez de dublê: o que estava
 * errado eram transações, ordem de escrita e regras duplicadas, nada disso visível com um fake.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class WorkoutEngineExecutionTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var settings: SettingsManager
    private lateinit var engine: WorkoutEngine

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.workoutDao()
        settings = SettingsManager(context)
        // Os padrões de fábrica, explícitos: o DataStore é compartilhado entre testes do mesmo
        // processo, e um valor deixado por outro teste tornaria as asserções abaixo uma loteria.
        settings.setDefaultRestSeconds(90)
        settings.setDefaultExerciseRestSeconds(120)
        settings.setAutoRestTimerOnSet(true)
        settings.setRestTimerState(null)
        engine = WorkoutEngine(dao, settings)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedTemplate(
        exerciseName: String = "Supino reto",
        targetSets: Int = 2,
        restSeconds: Int = 45
    ): Pair<Long, Long> {
        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa"))
        val templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Treino A", shortIdentifier = "A")
        )
        val exerciseId = dao.insertExercise(ExerciseEntity(name = exerciseName))
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = templateId,
                exerciseId = exerciseId,
                targetSets = targetSets,
                restDurationSeconds = restSeconds
            )
        )
        return templateId to exerciseId
    }

    // ---------------------------------------------------------------------------------------
    // Regra única de descanso
    // ---------------------------------------------------------------------------------------

    @Test
    fun `descanso entre series usa o snapshot do exercicio quando existe`() = runBlocking {
        val recommendation = engine.resolveRestRecommendation(actualExerciseId = null, restDurationSecondsSnapshot = 45)
        assertEquals(45, recommendation.betweenSets)
    }

    @Test
    fun `sem snapshot o descanso entre series cai no override do exercicio`() = runBlocking {
        val exerciseId = dao.insertExercise(ExerciseEntity(name = "Remada"))
        dao.insertOrUpdateOverride(ExerciseUserOverrideEntity(exerciseId = exerciseId, defaultRestSeconds = 75))

        val recommendation = engine.resolveRestRecommendation(exerciseId, restDurationSecondsSnapshot = null)

        assertEquals(75, recommendation.betweenSets)
    }

    @Test
    fun `sem snapshot e sem override vale a preferencia do usuario`() = runBlocking {
        settings.setDefaultRestSeconds(65)
        val recommendation = engine.resolveRestRecommendation(actualExerciseId = null, restDurationSecondsSnapshot = null)
        assertEquals(65, recommendation.betweenSets)
    }

    @Test
    fun `descanso entre exercicios ignora o snapshot e segue a preferencia`() = runBlocking {
        settings.setDefaultExerciseRestSeconds(150)
        // O snapshot de 45 s é do exercício; ele não decide o intervalo **entre** exercícios. Era
        // exatamente aqui que a tela de transição discordava do motor.
        val recommendation = engine.resolveRestRecommendation(actualExerciseId = null, restDurationSecondsSnapshot = 45)
        assertEquals(150, recommendation.afterExercise)
    }

    // ---------------------------------------------------------------------------------------
    // Temporizador de descanso
    // ---------------------------------------------------------------------------------------

    @Test
    fun `estender descanso sem descanso em andamento nao inventa um`() = runBlocking {
        val result = engine.adjustRestTimer(30)

        assertEquals(WorkoutEngine.NO_ACTIVE_REST_TIMER, result)
        assertNull(engine.restTimerTarget.first())
        assertNull(settings.restTimerDeadlineFlow.first())
    }

    @Test
    fun `estender descanso em andamento empurra o alvo`() = runBlocking {
        engine.startRestTimer(durationSeconds = 60, workoutSessionId = 1L, exerciseSessionId = 1L)
        val before = engine.restTimerTarget.first()!!

        val after = engine.adjustRestTimer(30)

        assertTrue("o alvo precisa avançar ~30 s", after - before in 29_000..31_000)
    }

    // ---------------------------------------------------------------------------------------
    // Ciclo de vida da sessão
    // ---------------------------------------------------------------------------------------

    @Test
    fun `iniciar treino cria sessao com exercicios e series`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 3)

        engine.startSession(templateId)

        val session = dao.getActiveSession()
        assertNotNull(session)
        val details = dao.getSessionWithDetails(session!!.id)!!
        assertEquals(1, details.exercises.size)
        assertEquals(3, details.exercises.first().sets.size)
        assertEquals(SessionStatus.IN_PROGRESS.name, session.status)
    }

    @Test
    fun `dois inicios concorrentes criam uma unica sessao`() = runBlocking {
        val (templateId, _) = seedTemplate()

        // O defeito original era um check-then-act: as duas chamadas passavam pela verificação
        // "existe sessão ativa?" antes de qualquer inserção, e a segunda sessão ficava órfã —
        // invisível para o `LIMIT 1` de `getActiveSession`, e impossível de cancelar pela UI.
        listOf(
            async { engine.startSession(templateId) },
            async { engine.startSession(templateId) }
        ).awaitAll()

        val inProgress = database.query(
            "SELECT COUNT(*) FROM workout_sessions WHERE status = ?",
            arrayOf(SessionStatus.IN_PROGRESS.name)
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        assertEquals(1, inProgress)
    }

    // ---------------------------------------------------------------------------------------
    // Conclusão de série e temporizador automático
    // ---------------------------------------------------------------------------------------

    @Test
    fun `concluir serie no meio do exercicio inicia descanso entre series`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 2, restSeconds = 45)
        engine.startSession(templateId)
        val session = dao.getActiveSession()!!
        val sets = dao.getSessionWithDetails(session.id)!!.exercises.first().sets.sortedBy { it.setNumber }

        engine.updateSet(sets.first().copy(completed = true))

        val target = engine.restTimerTarget.first()
        assertNotNull("um descanso entre séries precisa começar", target)
        val seconds = ((target!! - System.currentTimeMillis()) / 1000).toInt()
        assertTrue("esperado ~45 s, veio $seconds", seconds in 40..46)
        assertEquals("REST_SET", settings.restTimerTypeFlow.first())
    }

    @Test
    fun `exercicio sem series continua pendente e o treino nao termina`() = runBlocking {
        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa"))
        val templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Treino A", shortIdentifier = "A")
        )
        listOf("Supino", "Remada").forEachIndexed { index, name ->
            val exerciseId = dao.insertExercise(ExerciseEntity(name = name))
            dao.insertTemplateExercise(
                WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exerciseId, sortOrder = index, targetSets = 1)
            )
        }
        engine.startSession(templateId)
        val session = dao.getActiveSession()!!
        val exercises = dao.getSessionWithDetails(session.id)!!.exercises.sortedBy { it.exerciseSession.executionOrder }

        // O usuário remove a única série do segundo exercício — ação disponível na tela — e
        // conclui a única série do primeiro. Um exercício em branco nunca contou como feito: a
        // versão em memória exigia `sets.isNotEmpty()`, e a contagem no banco precisa concordar.
        exercises[1].sets.forEach { engine.removeSet(it) }
        engine.updateSet(exercises[0].sets.first().copy(completed = true))

        assertNotNull("o treino não terminou: há um exercício pendente (vazio)", engine.restTimerTarget.first())
        assertEquals("REST_EXERCISE", settings.restTimerTypeFlow.first())
    }

    @Test
    fun `concluir o treino inteiro nao inicia descanso`() = runBlocking {
        val (templateId, _) = seedTemplate(targetSets = 2)
        engine.startSession(templateId)
        val session = dao.getActiveSession()!!
        val sets = dao.getSessionWithDetails(session.id)!!.exercises.first().sets.sortedBy { it.setNumber }

        sets.forEach { engine.updateSet(it.copy(completed = true)) }

        assertNull("o último set do treino não pode abrir descanso", engine.restTimerTarget.first())
    }

    // ---------------------------------------------------------------------------------------
    // Recordes pessoais: uma regra só, no fim do treino
    // ---------------------------------------------------------------------------------------

    @Test
    fun `recorde de carga ignora serie de aquecimento`() = runBlocking {
        val (templateId, exerciseId) = seedTemplate(targetSets = 2)
        engine.startSession(templateId)
        val session = dao.getActiveSession()!!
        val sets = dao.getSessionWithDetails(session.id)!!.exercises.first().sets.sortedBy { it.setNumber }

        // Aquecimento pesado e série de trabalho leve: a regra do fim do treino tem de preferir a
        // de trabalho. O caminho antigo, que gravava o recorde na conclusão de cada série, deixava
        // os 200 kg de aquecimento gravados para sempre.
        engine.updateSet(sets[0].copy(completed = true, weight = 200f, type = SetType.WARMUP.name))
        engine.updateSet(sets[1].copy(completed = true, weight = 60f, type = SetType.NORMAL.name))
        engine.finishSession(session.id)

        val record = dao.getHighestPR(exerciseId, com.example.data.local.PRType.MAX_WEIGHT.name)
        assertNotNull(record)
        assertEquals(60f, record!!.value, 0.01f)
    }
}
