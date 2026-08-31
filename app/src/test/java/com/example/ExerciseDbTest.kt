package com.example

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.data.remote.ExternalExerciseDto
import com.example.domain.engine.ExerciseDbNormalizer
import com.example.domain.engine.ExerciseMatchStatus
import com.example.domain.engine.ExerciseMediaEngine
import com.example.domain.engine.ExerciseResolver
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
    fun `test exact match evaluation with PT-BR equipment and muscle aliases for Supino Reto`() {
        val engine = ExerciseMediaEngine(dao = FakeWorkoutDao())
        val exercise = ExerciseEntity(
            name = "Supino Reto com Barra",
            primaryMuscle = "Peitoral",
            equipment = "Barra",
            exerciseDbSearch = "Barbell Bench Press"
        )
        val candidates = listOf(
            ExternalExerciseDto(
                id = "0025",
                name = "barbell bench press",
                gifUrl = "https://oss.exercisedb.dev/gifs/0025.gif",
                targetMuscles = listOf("pectorals"),
                equipments = listOf("barbell")
            )
        )

        val eval = engine.evaluateCandidates(exercise, candidates)
        assertEquals(ExerciseMatchStatus.MATCHED, eval.status)
        assertEquals("0025", eval.candidate?.realId)
        assertTrue("Score should be 140 (100 name + 20 equip + 20 muscle)", eval.score >= 100)
    }

    @Test
    fun `test PT-BR Agachamento Livre matches Barbell Squat`() {
        val engine = ExerciseMediaEngine(dao = FakeWorkoutDao())
        val exercise = ExerciseEntity(
            name = "Agachamento Livre",
            primaryMuscle = "Quadríceps",
            equipment = "Barra",
            exerciseDbSearch = "Barbell Squat"
        )
        val candidates = listOf(
            ExternalExerciseDto(
                id = "0043",
                name = "barbell squat",
                gifUrl = "https://oss.exercisedb.dev/gifs/0043.gif",
                targetMuscles = listOf("quads"),
                equipments = listOf("barbell")
            )
        )

        val eval = engine.evaluateCandidates(exercise, candidates)
        assertEquals(ExerciseMatchStatus.MATCHED, eval.status)
        assertEquals("0043", eval.candidate?.realId)
    }

    @Test
    fun `test PT-BR Flexao matches Push-Up with body weight and peitoral aliases`() {
        val engine = ExerciseMediaEngine(dao = FakeWorkoutDao())
        val exercise = ExerciseEntity(
            name = "Flexão de Braços",
            primaryMuscle = "Peitoral",
            equipment = "Peso corporal",
            exerciseDbSearch = "Push-Up"
        )
        val candidates = listOf(
            ExternalExerciseDto(
                id = "0662",
                name = "push up",
                gifUrl = "https://oss.exercisedb.dev/gifs/0662.gif",
                targetMuscles = listOf("pectorals"),
                equipments = listOf("body weight")
            )
        )

        val eval = engine.evaluateCandidates(exercise, candidates)
        assertEquals(ExerciseMatchStatus.MATCHED, eval.status)
        assertEquals("0662", eval.candidate?.realId)
    }

    @Test
    fun `test PT-BR Cadeira Extensora matches Lever Leg Extension with machine alias`() {
        val engine = ExerciseMediaEngine(dao = FakeWorkoutDao())
        val exercise = ExerciseEntity(
            name = "Cadeira Extensora",
            primaryMuscle = "Quadríceps",
            equipment = "Máquina",
            exerciseDbSearch = "Lever Leg Extension"
        )
        val candidates = listOf(
            ExternalExerciseDto(
                id = "0585",
                name = "lever leg extension",
                gifUrl = "https://oss.exercisedb.dev/gifs/0585.gif",
                targetMuscles = listOf("quads"),
                equipments = listOf("leverage machine")
            )
        )

        val eval = engine.evaluateCandidates(exercise, candidates)
        assertEquals(ExerciseMatchStatus.MATCHED, eval.status)
        assertEquals("0585", eval.candidate?.realId)
    }

    @Test
    fun `test exact name match is preserved even if candidate lacks equipment and muscle`() {
        val engine = ExerciseMediaEngine(dao = FakeWorkoutDao())
        val exercise = ExerciseEntity(
            name = "Barbell Bench Press",
            primaryMuscle = "Peitoral",
            equipment = "Barra",
            exerciseDbSearch = "Barbell Bench Press"
        )
        val candidates = listOf(
            ExternalExerciseDto(
                id = "0025",
                name = "barbell bench press",
                gifUrl = "https://oss.exercisedb.dev/gifs/0025.gif",
                targetMuscles = emptyList(),
                equipments = emptyList()
            )
        )

        val eval = engine.evaluateCandidates(exercise, candidates)
        assertEquals(ExerciseMatchStatus.MATCHED, eval.status)
        assertEquals("0025", eval.candidate?.realId)
        assertEquals(100, eval.score)
    }

    @Test
    fun `test disambiguation using PT-BR Halteres when names are similar`() {
        val engine = ExerciseMediaEngine(dao = FakeWorkoutDao())
        val exercise = ExerciseEntity(
            name = "Supino Reto com Halteres",
            primaryMuscle = "Peitoral",
            equipment = "Halteres",
            exerciseDbSearch = "Bench Press"
        )
        val candidates = listOf(
            ExternalExerciseDto(
                id = "001",
                name = "dumbbell bench press",
                gifUrl = "https://example.com/db_bench.gif",
                targetMuscles = listOf("pectorals"),
                equipments = listOf("dumbbell")
            ),
            ExternalExerciseDto(
                id = "002",
                name = "barbell bench press",
                gifUrl = "https://example.com/bb_bench.gif",
                targetMuscles = listOf("pectorals"),
                equipments = listOf("barbell")
            )
        )

        val eval = engine.evaluateCandidates(exercise, candidates)
        assertEquals(ExerciseMatchStatus.MATCHED, eval.status)
        assertEquals("001", eval.candidate?.realId)
    }

    @Test
    fun `test ambiguous match when top two candidates have identical high scores`() {
        val engine = ExerciseMediaEngine(dao = FakeWorkoutDao())
        val exercise = ExerciseEntity(
            name = "Bench Press",
            primaryMuscle = "Peitoral",
            equipment = "Livre",
            exerciseDbSearch = "bench press"
        )
        val candidates = listOf(
            ExternalExerciseDto(
                id = "001",
                name = "bench press variation a",
                gifUrl = "https://example.com/1.gif",
                targetMuscles = listOf("pectorals"),
                equipments = listOf("barbell")
            ),
            ExternalExerciseDto(
                id = "002",
                name = "bench press variation b",
                gifUrl = "https://example.com/2.gif",
                targetMuscles = listOf("pectorals"),
                equipments = listOf("barbell")
            )
        )

        val eval = engine.evaluateCandidates(exercise, candidates)
        assertEquals(ExerciseMatchStatus.AMBIGUOUS, eval.status)
    }

    @Test
    fun `test not found status when no candidate matches score threshold`() {
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

    @Test
    fun `test custom photo priority in ExerciseResolver regardless of showGifs`() {
        val baseExercise = ExerciseEntity(
            id = 10,
            name = "Supino Reto",
            gifUrl = "https://oss.exercisedb.dev/gifs/supino.gif"
        )
        val override = ExerciseUserOverrideEntity(
            exerciseId = 10,
            customPhotoUri = "content://media/custom_photo.jpg"
        )

        val resolvedWithGifsOn = ExerciseResolver.resolve(baseExercise, override, showGifs = true)
        assertTrue(resolvedWithGifsOn.resolvedMedia.isCustomPhoto)
        assertEquals("content://media/custom_photo.jpg", resolvedWithGifsOn.resolvedMedia.mediaUri)

        val resolvedWithGifsOff = ExerciseResolver.resolve(baseExercise, override, showGifs = false)
        assertTrue(resolvedWithGifsOff.resolvedMedia.isCustomPhoto)
        assertEquals("content://media/custom_photo.jpg", resolvedWithGifsOff.resolvedMedia.mediaUri)
    }

    @Test
    fun `test showGifs toggle controls remote GIF visibility when no custom photo exists`() {
        val baseExercise = ExerciseEntity(
            id = 11,
            name = "Agachamento",
            gifUrl = "https://oss.exercisedb.dev/gifs/agachamento.gif"
        )

        val resolvedWithGifsOn = ExerciseResolver.resolve(baseExercise, null, showGifs = true)
        assertFalse(resolvedWithGifsOn.resolvedMedia.isCustomPhoto)
        assertTrue(resolvedWithGifsOn.resolvedMedia.isGif)
        assertEquals("https://oss.exercisedb.dev/gifs/agachamento.gif", resolvedWithGifsOn.resolvedMedia.mediaUri)

        val resolvedWithGifsOff = ExerciseResolver.resolve(baseExercise, null, showGifs = false)
        assertFalse(resolvedWithGifsOff.resolvedMedia.isCustomPhoto)
        assertFalse(resolvedWithGifsOff.resolvedMedia.isGif)
        assertNull(resolvedWithGifsOff.resolvedMedia.mediaUri)
    }
}
