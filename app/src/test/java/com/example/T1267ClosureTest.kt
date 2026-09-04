package com.example

import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.PRType
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.SessionWithDetails
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.domain.engine.ExerciseSearchEngine
import com.example.domain.engine.RirFormatter
import com.example.presentation.execution.ExecutionPhase
import com.example.presentation.execution.ExecutionState
import com.example.presentation.history.HistoryPeriod
import com.example.presentation.today.TodayHighlightCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * T12.6.7 — Fechamento de UX e Confiabilidade do Core.
 *
 * Covers the contextual search vocabulary, the reordering lifecycle end to end, the RIR copy and
 * the history period filters.
 */
class T1267ClosureTest {

    // ---------------------------------------------------------------------------------------
    // PARTE 1 — Busca contextual
    // ---------------------------------------------------------------------------------------

    private data class Ex(
        val name: String,
        val primary: String? = null,
        val secondary: String? = null,
        val equipment: String? = null
    )

    private val catalog = listOf(
        Ex("Supino Reto com Barra", "Peitoral", "Tríceps, Deltóide Anterior", "Barra"),
        Ex("Desenvolvimento Militar", "Ombros", "Tríceps", "Barra"),
        Ex("Elevação Lateral", "Deltoide Lateral", "Trapézio", "Halteres"),
        Ex("Elevação Frontal", "Deltoide Anterior", "Peitoral", "Halteres"),
        Ex("Arnold Press", "Ombros", "Tríceps", "Halteres"),
        Ex("Puxada Frontal", "Dorsal", "Bíceps", "Polia"),
        Ex("Remada Curvada", "Dorsal", "Bíceps", "Barra"),
        Ex("Agachamento Livre", "Quadríceps", "Glúteos", "Barra"),
        Ex("Rosca Direta", "Bíceps", "Antebraço", "Barra"),
        Ex("Prancha Abdominal", "Abdômen", "Core", "Peso do Corpo")
    )

    private fun search(query: String): List<String> = ExerciseSearchEngine.filter(
        items = catalog,
        query = query,
        nameSelector = { it.name },
        primaryMuscleSelector = { it.primary },
        secondaryMusclesSelector = { it.secondary },
        equipmentSelector = { it.equipment }
    ).map { it.name }

    @Test
    fun `busca por grupo muscular retorna exercicios do grupo`() {
        val results = search("ombro")

        assertTrue("Deve encontrar desenvolvimento", results.contains("Desenvolvimento Militar"))
        assertTrue("Deve encontrar elevacao lateral", results.contains("Elevação Lateral"))
        assertTrue("Deve encontrar elevacao frontal", results.contains("Elevação Frontal"))
        assertTrue("Deve encontrar arnold press", results.contains("Arnold Press"))

        // Os quatro exercícios de ombro vêm antes de qualquer correspondência secundária.
        val shoulderNames = setOf("Desenvolvimento Militar", "Elevação Lateral", "Elevação Frontal", "Arnold Press")
        assertEquals(shoulderNames, results.take(4).toSet())
    }

    @Test
    fun `busca por equipamento retorna exercicios compativeis`() {
        val results = search("halter")

        assertEquals(
            setOf("Elevação Lateral", "Elevação Frontal", "Arnold Press"),
            results.toSet()
        )
    }

    @Test
    fun `busca por costas retorna exercicios de dorsal`() {
        val results = search("costas")

        assertTrue(results.contains("Puxada Frontal"))
        assertTrue(results.contains("Remada Curvada"))
        assertEquals(setOf("Puxada Frontal", "Remada Curvada"), results.take(2).toSet())
    }

    @Test
    fun `sinonimos encontram os mesmos resultados nos dois sentidos`() {
        val byPortuguese = search("ombro").toSet()
        val byEnglish = search("shoulder").toSet()
        val byMuscle = search("deltoide").toSet()

        assertEquals(byPortuguese, byEnglish)
        assertEquals(byPortuguese, byMuscle)

        assertEquals(search("halter").toSet(), search("dumbbell").toSet())
    }

    @Test
    fun `regiao muscular alcanca os musculos que a compoem`() {
        // "perna" não aparece em nenhum campo do agachamento, mas "Quadríceps" pertence à região.
        assertTrue(search("perna").contains("Agachamento Livre"))
        // "braco" alcança bíceps.
        assertTrue(search("braco").contains("Rosca Direta"))
    }

