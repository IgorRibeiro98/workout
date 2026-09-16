package com.example.data.social

import com.example.data.local.XpTransactionDao
import com.example.data.local.XpTransactionEntity
import com.example.data.repository.XpTransactionRepositoryImpl
import com.example.domain.evolution.calculator.AchievementEvaluator
import com.example.domain.evolution.calculator.ConsistencyCalculator
import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.achievement.AchievementCatalog
import com.example.domain.evolution.model.achievement.AchievementCategory
import com.example.domain.evolution.model.achievement.AchievementEvaluationContext
import com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot
import com.example.domain.gamification.ConsistencyMilestoneEvaluator
import com.example.domain.gamification.GamificationEvents
import com.example.domain.gamification.XpRewardPolicy
import com.example.domain.gamification.mission.MissionEvaluationContext
import com.example.domain.gamification.mission.MissionEvaluator
import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.model.mission.MissionCatalog
import com.example.domain.gamification.model.mission.MissionCompletion
import com.example.domain.gamification.model.mission.MissionStatus
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A autoridade remota de gamificação (T19.2B/C) precisa dar o **mesmo** resultado que o motor
 * local daria sobre os mesmos fatos.
 *
 * ```text
 * Android   GamificationEventRecorder ao vivo:
 *           WORKOUT_COMPLETED → XpRewardPolicy
 *                             → ConsistencyMilestoneEvaluator → WEEKLY_GOAL_COMPLETED / STREAK_MILESTONE
 *                             → MissionEvaluator → MISSION_COMPLETED
 *           XpTransactionRepositoryImpl.calculateProgress → nível
 *           AchievementEvaluator → conquistas
 *
 * Backend   social-gamification.ts: a mesma consequência, em forma fechada, sobre contagens
 * ```
 *
 * Este teste **reproduz o motor local** — sessão a sessão, na ordem em que aconteceram, com as
 * mesmas `dedupeKey` — sobre cada caso de `contracts/social/v1/progress-projection.json`, e exige
 * o XP, o nível e as conquistas que a fixture declara. O backend lê a mesma fixture. Se alguém
 * mudar uma recompensa, uma missão ou um limiar de conquista de um lado só, o teste daquele lado
 * quebra — em vez de o perfil de um amigo mostrar um nível que o aparelho dele não reconhece.
 *
 * O que fica **fora** de propósito, e a fixture declara como `UNSUPPORTED_SERVER_SIDE`: XP de
 * recorde pessoal e conquistas de `PERFORMANCE`. O nível publicado é um limite inferior do local.
 */
class SocialProgressProjectionContractTest {

