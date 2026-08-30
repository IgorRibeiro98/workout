package com.example

import com.example.data.local.ExerciseEntity
import com.example.data.remote.ExternalExerciseDto
import com.example.domain.engine.ExerciseMatchStatus
import com.example.domain.engine.ExerciseMediaEngine
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class ExerciseDbTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(ExternalExerciseDto::class.java)

    @Test
    fun `test real ExerciseDB DTO parsing`() {
        val jsonFixture = """
            {
                "exerciseId": "ex_12345",
                "name": "barbell bench press",
                "gifUrl": "https://oss.exercisedb.dev/gifs/barbell_bench_press.gif",
                "targetMuscles": ["pectorals", "deltoids"],
                "bodyParts": ["chest"],
                "equipments": ["barbell"],
                "secondaryMuscles": ["triceps"],
                "instructions": ["Lie back on a flat bench", "Lower the bar to your chest", "Press upwards"]
            }
        """.trimIndent()

        val dto = adapter.fromJson(jsonFixture)
        assertNotNull(dto)
        assertEquals("ex_12345", dto?.realId)
        assertEquals("barbell bench press", dto?.name)
        assertEquals("https://oss.exercisedb.dev/gifs/barbell_bench_press.gif", dto?.gifUrl)
        assertEquals(listOf("pectorals", "deltoids"), dto?.realTargetMuscles)
        assertEquals(listOf("chest"), dto?.realBodyParts)
        assertEquals(listOf("barbell"), dto?.realEquipments)
    }

    @Test
    fun `test exact match evaluation scores high and returns MATCHED`() {
        val engine = ExerciseMediaEngine(dao = FakeWorkoutDao())
        val exercise = ExerciseEntity(
            name = "Barbell Bench Press",
            primaryMuscle = "pectorals",
            equipment = "barbell",
            exerciseDbSearch = "barbell bench press"
        )
        val candidates = listOf(
            ExternalExerciseDto(
                id = "0025",
                name = "barbell bench press",
                gifUrl = "https://example.com/bench.gif",
                targetMuscles = listOf("pectorals"),
                equipments = listOf("barbell")
            )
        )

        val eval = engine.evaluateCandidates(exercise, candidates)
        assertEquals(ExerciseMatchStatus.MATCHED, eval.status)
        assertEquals("0025", eval.candidate?.realId)
    }

    @Test
    fun `test ambiguous match when top two candidates have close scores`() {
        val engine = ExerciseMediaEngine(dao = FakeWorkoutDao())
        val exercise = ExerciseEntity(
            name = "Bench Press",
            primaryMuscle = "pectorals",
            equipment = "barbell",
            exerciseDbSearch = "bench press"
        )
        val candidates = listOf(
            ExternalExerciseDto(
                id = "001",
                name = "barbell bench press",
                gifUrl = "https://example.com/1.gif",
                targetMuscles = listOf("pectorals"),
                equipments = listOf("barbell")
            ),
            ExternalExerciseDto(
                id = "002",
                name = "dumbbell bench press",
                gifUrl = "https://example.com/2.gif",
                targetMuscles = listOf("pectorals"),
                equipments = listOf("barbell")
            )
        )

        val eval = engine.evaluateCandidates(exercise, candidates)
        assertEquals(ExerciseMatchStatus.AMBIGUOUS, eval.status)
    }

    @Test
    fun `test not found status when no suitable candidate matches score threshold`() {
        val engine = ExerciseMediaEngine(dao = FakeWorkoutDao())
        val exercise = ExerciseEntity(
            name = "Exercício Desconhecido Raro",
            exerciseDbSearch = "unknown rare movement"
        )
        val candidates = listOf(
            ExternalExerciseDto(
                id = "999",
                name = "cable triceps pushdown",
                gifUrl = "https://example.com/pushdown.gif"
            )
        )

        val eval = engine.evaluateCandidates(exercise, candidates)
        assertEquals(ExerciseMatchStatus.NOT_FOUND, eval.status)
        assertNull(eval.candidate)
    }
}