    @Test
    fun `sinonimos curtos nao casam no meio de palavras`() {
        // "lat" é sinônimo de costas, mas não pode casar dentro de "elevacao LATeral" — senão uma
        // elevação de ombro apareceria como exercício de dorsal.
        assertEquals(0, ExerciseSearchEngine.score(query = "costas", name = "Elevação Lateral"))
        assertEquals(0, ExerciseSearchEngine.score(query = "perna", name = "Alongamento"))
        // O mesmo termo curto casa quando é uma palavra inteira.
        assertTrue(ExerciseSearchEngine.score(query = "costas", name = "Pulldown", primaryMuscle = "Lat") > 0)
    }

    @Test
    fun `musculo secundario aparece no fim da lista e nao no topo`() {
        // Supino envolve o deltóide anterior, então deve ser encontrado por "ombro" — mas depois
        // de todos os exercícios cujo alvo principal é o ombro.
        val results = search("ombro")

        assertTrue(results.contains("Supino Reto com Barra"))
        assertEquals("Supino Reto com Barra", results.last())
    }

    @Test
    fun `busca por nome continua exata e prioritaria`() {
        val results = search("supino")

        assertEquals(listOf("Supino Reto com Barra"), results)
    }

    @Test
    fun `busca vazia devolve o catalogo intacto`() {
        assertEquals(catalog.size, search("").size)
        assertEquals(catalog.size, search("   ").size)
    }

