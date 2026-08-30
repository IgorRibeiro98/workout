package com.example

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.data.local.SessionCalendarSummary
import com.example.data.local.SetLogEntity
import com.example.data.local.SetType
import com.example.data.local.WorkoutSessionEntity
import com.example.domain.engine.*
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    
    @Test
    fun testMuscleVisualResolver() {
        val chest = MuscleVisualResolver.resolveGroup("Peitoral Superior")
        assertEquals("Peitoral", chest.displayName)

        val back = MuscleVisualResolver.resolveGroup("Dorsal / Costas")
        assertEquals("Costas", back.displayName)

        val quads = MuscleVisualResolver.resolveGroup("Quadríceps")
        assertEquals("Quadríceps", quads.displayName)

        val shoulders = MuscleVisualResolver.resolveGroup("Deltoide Lateral")
        assertEquals("Ombros", shoulders.displayName)

        val biceps = MuscleVisualResolver.resolveGroup("Bíceps Braquial")
        assertEquals("Bíceps", biceps.displayName)
    }

    @Test
    fun testMuscleNormalizer() {
        val normalized = MuscleNormalizer.normalize("Peitoral superior")
        assertEquals("Peitoral", normalized)

        val dorsal = MuscleNormalizer.normalize("Dorsal / Costas")
        assertEquals("Costas", dorsal)

        val gluteos = MuscleNormalizer.normalize("Glúteo Máximo")
        assertEquals("Glúteos", gluteos)
    }

    @Test
    fun testProgressionEngine_CeilingReached() {
        val prevSets = listOf(
            SetLogEntity(exerciseSessionId = 1, setNumber = 1, repetitions = 12, weight = 50f, completed = true),
            SetLogEntity(exerciseSessionId = 1, setNumber = 2, repetitions = 12, weight = 50f, completed = true),
            SetLogEntity(exerciseSessionId = 1, setNumber = 3, repetitions = 12, weight = 50f, completed = true)
        )
        val rec = ProgressionEngine.evaluateProgression(
            currentSets = emptyList(),
            previousSets = prevSets,
            minTargetReps = 8,
            maxTargetReps = 12
        )
        assertEquals(ProgressionAction.INCREASE, rec.action)
        assertEquals(2.0f, rec.suggestedWeightDelta, 0.01f)
    }

    @Test
    fun testProgressionEngine_BelowFloor() {
        val prevSets = listOf(
            SetLogEntity(exerciseSessionId = 1, setNumber = 1, repetitions = 6, weight = 50f, completed = true),
            SetLogEntity(exerciseSessionId = 1, setNumber = 2, repetitions = 7, weight = 50f, completed = true)
        )
        val rec = ProgressionEngine.evaluateProgression(
            currentSets = emptyList(),
            previousSets = prevSets,
            minTargetReps = 8,
            maxTargetReps = 12
        )
        assertEquals(ProgressionAction.DECREASE, rec.action)
    }

    @Test
    fun testProgressionEngine_Maintain() {
        val prevSets = listOf(
            SetLogEntity(exerciseSessionId = 1, setNumber = 1, repetitions = 10, weight = 50f, completed = true),
            SetLogEntity(exerciseSessionId = 1, setNumber = 2, repetitions = 9, weight = 50f, completed = true)
        )
        val rec = ProgressionEngine.evaluateProgression(
            currentSets = emptyList(),
            previousSets = prevSets,
            minTargetReps = 8,
            maxTargetReps = 12
        )
        assertEquals(ProgressionAction.MAINTAIN, rec.action)
    }

    @Test
    fun testVolumeCalculator_ExcludesWarmupSets() {
        val sets = listOf(
            SetLogEntity(exerciseSessionId = 100, setNumber = 1, repetitions = 15, weight = 20f, type = SetType.WARMUP.name, completed = true), // Warmup: 300kg (must be excluded)
            SetLogEntity(exerciseSessionId = 100, setNumber = 2, repetitions = 10, weight = 100f, type = SetType.NORMAL.name, completed = true), // 1000kg
            SetLogEntity(exerciseSessionId = 100, setNumber = 3, repetitions = 8, weight = 100f, type = SetType.NORMAL.name, completed = true),  // 800kg
            SetLogEntity(exerciseSessionId = 100, setNumber = 4, repetitions = 10, weight = 100f, type = SetType.NORMAL.name, completed = false) // uncompleted: ignored
        )
        val vol = VolumeCalculator.calculateVolume(sets)
        assertEquals(1800.0, vol, 0.01)

        val effectiveCount = VolumeCalculator.countEffectiveSets(sets)
        assertEquals(2, effectiveCount)

        val oneRm = VolumeCalculator.calculateOneRepMax(100f, 10)
        assertTrue("1RM should be around 133kg", oneRm in 130f..135f)
    }

    @Test
    fun testExerciseMediaResolver_Priority() {
        val exercise = ExerciseEntity(
            id = 1L,
            name = "Supino Reto com Barra",
            primaryMuscle = "Peitoral",
            gifUrl = "https://oss.exercisedb.dev/image/supino.gif",
            mediaUrl = "https://static.com/supino.png"
        )

        // Case 1: Override has custom photo -> Must take priority over GIF and static
        val overrideWithPhoto = ExerciseUserOverrideEntity(
            exerciseId = 1L,
            customPhotoUri = "content://media/photo1.jpg",
            displayName = "Meu Supino Top"
        )
        val media1 = ExerciseMediaResolver.resolveMedia(exercise, overrideWithPhoto, showGifs = true)
        assertEquals("content://media/photo1.jpg", media1.mediaUri)
        assertTrue(media1.isCustomPhoto)
        assertFalse(media1.isGif)

        // Case 2: No custom photo, showGifs is true -> Must use GIF
        val overrideNoPhoto = ExerciseUserOverrideEntity(exerciseId = 1L, customPhotoUri = null)
        val media2 = ExerciseMediaResolver.resolveMedia(exercise, overrideNoPhoto, showGifs = true)
        assertEquals("https://oss.exercisedb.dev/image/supino.gif", media2.mediaUri)
        assertFalse(media2.isCustomPhoto)
        assertTrue(media2.isGif)

        // Case 3: No custom photo, showGifs is false -> Must fallback to static mediaUrl
        val media3 = ExerciseMediaResolver.resolveMedia(exercise, overrideNoPhoto, showGifs = false)
        assertEquals("https://static.com/supino.png", media3.mediaUri)
        assertFalse(media3.isCustomPhoto)
        assertFalse(media3.isGif)
    }

    @Test
    fun testCuratedExerciseVideo_EmbedUrl() {
        val video = CuratedExerciseVideo(
            videoId = "rT7DgCr-3pg",
            title = "Como Fazer Supino Reto Perfeito",
            channel = "Canal Treino"
        )
        assertEquals("https://www.youtube-nocookie.com/embed/rT7DgCr-3pg?autoplay=1&rel=0", video.getEmbedUrl())

        val videoWithTimestamps = CuratedExerciseVideo(
            videoId = "rT7DgCr-3pg",
            title = "Supino",
            channel = "Treino",
            startSeconds = 15,
            endSeconds = 45
        )
        assertEquals("https://www.youtube-nocookie.com/embed/rT7DgCr-3pg?start=15&end=45&autoplay=1&rel=0", videoWithTimestamps.getEmbedUrl())
    }
}


