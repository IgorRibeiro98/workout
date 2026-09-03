package com.example

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SessionCalendarSummary
import com.example.data.local.SetLogEntity
import com.example.data.local.SetType
import com.example.data.local.WorkoutSessionEntity
import com.example.domain.engine.ExerciseSearchEngine
import com.example.domain.engine.RirFormatter
import com.example.domain.model.ExerciseExecutionMode
import com.example.domain.model.ExerciseExecutionValue
import com.example.domain.performance.calculator.VolumeCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T12ConsolidatedFeedbackTest {

    private val sampleExercises = listOf(
        ExerciseEntity(
            id = 1,
            name = "Supino Reto com Barra",
            primaryMuscle = "Peitoral",
            secondaryMuscles = "Tríceps, Deltóide Anterior",
            equipment = "Barra"
        ),
        ExerciseEntity(
            id = 2,
            name = "Prancha Abdominal Isométrica",
            primaryMuscle = "Abdômen",
            secondaryMuscles = "Core, Lombar",
            equipment = "Peso do Corpo"
        ),
        ExerciseEntity(
            id = 3,
            name = "Agachamento Livre",
            primaryMuscle = "Quadríceps",
            secondaryMuscles = "Glúteos, Posterior",
            equipment = "Barra"
        ),
        ExerciseEntity(
            id = 4,
            name = "Puxada Frontal",
            primaryMuscle = "Dorsal",
            secondaryMuscles = "Bíceps",
            equipment = "Polia"
        )
    )

    private fun filter(query: String): List<ExerciseEntity> {
        return ExerciseSearchEngine.filter(
            items = sampleExercises,
            query = query,
            nameSelector = { it.name },
            primaryMuscleSelector = { it.primaryMuscle },
            secondaryMusclesSelector = { it.secondaryMuscles },
            equipmentSelector = { it.equipment }
        )
    }

    @Test
    fun testExerciseSearchEngine_byNameAndAccentInsensitive() {
        // "supino" finds "Supino Reto com Barra"
        val results1 = filter("supino")
        assertEquals(1, results1.size)
        assertEquals(1L, results1.first().id)

        // "abdomen" without accent finds "Prancha Abdominal Isométrica"
        val results2 = filter("abdomen")
        assertEquals(1, results2.size)
        assertEquals(2L, results2.first().id)

        // "prancha" finds plank
        val results3 = filter("prancha")
        assertEquals(1, results3.size)
        assertEquals(2L, results3.first().id)
    }

    @Test
    fun testExerciseSearchEngine_byMuscleAliasAndEquipment() {
        // "costas" should find "Dorsal"
        val results1 = filter("costas")
        assertTrue(results1.any { it.name == "Puxada Frontal" })

        // "peito" should find "Peitoral"
        val results2 = filter("peito")
        assertTrue(results2.any { it.name == "Supino Reto com Barra" })

        // "perna" should find "Quadríceps"
        val results3 = filter("perna")
        assertTrue(results3.any { it.name == "Agachamento Livre" })

        // "peso do corpo" should find bodyweight exercise
        val results4 = filter("peso do corpo")
        assertTrue(results4.any { it.name == "Prancha Abdominal Isométrica" })
    }

    @Test
    fun testDurationSets_doNotDistortVolumeTonnage() {
        val repetitionSet = SetLogEntity(
            exerciseSessionId = 1L,
            setNumber = 1,
            weight = 100f,
            repetitions = 10,
            completed = true,
            durationSeconds = null,
            type = SetType.NORMAL.name
        )

        val durationSet = SetLogEntity(
            exerciseSessionId = 2L,
            setNumber = 1,
            weight = 10f, // 10kg plate on back during plank
            repetitions = 60, // 60 seconds
            durationSeconds = 60,
            completed = true,
            type = SetType.NORMAL.name
        )

        // Repetition set tonnage = 100 * 10 = 1000kg
        assertEquals(1000.0, VolumeCalculator.calculateSetsVolume(listOf(repetitionSet)), 0.001)

        // Duration set tonnage must be 0.0 in standard volume tonnage calculation
        assertEquals(0.0, VolumeCalculator.calculateSetsVolume(listOf(durationSet)), 0.001)

        // Combined volume must remain 1000.0, not 1600.0
        val combinedVolume = VolumeCalculator.calculateSetsVolume(listOf(repetitionSet, durationSet))
        assertEquals(1000.0, combinedVolume, 0.001)
    }

    @Test
    fun testSetExecutionValue_helpers() {
        val repVal = ExerciseExecutionValue(ExerciseExecutionMode.REPS, 12)
        assertFalse(repVal.isDuration)
        assertEquals(12, repVal.value)
        assertEquals("12 reps", repVal.formatted)

        val durVal = ExerciseExecutionValue(ExerciseExecutionMode.DURATION, 45)
        assertTrue(durVal.isDuration)
        assertEquals(45, durVal.value)
        assertEquals("45s", durVal.formatted)
    }

    @Test
    fun testSortedExercises_orderingStability() {
        val ex1 = ExerciseSessionWithSets(
            exerciseSession = ExerciseSessionEntity(id = 10, sessionId = 1, plannedExerciseId = 1, actualExerciseId = 1, exerciseNameSnapshot = "Ex 1", executionOrder = 3, sortOrder = 3),
            sets = emptyList()
        )
        val ex2 = ExerciseSessionWithSets(
            exerciseSession = ExerciseSessionEntity(id = 20, sessionId = 1, plannedExerciseId = 2, actualExerciseId = 2, exerciseNameSnapshot = "Ex 2", executionOrder = 1, sortOrder = 1),
            sets = emptyList()
        )
        val ex3 = ExerciseSessionWithSets(
            exerciseSession = ExerciseSessionEntity(id = 30, sessionId = 1, plannedExerciseId = 3, actualExerciseId = 3, exerciseNameSnapshot = "Ex 3", executionOrder = 2, sortOrder = 2),
            sets = emptyList()
        )

        val summary = SessionCalendarSummary(
            session = WorkoutSessionEntity(id = 1, templateId = 1L, startedAt = 1000L),
            checkIn = null,
            exercises = listOf(ex1, ex2, ex3)
        )

        val sorted = summary.sortedExercises
        assertEquals(3, sorted.size)
        assertEquals(20L, sorted[0].exerciseSession.id) // executionOrder 1
        assertEquals(30L, sorted[1].exerciseSession.id) // executionOrder 2
        assertEquals(10L, sorted[2].exerciseSession.id) // executionOrder 3
    }

    @Test
    fun testRirFormatter_labels() {
        assertEquals("RIR 0", RirFormatter.formatSecondaryRir(0))
        assertEquals("RIR 1", RirFormatter.formatSecondaryRir(1))
        assertEquals("RIR 2", RirFormatter.formatSecondaryRir(2))
        assertTrue(RirFormatter.isFailure(0))
        assertFalse(RirFormatter.isFailure(2))
    }
}