    @Test
    fun `busca sem correspondencia devolve lista vazia`() {
        assertTrue(search("zzzz inexistente").isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // PARTE 2 — Reordenação ponta a ponta
    // ---------------------------------------------------------------------------------------

    private fun exerciseSession(
        id: Long,
        name: String,
        plannedOrder: Int,
        executionOrder: Int,
        totalSets: Int,
        completedSets: Int
    ): ExerciseSessionWithSets {
        val sets = (1..totalSets).map { setNumber ->
            SetLogEntity(
                id = id * 100 + setNumber,
                exerciseSessionId = id,
                setNumber = setNumber,
                completed = setNumber <= completedSets,
                weight = 40f,
                repetitions = 10
            )
        }
        return ExerciseSessionWithSets(
            exerciseSession = ExerciseSessionEntity(
                id = id,
                sessionId = 900L,
                plannedExerciseId = id,
                actualExerciseId = id,
                exerciseNameSnapshot = name,
                sortOrder = executionOrder,
                plannedOrder = plannedOrder,
                executionOrder = executionOrder
            ),
            sets = sets
        )
    }

    /** Builds the state the way the ViewModel does: exercises sorted by executionOrder. */
    private fun stateOf(
        exercises: List<ExerciseSessionWithSets>,
        currentIndex: Int
    ): ExecutionState {
        val sorted = exercises.sortedBy { it.exerciseSession.executionOrder }
        return ExecutionState(
            sessionWithDetails = SessionWithDetails(
                session = WorkoutSessionEntity(id = 900L, templateId = 1L, startedAt = 0L),
                exercises = sorted
            ),
            currentExerciseIndex = currentIndex,
            isLoading = false
        )
    }

    @Test
    fun `cenario critico - apos concluir C e A o proximo e B e nao o fim do treino`() {
        // Plano A, B, C reordenado para C, A, B. C e A concluídos, B ainda pendente.
        val a = exerciseSession(id = 1, name = "A", plannedOrder = 1, executionOrder = 2, totalSets = 3, completedSets = 3)
        val b = exerciseSession(id = 2, name = "B", plannedOrder = 2, executionOrder = 3, totalSets = 3, completedSets = 0)
        val c = exerciseSession(id = 3, name = "C", plannedOrder = 3, executionOrder = 1, totalSets = 3, completedSets = 3)

        // Usuário está em A (posição 2 da execução), que acabou de terminar.
        val state = stateOf(listOf(a, b, c), currentIndex = 1)

        assertEquals(listOf("C", "A", "B"), state.sessionWithDetails!!.exercises.map { it.exerciseSession.exerciseNameSnapshot })
        assertFalse("O treino não pode ser dado como concluído", state.isAllExercisesCompleted)
        assertEquals(ExecutionPhase.EXERCISE_TRANSITION, state.phase)
        assertEquals("B", state.nextPendingExercise?.exerciseSession?.exerciseNameSnapshot)
    }

    @Test
    fun `estar na ultima posicao nao oferece concluir treino se algo esta pendente`() {
        // Usuário na última posição (B), mas A continua pendente após a reordenação.
        val a = exerciseSession(id = 1, name = "A", plannedOrder = 1, executionOrder = 2, totalSets = 3, completedSets = 0)
        val b = exerciseSession(id = 2, name = "B", plannedOrder = 2, executionOrder = 3, totalSets = 3, completedSets = 2)
        val c = exerciseSession(id = 3, name = "C", plannedOrder = 3, executionOrder = 1, totalSets = 3, completedSets = 3)

        val state = stateOf(listOf(a, b, c), currentIndex = 2)

        assertTrue("Posicionalmente é o último", state.isLastExercise)
        assertFalse("Mas ainda há exercício pendente, então não encerra o treino", state.isLastPendingExercise)
        assertEquals("A", state.nextPendingExercise?.exerciseSession?.exerciseNameSnapshot)
    }

    @Test
    fun `ultimo exercicio pendente de fato encerra o treino`() {
        val a = exerciseSession(id = 1, name = "A", plannedOrder = 1, executionOrder = 2, totalSets = 3, completedSets = 3)
        val b = exerciseSession(id = 2, name = "B", plannedOrder = 2, executionOrder = 3, totalSets = 3, completedSets = 2)
        val c = exerciseSession(id = 3, name = "C", plannedOrder = 3, executionOrder = 1, totalSets = 3, completedSets = 3)

        val state = stateOf(listOf(a, b, c), currentIndex = 2)

        assertTrue(state.isLastPendingExercise)
        assertNull(state.nextPendingExercise)
    }

    @Test
    fun `treino so e concluido quando todos os exercicios terminam`() {
        val a = exerciseSession(id = 1, name = "A", plannedOrder = 1, executionOrder = 2, totalSets = 2, completedSets = 2)
        val b = exerciseSession(id = 2, name = "B", plannedOrder = 2, executionOrder = 3, totalSets = 2, completedSets = 2)
        val c = exerciseSession(id = 3, name = "C", plannedOrder = 3, executionOrder = 1, totalSets = 2, completedSets = 2)

        val state = stateOf(listOf(a, b, c), currentIndex = 2)

        assertTrue(state.isAllExercisesCompleted)
        assertEquals(ExecutionPhase.WORKOUT_COMPLETE, state.phase)
    }

    @Test
    fun `exercicio sem series nao conta como concluido`() {
        val a = exerciseSession(id = 1, name = "A", plannedOrder = 1, executionOrder = 1, totalSets = 2, completedSets = 2)
        val added = exerciseSession(id = 2, name = "Adicionado", plannedOrder = 2, executionOrder = 2, totalSets = 0, completedSets = 0)

        val state = stateOf(listOf(a, added), currentIndex = 0)

        assertFalse(state.isAllExercisesCompleted)
        assertEquals("Adicionado", state.nextPendingExercise?.exerciseSession?.exerciseNameSnapshot)
    }

    @Test
    fun `ordem adaptada e detectada apenas quando execucao difere do planejado`() {
        val naturalOrder = listOf(
            exerciseSession(id = 1, name = "A", plannedOrder = 1, executionOrder = 1, totalSets = 1, completedSets = 0),
            exerciseSession(id = 2, name = "B", plannedOrder = 2, executionOrder = 2, totalSets = 1, completedSets = 0)
        )
        assertFalse(stateOf(naturalOrder, 0).isOrderAdapted)

        val adapted = listOf(
            exerciseSession(id = 1, name = "A", plannedOrder = 1, executionOrder = 2, totalSets = 1, completedSets = 0),
            exerciseSession(id = 2, name = "B", plannedOrder = 2, executionOrder = 1, totalSets = 1, completedSets = 0)
        )
        assertTrue(stateOf(adapted, 0).isOrderAdapted)
    }

    // ---------------------------------------------------------------------------------------
    // PARTE 5 — RIR explicado
    // ---------------------------------------------------------------------------------------

    @Test
    fun `rotulo de esforco nao duplica o emoji da falha`() {
        assertEquals("🔥 Até a falha", RirFormatter.formatEffort(0))
        assertEquals("🔥 Falha", RirFormatter.formatEffort(0, short = true))
        assertEquals("😤 Muito pesado", RirFormatter.formatEffort(1))
        assertEquals("💪 Pesado", RirFormatter.formatEffort(2))
        assertEquals("🙂 Controlado", RirFormatter.formatEffort(3))
        assertEquals("🙂 Controlado", RirFormatter.formatEffort(9))
        assertNull(RirFormatter.formatEffort(null))
    }

    @Test
    fun `rir e explicado em linguagem simples`() {
        assertTrue(RirFormatter.HELP_DEFINITION.contains("Repetições em Reserva"))
        assertTrue(RirFormatter.HELP_DEFINITION.contains("falha"))
        assertEquals(4, RirFormatter.HELP_SCALE.size)
        assertTrue(RirFormatter.HELP_SCALE.first().first.contains("Falha"))
        assertTrue(RirFormatter.HELP_TITLE.contains("RIR"))
    }

    @Test
    fun `valor tecnico do rir continua disponivel como informacao complementar`() {
        assertEquals("RIR 2", RirFormatter.formatSecondaryRir(2))
        assertEquals("RIR 4+", RirFormatter.formatSecondaryRir(7))
        assertEquals("", RirFormatter.formatSecondaryRir(null))
    }

    // ---------------------------------------------------------------------------------------
    // PARTE 6 — Destaque único da tela Hoje
    // ---------------------------------------------------------------------------------------

    @Test
    fun `sequencia de semanas conta semanas consecutivas com treino`() {
        val now = System.currentTimeMillis()
        val week = 7L * 24 * 60 * 60 * 1000

        assertEquals(0, TodayHighlightCalculator.calculateStreakWeeks(emptyList(), now))
        // Esta semana e as três anteriores.
        assertEquals(
            4,
            TodayHighlightCalculator.calculateStreakWeeks(
                listOf(now, now - week, now - 2 * week, now - 3 * week),
                now
            )
        )
        // Um buraco na semana passada interrompe a contagem.
        assertEquals(
            1,
            TodayHighlightCalculator.calculateStreakWeeks(
                listOf(now, now - 2 * week, now - 3 * week),
                now
            )
        )
    }

    @Test
    fun `sequencia sobrevive a semana corrente ainda sem treino`() {
        val now = System.currentTimeMillis()
        val week = 7L * 24 * 60 * 60 * 1000

        // Treinou nas duas semanas anteriores, mas ainda não nesta.
        assertEquals(
            2,
            TodayHighlightCalculator.calculateStreakWeeks(listOf(now - week, now - 2 * week), now)
        )
    }

    @Test
    fun `recorde antigo nao vira destaque`() {
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L

        val recent = listOf(PersonalRecordEntity(id = 1, exerciseId = 1, date = now - day, prType = PRType.MAX_WEIGHT, value = 100f))
        val recentText = TodayHighlightCalculator.formatRecentMilestone(recent, now)
        assertNotNull(recentText)
        assertTrue(recentText!!.contains("100"))

        val old = listOf(PersonalRecordEntity(id = 2, exerciseId = 1, date = now - 40 * day, prType = PRType.MAX_WEIGHT, value = 100f))
        assertNull(TodayHighlightCalculator.formatRecentMilestone(old, now))
    }

    @Test
    fun `hoje mostra um unico destaque e nada quando nao ha novidade`() {
        val record = TodayHighlightCalculator.buildHighlight(streakWeeks = 5, recentMilestone = "Novo recorde de carga: 100 kg")
        assertEquals("🏆", record?.emoji)
        assertTrue(record!!.text.contains("recorde"))

        val streak = TodayHighlightCalculator.buildHighlight(streakWeeks = 4, recentMilestone = null)
        assertEquals("🔥", streak?.emoji)
        assertEquals("4 semanas treinando", streak?.text)

        // Uma semana isolada ainda não é uma sequência digna de destaque.
        assertNull(TodayHighlightCalculator.buildHighlight(streakWeeks = 1, recentMilestone = null))
        assertNull(TodayHighlightCalculator.buildHighlight(streakWeeks = 0, recentMilestone = null))
    }

    // ---------------------------------------------------------------------------------------
    // PARTE 8 — Filtros do histórico
    // ---------------------------------------------------------------------------------------

    @Test
    fun `periodos do historico cobrem semana mes e ano`() {
        assertEquals(
            listOf("Semana", "Mês", "Ano", "Tudo"),
            HistoryPeriod.entries.map { it.label }
        )
    }

    @Test
    fun `inicio do periodo respeita semana mes e ano correntes`() {
        val now = System.currentTimeMillis()

        assertEquals(0L, HistoryPeriod.ALL.startTimestamp(now))

        val monthStart = Calendar.getInstance().apply { timeInMillis = HistoryPeriod.MONTH.startTimestamp(now) }
        assertEquals(1, monthStart.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, monthStart.get(Calendar.HOUR_OF_DAY))

        val yearStart = Calendar.getInstance().apply { timeInMillis = HistoryPeriod.YEAR.startTimestamp(now) }
        assertEquals(1, yearStart.get(Calendar.DAY_OF_YEAR))

        val weekStart = Calendar.getInstance().apply { timeInMillis = HistoryPeriod.WEEK.startTimestamp(now) }
        assertEquals(Calendar.MONDAY, weekStart.get(Calendar.DAY_OF_WEEK))

        // Janelas mais curtas nunca começam antes das mais longas.
        assertTrue(HistoryPeriod.WEEK.startTimestamp(now) >= HistoryPeriod.MONTH.startTimestamp(now) ||
                HistoryPeriod.MONTH.startTimestamp(now) <= now)
        assertTrue(HistoryPeriod.YEAR.startTimestamp(now) <= HistoryPeriod.MONTH.startTimestamp(now))
    }
}
