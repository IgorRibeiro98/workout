package com.example.data.social

import com.example.domain.evolution.calculator.ConsistencyCalculator
import com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sequência semanal de consistência tem **duas** implementações desde a T19.2A — e elas precisam
 * concordar.
 *
 * ```text
 * Android   ConsistencyCalculator.calculateWeeklyConsistencies + calculateProgress   (a tela do dono)
 * Backend   social-consistency.ts                                                    (o que os amigos veem)
 * ```
 *
 * A amarra é a fixture compartilhada `contracts/social/v1/consistency-streak.json`, lida por este
 * teste e por `backend/test/social-progress-v2.spec.ts`. Cada caso traz as sessões, a meta por
 * semana, o início do acompanhamento e o "agora" — e o resultado esperado, semana a semana. Uma
 * mudança unilateral em qualquer dos dois lados quebra o teste de quem mudou.
 *
 * **Este teste não prova que o Android calcula o perfil social.** Ele não calcula: a projeção é do
 * servidor, sobre sessões sincronizadas e parâmetros declarados. O que ele prova é que a regra que o
 * servidor implementou é exatamente a regra que o domínio do Spark já tinha.
 */
class SocialConsistencyContractTest {

    private fun fixture(): JsonObject {
        val relative = "contracts/social/v1/consistency-streak.json"
        val file = File(relative).takeIf { it.isFile }
            ?: File("../$relative").takeIf { it.isFile }
            ?: error("fixture compartilhada não encontrada: $relative")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun cases(): List<JsonObject> =
        fixture().getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun `a fixture existe e tem casos — senao os testes abaixo nao provam nada`() {
        assertTrue("a fixture precisa cobrir os casos de fronteira", cases().size >= 6)
    }

    @Test
    fun `cada caso da fixture produz as mesmas semanas e a mesma sequencia no dominio do Spark`() {
        for (case in cases()) {
            val name = case.getValue("name").jsonPrimitive.content
            val zone = ZoneId.of(case.getValue("timeZone").jsonPrimitive.content)
            val nowMillis = case.getValue("nowMillis").jsonPrimitive.content.toLong()
            val consistency = case.getValue("consistency").jsonObject
            val trackingStartedAt = consistency.getValue("trackingStartedAtEpochDay").jsonPrimitive.content.toLong()
            val goals = consistency.getValue("weeklyGoals").jsonArray.map { it.jsonObject }.map {
                WeeklyGoalSnapshot(
                    effectiveFromWeek = it.getValue("weekStartEpochDay").jsonPrimitive.content.toLong(),
                    goal = it.getValue("goal").jsonPrimitive.content.toInt()
                )
            }
            val timestamps = case.getValue("completedSessions").jsonArray.map {
                it.jsonObject.getValue("millis").jsonPrimitive.content.toLong()
            }
            val referenceDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

            // A regra do domínio, exatamente como `ConsistencyRepositoryImpl` a chama para a tela.
            val weeks = ConsistencyCalculator.calculateWeeklyConsistencies(
                timestamps = timestamps,
                goalSnapshots = goals,
                referenceDate = referenceDate,
                trackingStartedAtEpochDay = trackingStartedAt,
                zoneId = zone
            )
            val progress = ConsistencyCalculator.calculateProgress(weeks, referenceDate)

            val expected = case.getValue("expected").jsonObject
            val expectedWeeks = expected.getValue("weeks").jsonArray.map { it.jsonObject }

            assertEquals("[$name] número de semanas", expectedWeeks.size, weeks.size)
            weeks.zip(expectedWeeks).forEach { (week, json) ->
                val label = "[$name] semana ${week.weekStartEpochDay}"
                assertEquals(label, json.getValue("weekStartEpochDay").jsonPrimitive.content.toLong(), week.weekStartEpochDay)
                assertEquals(label, json.getValue("goal").jsonPrimitive.content.toInt(), week.goal)
                assertEquals(label, json.getValue("completedWorkouts").jsonPrimitive.content.toInt(), week.completedWorkouts)
                assertEquals(label, json.getValue("status").jsonPrimitive.content, week.status.name)
            }
            assertEquals(
                "[$name] sequência atual",
                expected.getValue("currentStreakWeeks").jsonPrimitive.content.toInt(),
                progress.currentStreakWeeks
            )
            assertEquals(
                "[$name] maior sequência",
                expected.getValue("longestStreakWeeks").jsonPrimitive.content.toInt(),
                progress.longestStreakWeeks
            )
        }
    }

    @Test
    fun `o epoch day de uma segunda-feira e o que a fixture assume`() {
        // 1970-01-05 é segunda-feira: epoch day 4. É a âncora que `social-consistency.ts` usa para
        // decidir "segunda-feira" sem calendário — e ela precisa bater com `java.time`.
        val monday = java.time.LocalDate.ofEpochDay(4)
        assertEquals(java.time.DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(monday, ConsistencyCalculator.weekStart(monday.plusDays(6)))
    }
}
