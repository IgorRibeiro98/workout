package com.example.data.social

import com.example.domain.evolution.calculator.ConsistencyCalculator
import com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
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
 * A semana canônica do Spark tem **duas** implementações agora — e elas precisam concordar (T17.2).
 *
 * ```text
 * Android   ConsistencyCalculator.weekStart      java.time, segunda-feira da data local
 * Backend   canonicalWeekWindow                  Intl, a mesma segunda-feira
 * ```
 *
 * A contagem de "treinos desta semana" que um amigo vê é calculada **no servidor**, e a que o dono
 * vê no próprio Perfil é calculada **no aparelho**. Se as duas discordarem, o defeito aparece como
 * "o perfil do meu amigo mostra um número diferente do que ele vê" — o pior tipo de bug para
 * depurar, porque nenhum dos dois lados está obviamente errado.
 *
 * A amarra é a fixture compartilhada `contracts/social/v1/weekly-window.json`, lida por este teste
 * e por `backend/test/social-progress.spec.ts`. Uma mudança unilateral quebra o teste de quem
 * mudou — em vez de virar um número errado na tela de outra pessoa.
 *
 * **Este teste não prova que o Android calcula o perfil social.** Ele não calcula, e não deve: a
 * projeção é do servidor. O que ele prova é que a regra que o servidor implementou é exatamente a
 * regra que o domínio do Spark já tinha.
 */
class SocialWeekWindowContractTest {

    /**
     * A fixture, lida com `kotlinx.serialization`.
     *
     * `org.json` não serve: no teste unitário do Android ele é um stub que lança "not mocked", e
     * usá-lo exigiria Robolectric — um emulador de framework para ler um arquivo de texto. É a
     * mesma escolha do `FriendCodeContractTest`.
     */
    private fun fixture(): JsonObject {
        val relative = "contracts/social/v1/weekly-window.json"
        val file = File(relative).takeIf { it.isFile }
            ?: File("../$relative").takeIf { it.isFile }
            ?: error("fixture compartilhada não encontrada: $relative")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun cases(): List<JsonObject> =
        fixture().getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun `a fixture existe e tem casos — senao os testes abaixo nao provam nada`() {
        assertTrue("a fixture precisa cobrir mais de um fuso", cases().size >= 4)
    }

    @Test
    fun `o inicio da semana da fixture e o weekStart canonico do dominio`() {
        for (case in cases()) {
            val name = case.getValue("name").jsonPrimitive.content
            val zone = ZoneId.of(case.getValue("timeZone").jsonPrimitive.content)
            val nowMillis = case.getValue("nowMillis").jsonPrimitive.content.toLong()

            // A regra do domínio, sem intermediário: a segunda-feira da semana que contém a data
            // **local** do instante.
            val localDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            val monday = ConsistencyCalculator.weekStart(localDate)

            val expectedStart = case.getValue("weekStartMillis").jsonPrimitive.content.toLong()
            val expectedEnd = case.getValue("weekEndMillis").jsonPrimitive.content.toLong()

            assertEquals(
                "$name: início da semana",
                expectedStart,
                monday.atStartOfDay(zone).toInstant().toEpochMilli()
            )
            assertEquals(
                // O fim é a **meia-noite da segunda seguinte**, e não "início + 7×24h": numa semana
                // com virada de horário de verão as duas diferem em uma hora, e a diferença
                // apareceria como um treino de domingo à noite contado na semana errada.
                "$name: fim da semana",
                expectedEnd,
                monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
            )
            assertEquals(
                "$name: duração da semana",
                case.getValue("weekDurationHours").jsonPrimitive.content.toDouble(),
                (expectedEnd - expectedStart) / 3_600_000.0,
                0.0001
            )
        }
    }

    @Test
    fun `a contagem semanal do ConsistencyCalculator bate com a esperada pelo servidor`() {
        for (case in cases()) {
            val name = case.getValue("name").jsonPrimitive.content
            val zone = ZoneId.of(case.getValue("timeZone").jsonPrimitive.content)
            val nowMillis = case.getValue("nowMillis").jsonPrimitive.content.toLong()
            val referenceDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

            val timestamps = case.getValue("completedSessions").jsonArray
                .map { it.jsonObject.getValue("millis").jsonPrimitive.content.toLong() }

            // A meta e o início do acompanhamento não decidem a **contagem** da semana corrente —
            // eles decidem status e sequência, que são outra pergunta. Aqui existem só para o
            // calculador ter contexto suficiente para produzir a semana.
            val trackingStart = Instant.ofEpochMilli(timestamps.min())
                .atZone(zone)
                .toLocalDate()
                .minusWeeks(8)
                .toEpochDay()

            val weeks = ConsistencyCalculator.calculateWeeklyConsistencies(
                timestamps = timestamps,
                goalSnapshots = listOf(
                    WeeklyGoalSnapshot(
                        effectiveFromWeek = LocalDate.ofEpochDay(trackingStart)
                            .with(DayOfWeek.MONDAY)
                            .toEpochDay(),
                        goal = 3
                    )
                ),
                defaultGoal = 3,
                referenceDate = referenceDate,
                trackingStartedAtEpochDay = trackingStart,
                zoneId = zone
            )

            val currentMonday = ConsistencyCalculator.weekStart(referenceDate).toEpochDay()
            val currentWeek = weeks.firstOrNull { it.weekStartEpochDay == currentMonday }

            assertEquals(
                "$name: treinos da semana corrente",
                case.getValue("expectedWeeklyWorkoutCount").jsonPrimitive.content.toInt(),
                currentWeek?.completedWorkouts ?: -1
            )
        }
    }

    @Test
    fun `a fixture declara que apenas sessoes COMPLETED contam`() {
        // A regra vive no schema do servidor (`workoutSessionSchema` só aceita `COMPLETED`) e na
        // consulta da projeção. A fixture a declara para que os dois lados leiam a mesma frase.
        val rule = fixture().getValue("rule").jsonObject
        assertTrue(rule.getValue("counts").jsonPrimitive.content.contains("COMPLETED"))

        val never = rule.getValue("neverCounts").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("PLANNED", "IN_PROGRESS", "PAUSED", "CANCELLED"), never)
    }
}
