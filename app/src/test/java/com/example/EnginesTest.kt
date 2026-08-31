package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.*
import com.example.domain.engine.ExportEngine
import com.example.domain.engine.StatsEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnginesTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var context: Context

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.workoutDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun `test export engine generates valid json`() = runBlocking {
        val exId = dao.insertExercise(
            ExerciseEntity(
                name = "Supino Reto",
                canonicalId = "supino-reto-barra",
                primaryMuscle = "Peitoral"
            )
        )
        val pId = dao.insertProgram(WorkoutProgramEntity(name = "Prog 1"))
        val tId = dao.insertTemplate(WorkoutTemplateEntity(programId = pId, name = "Treino A", orderInProgram = 0))
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = tId, exerciseId = exId, sortOrder = 0))

        val exporter = ExportEngine(dao, context)
        val uri = exporter.exportData()
        assertNotNull(uri)
    }

    @Test
    fun `test stats engine calculates volume and sets correctly`() = runBlocking {
        val now = System.currentTimeMillis()
        val startOfWeek = now - 3 * 24 * 60 * 60 * 1000L
        val endOfWeek = now + 3 * 24 * 60 * 60 * 1000L

        val exId = dao.insertExercise(
            ExerciseEntity(
                name = "Supino Reto",
                canonicalId = "supino-reto-barra",
                primaryMuscle = "chest"
            )
        )

        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = now,
                finishedAt = now + 3600000L,
                status = "COMPLETED",
                templateNameSnapshot = "Treino A"
            )
        )

        val exSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Supino Reto",
                primaryMuscleSnapshot = "chest",
                sortOrder = 0
            )
        )

        dao.insertSetLogs(
            listOf(
                SetLogEntity(
                    exerciseSessionId = exSessionId,
                    setNumber = 1,
                    type = "NORMAL",
                    weight = 80f,
                    repetitions = 10,
                    completed = true
                ),
                SetLogEntity(
                    exerciseSessionId = exSessionId,
                    setNumber = 2,
                    type = "NORMAL",
                    weight = 80f,
                    repetitions = 10,
                    completed = true
                )
            )
        )

        val stats = StatsEngine(dao)
        val weeklyStats = stats.getWeeklyStatsFlow(startOfWeek, endOfWeek).first()

        assertEquals(1, weeklyStats.workoutsCount)
        assertEquals(2, weeklyStats.setsCount)
        assertEquals(1600L, weeklyStats.volume)
        assertEquals(3600000L, weeklyStats.durationMs)
    }
}