    private fun fixture(): JsonObject {
        val relative = "contracts/social/v1/progress-projection.json"
        val file = File(relative).takeIf { it.isFile }
            ?: File("../$relative").takeIf { it.isFile }
            ?: error("fixture compartilhada não encontrada: $relative")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun catalog(): JsonObject = fixture().getValue("catalog").jsonObject
    private fun cases(): List<JsonObject> = fixture().getValue("cases").jsonArray.map { it.jsonObject }

    // ------------------------------------------------------------------ o catálogo espelhado

    @Test
    fun `as versoes de politica e catalogo sao as que o servidor espelha`() {
        val catalog = catalog()
        assertEquals(XpRewardPolicy.VERSION, catalog.getValue("xpPolicyVersion").jsonPrimitive.content.toInt())
        assertEquals(MissionCatalog.CATALOG_VERSION, catalog.getValue("missionCatalogVersion").jsonPrimitive.content.toInt())
        assertEquals(AchievementCatalog.CATALOG_VERSION, catalog.getValue("achievementCatalogVersion").jsonPrimitive.content.toInt())
    }

    @Test
    fun `a matriz de XP cobre todo GamificationEventType com a recompensa real da politica`() {
        val sources = catalog().getValue("xpSources").jsonArray.map { it.jsonObject }
        val byEvent = sources.associateBy { it.getValue("event").jsonPrimitive.content }

        assertEquals(
            GamificationEventType.entries.map { it.name }.sorted(),
            byEvent.keys.sorted()
        )

        for (type in GamificationEventType.entries) {
            val declared = byEvent.getValue(type.name).getValue("xp")
            if (type == GamificationEventType.MISSION_COMPLETED) {
                // A recompensa da missão vem do próprio fato; a fixture aponta para o catálogo.
                assertEquals("MISSION_CATALOG", declared.jsonPrimitive.content)
                continue
            }
            val probe = GamificationEvent(type = type, timestamp = 0L)
            val reward = XpRewardPolicy.rewardFor(probe)?.amount
            val expected = if (declared is JsonNull) null else declared.jsonPrimitive.content.toInt()
            assertEquals("recompensa de ${type.name}", expected, reward)
        }

        // O recorde pessoal é a única origem com XP que o servidor não reconstrói.
        val unsupported = sources.filter {
            it.getValue("authority").jsonPrimitive.content == "UNSUPPORTED_SERVER_SIDE"
        }.map { it.getValue("event").jsonPrimitive.content }
        assertEquals(listOf("PERSONAL_RECORD_CREATED"), unsupported)
    }

    @Test
    fun `as missoes espelhadas sao o catalogo local, id a id`() {
        val missions = catalog().getValue("missions").jsonArray.map { it.jsonObject }
        assertEquals(MissionCatalog.DEFINITIONS.size, missions.size)
        MissionCatalog.DEFINITIONS.sortedBy { it.order }.zip(missions).forEach { (local, remote) ->
            assertEquals(local.id, remote.getValue("id").jsonPrimitive.content)
            assertEquals(local.type.name, remote.getValue("type").jsonPrimitive.content)
            assertEquals(local.rewardXp, remote.getValue("rewardXp").jsonPrimitive.content.toInt())
            val remoteTarget = remote.getValue("target")
            assertEquals(local.target, if (remoteTarget is JsonNull) null else remoteTarget.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `as conquistas espelhadas sao o catalogo local, e so PERFORMANCE fica sem autoridade remota`() {
        val achievements = catalog().getValue("achievements").jsonArray.map { it.jsonObject }
        assertEquals(AchievementCatalog.DEFINITIONS.size, achievements.size)
        AchievementCatalog.DEFINITIONS.zip(achievements).forEach { (local, remote) ->
            assertEquals(local.id, remote.getValue("id").jsonPrimitive.content)
            assertEquals(local.category.name, remote.getValue("category").jsonPrimitive.content)
            assertEquals(local.target, remote.getValue("target").jsonPrimitive.content.toInt())
            val authority = remote.getValue("authority").jsonPrimitive.content
            val expected = if (local.category == AchievementCategory.PERFORMANCE) "UNSUPPORTED_SERVER_SIDE" else "RECONSTRUCTABLE"
            assertEquals("autoridade de ${local.id}", expected, authority)
        }
    }

    @Test
    fun `a curva de nivel e a de XpTransactionRepositoryImpl, ponto a ponto`() = runBlocking {
        val samples = catalog().getValue("levelCurve").jsonObject.getValue("samples").jsonArray.map { it.jsonObject }
        for (sample in samples) {
            val totalXp = sample.getValue("totalXp").jsonPrimitive.content.toInt()
            val progress = XpTransactionRepositoryImpl(FakeXpDao(totalXp)).getUserProgress().first()
            assertEquals("nível com $totalXp XP", sample.getValue("level").jsonPrimitive.content.toInt(), progress.currentLevel)
            assertEquals("XP no nível com $totalXp XP", sample.getValue("currentLevelXp").jsonPrimitive.content.toInt(), progress.currentLevelXp)
            assertEquals("XP para o próximo com $totalXp XP", sample.getValue("xpForNextLevel").jsonPrimitive.content.toInt(), progress.xpForNextLevel)
        }
        Unit
    }

    // ------------------------------------------------------------------ o motor local sobre os casos

    @Test
    fun `o motor local ao vivo produz o XP, o nivel e as conquistas que a fixture declara`() = runBlocking {
        for (case in cases()) {
            val name = case.getValue("name").jsonPrimitive.content
            val zone = ZoneId.of(case.getValue("timeZone").jsonPrimitive.content)
            val nowMillis = case.getValue("nowMillis").jsonPrimitive.content.toLong()
            val consistency = case.getValue("consistency").takeUnless { it is JsonNull }?.jsonObject
            val expected = case.getValue("expected").jsonObject
            val sessions = case.getValue("completedSessions").jsonArray
                .map { it.jsonObject.getValue("millis").jsonPrimitive.content.toLong() }
                .sorted()
            val measurements = case.getValue("bodyMeasurements").jsonArray
                .map { it.jsonObject.getValue("millis").jsonPrimitive.content.toLong() }

            val trackingStartedAt = consistency?.getValue("trackingStartedAtEpochDay")?.jsonPrimitive?.content?.toLong()
            val goals = consistency?.getValue("weeklyGoals")?.jsonArray?.map { it.jsonObject }?.map {
                WeeklyGoalSnapshot(
                    effectiveFromWeek = it.getValue("weekStartEpochDay").jsonPrimitive.content.toLong(),
                    goal = it.getValue("goal").jsonPrimitive.content.toInt()
                )
            } ?: emptyList()

            val engine = LiveEngine(zone, goals, trackingStartedAt)
            sessions.forEachIndexed { index, startedAt -> engine.completeWorkout(sessionId = index + 1L, startedAt = startedAt) }

            val referenceDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            val weeks = ConsistencyCalculator.calculateWeeklyConsistencies(
                timestamps = sessions,
                goalSnapshots = goals,
                referenceDate = referenceDate,
                trackingStartedAtEpochDay = trackingStartedAt,
                zoneId = zone
            )
            val progress = ConsistencyCalculator.calculateProgress(weeks, referenceDate)

            val expectedXp = expected.getValue("xp").takeUnless { it is JsonNull }?.jsonObject
            if (expectedXp != null) {
                assertEquals("[$name] XP total do motor local", expectedXp.getValue("total").jsonPrimitive.content.toInt(), engine.totalXp)
                val level = XpTransactionRepositoryImpl(FakeXpDao(engine.totalXp)).getUserProgress().first().currentLevel
                assertEquals(
                    "[$name] nível",
                    expected.getValue("level").jsonObject.getValue("level").jsonPrimitive.content.toInt(),
                    level
                )
                assertEquals(
                    "[$name] sequência atual",
                    expected.getValue("currentStreakWeeks").jsonPrimitive.content.toInt(),
                    progress.currentStreakWeeks
                )
            }

            // As conquistas: o avaliador local, restrito às categorias que o servidor afirma.
            val evaluations = AchievementEvaluator.evaluate(
                AchievementEvaluationContext(
                    completedWorkoutsCount = sessions.size,
                    completedWorkoutsTimestamps = sessions,
                    gamificationEvents = engine.events,
                    measurements = measurements.mapIndexed { index, date -> measurement(index + 1L, date) },
                    consistencyProgress = if (consistency != null) progress else null
                )
            )
            val remoteCategories = setOf(AchievementCategory.TRAINING, AchievementCategory.CONSISTENCY, AchievementCategory.BODY)
            val earnedLocally = evaluations
                .filter { it.eligibleForUnlock && it.definition.category in remoteCategories }
                .filter { consistency != null || it.definition.category != AchievementCategory.CONSISTENCY }
                .map { it.definition.id }
            val expectedEarned = expected.getValue("earnedAchievementIds").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("[$name] conquistas verificáveis", expectedEarned, earnedLocally)

            // E nenhuma conquista de recorde nasce sem evento de recorde — o servidor também não a afirma.
            assertTrue(evaluations.none { it.eligibleForUnlock && it.definition.category == AchievementCategory.PERFORMANCE })
        }
        Unit
    }

    /**
     * O `GamificationEventRecorder`, em miniatura e sem Room: a mesma sequência de fatos que o
     * app registra ao concluir um treino, com as mesmas `dedupeKey` e a mesma política de XP.
     */
    private class LiveEngine(
        private val zone: ZoneId,
        private val goals: List<WeeklyGoalSnapshot>,
        private val trackingStartedAt: Long?
    ) {
        val events = mutableListOf<GamificationEvent>()
        private val dedupe = mutableSetOf<String>()
        private val timestamps = mutableListOf<Long>()
        var totalXp = 0
            private set

        private fun record(event: GamificationEvent): Boolean {
            if (!dedupe.add(event.dedupeKey)) return false
            events += event
            totalXp += XpRewardPolicy.rewardFor(event)?.amount ?: 0
            return true
        }

        fun completeWorkout(sessionId: Long, startedAt: Long) {
            val finishedAt = startedAt + 3_600_000
            timestamps += startedAt
            record(GamificationEvents.workoutCompleted(sessionId = sessionId, timestamp = finishedAt))
            if (timestamps.size == 1) {
                record(GamificationEvents.firstWorkoutCompleted(sessionId = sessionId, timestamp = finishedAt))
            }

            // `GamificationEventRecorder.evaluateConsistency`: a meta padrão é a vigente; os
            // snapshots decidem semana a semana.
            ConsistencyMilestoneEvaluator.evaluate(
                workoutTimestamps = timestamps.toList(),
                weeklyGoal = goals.lastOrNull()?.goal ?: 3,
                goalSnapshots = goals,
                referenceTimestamp = finishedAt,
                trackingStartedAtEpochDay = trackingStartedAt,
                zoneId = zone
            ).forEach { record(it) }

            // `MissionRepositoryImpl.evaluateAndComplete`, ao vivo, no instante da conclusão.
            val referenceDate = Instant.ofEpochMilli(finishedAt).atZone(zone).toLocalDate()
            val weeks = ConsistencyCalculator.calculateWeeklyConsistencies(
                timestamps = timestamps.toList(),
                goalSnapshots = goals,
                referenceDate = referenceDate,
                trackingStartedAtEpochDay = trackingStartedAt,
                zoneId = zone
            )
            val completions = events
                .filter { it.type == GamificationEventType.MISSION_COMPLETED }
                .map {
                    MissionCompletion(
                        missionId = it.metadata.getValue("missionId"),
                        periodKey = it.metadata.getValue("missionPeriodKey"),
                        completedAt = it.timestamp,
                        target = it.metadata.getValue("missionTarget").toInt(),
                        rewardXp = it.metadata.getValue("missionRewardXp").toInt()
                    )
                }
            MissionEvaluator.evaluate(
                MissionEvaluationContext(
                    completedWorkoutTimestamps = timestamps.toList(),
                    weeklyConsistencies = weeks,
                    completions = completions,
                    referenceTimestamp = finishedAt,
                    zoneId = zone
                )
            ).filter { it.status == MissionStatus.COMPLETED && it.completedAt == null }.forEach { mission ->
                val definition = MissionCatalog.getDefinition(mission.missionId) ?: return@forEach
                record(
                    GamificationEvents.missionCompleted(
                        missionId = mission.missionId,
                        periodKey = mission.periodKey,
                        target = mission.target,
                        rewardXp = definition.rewardXp,
                        catalogVersion = MissionCatalog.CATALOG_VERSION,
                        timestamp = finishedAt
                    )
                )
            }
        }
    }

    private fun measurement(id: Long, date: Long) = BodyMeasurement(
        id = id, date = date, weightKg = 80f, heightCm = null, waistCm = null, abdomenCm = null,
        chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null,
        leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null
    )

    /** Só o total importa para a curva; o resto do DAO não é exercitado. */
    private class FakeXpDao(private val total: Int) : XpTransactionDao {
        override suspend fun insertTransaction(transaction: XpTransactionEntity): Long = 1L
        override suspend fun insertTransactions(transactions: List<XpTransactionEntity>): List<Long> = emptyList()
        override fun getAllTransactions(): Flow<List<XpTransactionEntity>> = flowOf(emptyList())
        override fun getTotalXp(): Flow<Int?> = flowOf(total)
        override suspend fun hasTransactionForEvent(eventId: String): Boolean = false
        override suspend fun deleteAllTransactions() = Unit
        override suspend fun replaceAllTransactions(transactions: List<XpTransactionEntity>) = Unit
    }
}
