package com.example.domain.exercise.import

import com.example.data.local.*
import com.example.data.remote.NetworkResult
import com.example.data.remote.provider.CompositeExerciseApiProvider
import com.example.data.remote.provider.ExerciseApiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseImportSyncTest {

    private class FakeWorkoutDao : WorkoutDao {
        val exercises = mutableMapOf<Long, ExerciseEntity>()
        private var idCounter = 1L

        override fun getActiveExercises(): Flow<List<ExerciseEntity>> = flowOf(exercises.values.toList())
        override suspend fun getAllExercisesList(): List<ExerciseEntity> = exercises.values.toList()
        override suspend fun getAllExercisesSync(): List<ExerciseEntity> = exercises.values.toList()

        override suspend fun insertExercise(exercise: ExerciseEntity): Long {
            val id = if (exercise.id == 0L) idCounter++ else exercise.id
            val stored = exercise.copy(id = id)
            exercises[id] = stored
            return id
        }

        override suspend fun updateExercise(exercise: ExerciseEntity) {
            exercises[exercise.id] = exercise
        }

        override suspend fun deleteExercise(exercise: ExerciseEntity) {
            exercises.remove(exercise.id)
        }

        override suspend fun getExerciseByCanonicalId(canonicalId: String): ExerciseEntity? {
            return exercises.values.firstOrNull { 
                it.externalExerciseId == canonicalId || 
                it.externalReferences?.contains(canonicalId) == true 
            }
        }

        override suspend fun getExerciseByName(name: String): ExerciseEntity? {
            return exercises.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

        override suspend fun getExerciseById(id: Long): ExerciseEntity? = exercises[id]
        override fun getExerciseByIdFlow(id: Long): Flow<ExerciseEntity?> = flowOf(exercises[id])

        // Unused stubs
        override suspend fun getAllSyncCheckpoints(): List<ExerciseSyncCheckpointEntity> = emptyList()
        override suspend fun getPendingSyncCheckpoints(): List<ExerciseSyncCheckpointEntity> = emptyList()
        override suspend fun insertSyncCheckpoints(checkpoints: List<ExerciseSyncCheckpointEntity>) {}
        override suspend fun updateSyncCheckpoint(checkpoint: ExerciseSyncCheckpointEntity) {}
        override suspend fun clearSyncCheckpoints() {}
        override suspend fun insertExerciseEducation(entity: ExerciseEducationEntity) {}
        override suspend fun insertExerciseMedia(entity: ExerciseMediaEntity) {}
        override suspend fun insertExerciseProgression(entity: ExerciseProgressionEntity) {}
        override suspend fun insertExerciseSafety(entity: ExerciseSafetyEntity) {}
        override suspend fun insertExerciseSubstitutionPremium(entity: ExerciseSubstitutionPremiumEntity) {}
        override suspend fun insertExerciseAiContext(entity: ExerciseAiContextEntity) {}
        override suspend fun insertExerciseBiomechanics(entity: ExerciseBiomechanicsEntity) {}
        override suspend fun insertExerciseExecution(entity: ExerciseExecutionEntity) {}
        override suspend fun getExerciseEducation(exerciseId: Long): ExerciseEducationEntity? = null
        override suspend fun getExerciseMedia(exerciseId: Long): ExerciseMediaEntity? = null
        override suspend fun getExerciseProgression(exerciseId: Long): ExerciseProgressionEntity? = null
        override suspend fun getExerciseSafety(exerciseId: Long): ExerciseSafetyEntity? = null
        override suspend fun getExerciseSubstitutionPremium(exerciseId: Long): ExerciseSubstitutionPremiumEntity? = null
        override suspend fun getExerciseAiContext(exerciseId: Long): ExerciseAiContextEntity? = null
        override suspend fun getExerciseBiomechanics(exerciseId: Long): ExerciseBiomechanicsEntity? = null
        override suspend fun getExerciseExecution(exerciseId: Long): ExerciseExecutionEntity? = null
        override suspend fun getProgramByExternalId(externalId: String): WorkoutProgramEntity? = null
        override fun getAllPrograms(): Flow<List<WorkoutProgramEntity>> = flowOf(emptyList())
        override suspend fun getAllProgramsSync(): List<WorkoutProgramEntity> = emptyList()
        override fun getCurrentProgram(): Flow<WorkoutProgramEntity?> = flowOf(null)
        override suspend fun insertProgram(program: WorkoutProgramEntity): Long = 0
        override suspend fun updateProgram(program: WorkoutProgramEntity) {}
        override suspend fun clearCurrentProgram() {}
        override suspend fun setCurrentProgram(id: Long) {}
        override fun getTemplatesForProgram(programId: Long): Flow<List<WorkoutTemplateEntity>> = flowOf(emptyList())
        override suspend fun getTemplatesForProgramSync(programId: Long): List<WorkoutTemplateEntity> = emptyList()
        override suspend fun insertTemplate(template: WorkoutTemplateEntity): Long = 0
        override suspend fun updateTemplate(template: WorkoutTemplateEntity) {}
        override suspend fun deleteTemplateExercisesForTemplate(templateId: Long) {}
        override suspend fun getLastCompletedSession(): WorkoutSessionEntity? = null
        override fun getWeeklyCompletedSessionsCount(startOfWeek: Long): Flow<Int> = flowOf(0)
        override fun getActiveSessionFlow(): Flow<WorkoutSessionEntity?> = flowOf(null)
        override suspend fun getActiveSession(): WorkoutSessionEntity? = null
        override suspend fun insertSession(session: WorkoutSessionEntity): Long = 0
        override suspend fun updateSession(session: WorkoutSessionEntity) {}
        override suspend fun insertExerciseSession(exerciseSession: ExerciseSessionEntity): Long = 0
        override suspend fun updateExerciseSession(exerciseSession: ExerciseSessionEntity) {}
        override suspend fun updateExerciseSessions(exerciseSessions: List<ExerciseSessionEntity>) {}
        override suspend fun insertSetLogs(setLogs: List<SetLogEntity>) {}
        override fun getActiveSessionWithDetailsFlow(): Flow<SessionWithDetails?> = flowOf(null)
        override suspend fun getLastExecutionSetsForExercise(exerciseId: Long): List<SetLogEntity> = emptyList()
        override suspend fun getSetLogsForExerciseSession(exerciseSessionId: Long): List<SetLogEntity> = emptyList()
        override suspend fun updateSetLog(setLog: SetLogEntity) {}
        override suspend fun updateSetLogs(setLogs: List<SetLogEntity>) {}
        override suspend fun deleteSetLog(setLog: SetLogEntity) {}
        override suspend fun deleteProgram(program: WorkoutProgramEntity) {}
        override suspend fun deleteTemplate(template: WorkoutTemplateEntity) {}
        override suspend fun getTemplateById(templateId: Long): WorkoutTemplateEntity? = null
        override suspend fun insertPersonalRecord(pr: PersonalRecordEntity) {}
        override suspend fun getHighestPR(exerciseId: Long, prType: String): PersonalRecordEntity? = null
        override fun getPRsForExerciseFlow(exerciseId: Long): Flow<List<PersonalRecordEntity>> = flowOf(emptyList())
        override fun getRecentPRsFlow(): Flow<List<PersonalRecordEntity>> = flowOf(emptyList())
        override suspend fun insertCheckIn(checkIn: CheckInEntity): Long = 0
        override suspend fun updateCheckIn(checkIn: CheckInEntity) {}
        override suspend fun deleteCheckIn(checkIn: CheckInEntity) {}
        override suspend fun getActiveCheckIn(): CheckInEntity? = null
        override fun getActiveCheckInFlow(): Flow<CheckInEntity?> = flowOf(null)
        override suspend fun getCheckInForSession(sessionId: Long): CheckInEntity? = null
        override fun getAllCompletedSessionsWithDetailsFlow(): Flow<List<SessionCalendarSummary>> = flowOf(emptyList())
        override suspend fun getAllCompletedSessionsWithDetails(): List<SessionCalendarSummary> = emptyList()
        override suspend fun deleteWorkoutSession(session: WorkoutSessionEntity) {}
        override suspend fun getAlternativesForExercise(exerciseId: Long): List<ExerciseAlternativeEntity> = emptyList()
        override suspend fun insertAlternative(alt: ExerciseAlternativeEntity): Long = 0
        override fun getOverrideForExerciseFlow(exerciseId: Long): Flow<ExerciseUserOverrideEntity?> = flowOf(null)
        override suspend fun getOverrideForExercise(exerciseId: Long): ExerciseUserOverrideEntity? = null
        override suspend fun getAllOverrides(): List<ExerciseUserOverrideEntity> = emptyList()
        override fun getAllOverridesFlow(): Flow<List<ExerciseUserOverrideEntity>> = flowOf(emptyList())
        override suspend fun insertOrUpdateOverride(override: ExerciseUserOverrideEntity) {}
        override suspend fun deleteOverride(override: ExerciseUserOverrideEntity) {}
        override suspend fun getMaxContentVersion(): Int? = null
        override suspend fun getCanonicalExercisesCount(): Int = exercises.size
        override suspend fun getExerciseSessionById(id: Long): ExerciseSessionEntity? = null
        override suspend fun getExplicitAlternatives(exerciseId: Long): List<ExerciseEntity> = emptyList()
        override suspend fun getAlternativesBySubstitutionGroup(exerciseId: Long): List<ExerciseEntity> = emptyList()
        override suspend fun getAlternativesByMovementPattern(exerciseId: Long): List<ExerciseEntity> = emptyList()
        override suspend fun getAlternativesByMuscle(exerciseId: Long): List<ExerciseEntity> = emptyList()
        override suspend fun updateExerciseSessionActualExercise(exerciseSessionId: Long, newExerciseId: Long, newName: String, reason: String) {}
        override suspend fun updateTemplateExercise(templateId: Long, oldExerciseId: Long, newExerciseId: Long) {}
        override suspend fun getTemplateExercisesWithDetails(templateId: Long): List<TemplateExerciseWithDetails> = emptyList()
        override fun getTemplateExercisesWithDetailsFlow(templateId: Long): Flow<List<TemplateExerciseWithDetails>> = flowOf(emptyList())
        override suspend fun insertTemplateExercise(templateExercise: WorkoutTemplateExerciseEntity) {}
        override suspend fun updateTemplateExerciseFull(templateExercise: WorkoutTemplateExerciseEntity) {}
        override suspend fun getAllTemplatesSync(): List<WorkoutTemplateEntity> = emptyList()
        override suspend fun getSessionById(id: Long): WorkoutSessionEntity? = null
        override suspend fun getExerciseSessionsForSession(sessionId: Long): List<ExerciseSessionEntity> = emptyList()
        override suspend fun deleteTemplateExercise(templateExercise: WorkoutTemplateExerciseEntity) {}
    }

    private class FakeAdapter(
        override val sourceName: String,
        private val itemsProvider: () -> List<ExternalExerciseDTO>
    ) : ExternalExerciseAdapter {
        override suspend fun fetchCatalog(limit: Int, offset: Int): List<ExternalExerciseDTO> {
            return itemsProvider().drop(offset).take(limit)
        }
    }

    @Test
    fun test1_InitialImport_EmptyDb_Creates1000CanonicalExercises() = runBlocking {
        val fakeDao = FakeWorkoutDao()
        val mockItems = (1..1000).map { i ->
            ExternalExerciseDTO(
                externalId = "ext_$i",
                name = "Exercise $i",
                bodyParts = listOf("chest"),
                targetMuscles = listOf("pectoral"),
                equipment = "barbell",
                instructions = listOf("Step 1", "Step 2")
            )
        }
        val adapter = FakeAdapter("EXERCISE_DB_V1") { mockItems }
        val syncManager = ExerciseSyncManager(fakeDao, listOf(adapter))

        val result = syncManager.synchronize(limit = 1000, offset = 0)

        assertEquals(1000, result.imported)
        assertEquals(0, result.updated)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        assertEquals(1000, fakeDao.exercises.size)
    }

    @Test
    fun test2_SecondSync_Produces0Duplicates() = runBlocking {
        val fakeDao = FakeWorkoutDao()
        val mockItems = (1..50).map { i ->
            ExternalExerciseDTO(
                externalId = "ext_$i",
                name = "Exercise $i",
                bodyParts = listOf("chest"),
                targetMuscles = listOf("pectoral"),
                equipment = "barbell",
                instructions = listOf("Step 1", "Step 2")
            )
        }
        val adapter = FakeAdapter("EXERCISE_DB_V1") { mockItems }
        val syncManager = ExerciseSyncManager(fakeDao, listOf(adapter))

        // First sync
        val sync1 = syncManager.synchronize(limit = 50, offset = 0)
        assertEquals(50, sync1.imported)

        // Second sync
        val sync2 = syncManager.synchronize(limit = 50, offset = 0)
        assertEquals(0, sync2.imported)
        assertEquals(50, sync2.updated)
        assertEquals(0, sync2.skipped)
        assertEquals(50, fakeDao.exercises.size)
    }

    @Test
    fun test3_CuratedExercise_PreservesCuratedContent() = runBlocking {
        val fakeDao = FakeWorkoutDao()
        // Insert a curated exercise into fake DB
        val curatedEntity = ExerciseEntity(
            id = 1,
            name = "Supino reto com barra",
            externalExerciseId = "ext_bench",
            instructions = "Instrução curada do aplicativo",
            executionTips = "Manter as escápulas aduzidas",
            commonMistakes = "Cotovelos muito abertos",
            isCurated = true,
            origin = "SYSTEM"
        )
        fakeDao.insertExercise(curatedEntity)

        val incomingItem = ExternalExerciseDTO(
            externalId = "ext_bench",
            name = "Supino reto com barra",
            instructions = listOf("Instructions from external API"),
            bodyParts = listOf("chest"),
            equipment = "barbell"
        )
        val adapter = FakeAdapter("EXERCISE_DB_V1") { listOf(incomingItem) }
        val syncManager = ExerciseSyncManager(fakeDao, listOf(adapter))

        val result = syncManager.synchronize(limit = 10, offset = 0)
        assertEquals(1, result.updated)

        val updatedEntity = fakeDao.getExerciseById(1)
        assertNotNull(updatedEntity)
        assertTrue(updatedEntity!!.isCurated)
        assertEquals("Instrução curada do aplicativo", updatedEntity.instructions)
        assertEquals("Manter as escápulas aduzidas", updatedEntity.executionTips)
        assertEquals("Cotovelos muito abertos", updatedEntity.commonMistakes)
    }

    @Test
    fun test4_UserCreatedExercise_IsNotAltered() = runBlocking {
        val fakeDao = FakeWorkoutDao()
        val userCreatedEntity = ExerciseEntity(
            id = 1,
            name = "Meu exercício personalizado",
            externalExerciseId = "ext_custom",
            isUserCreated = true,
            origin = "USER_CREATED",
            instructions = "Minha instrução personalizada"
        )
        fakeDao.insertExercise(userCreatedEntity)

        val incomingItem = ExternalExerciseDTO(
            externalId = "ext_custom",
            name = "Meu exercício personalizado",
            instructions = listOf("Tentativa de alterar via API")
        )
        val adapter = FakeAdapter("EXERCISE_DB_V1") { listOf(incomingItem) }
        val syncManager = ExerciseSyncManager(fakeDao, listOf(adapter))

        val result = syncManager.synchronize(limit = 10, offset = 0)
        assertEquals(1, result.skipped)

        val stored = fakeDao.getExerciseById(1)
        assertNotNull(stored)
        assertEquals("Minha instrução personalizada", stored!!.instructions)
        assertTrue(stored.isUserCreated)
    }

    @Test
    fun test5_ApiUnavailable_LocalCatalogContinuesWorking() = runBlocking {
        val fakeDao = FakeWorkoutDao()
        val localExercise = ExerciseEntity(
            id = 1,
            name = "Agachamento livre com barra",
            primaryMuscle = "Pernas",
            equipment = "Barra"
        )
        fakeDao.insertExercise(localExercise)

        val failingProvider = object : ExerciseApiProvider {
            override val providerType = com.example.data.remote.provider.ProviderType.V1_OSS
            override suspend fun fetchExternalCatalog(limit: Int, offset: Int) = NetworkResult.Offline
            override suspend fun searchExercises(query: String) = NetworkResult.Offline
            override suspend fun getExerciseById(id: String) = NetworkResult.Offline
            override suspend fun testConnection(query: String) = com.example.data.remote.NetworkTestResult.Failure(errorMessage = "API offline")
        }

        val localProvider = com.example.data.remote.provider.ExerciseLocalCacheProvider(fakeDao)
        val compositeProvider = CompositeExerciseApiProvider(failingProvider, failingProvider, localProvider)

        val searchResult = compositeProvider.searchExercises("Agachamento")
        assertTrue(searchResult is NetworkResult.Success)
        val data = (searchResult as NetworkResult.Success).data
        assertEquals(1, data.size)
        assertEquals("Agachamento livre com barra", data[0].name)
    }
}
