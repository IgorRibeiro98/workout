package com.example.domain.engine

import com.example.data.datastore.RestCompletionBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A única conta de "quanto falta"/"quanto passou" do descanso (T19.9) — sempre derivada de
 * `targetTimeMs - nowMs`, nunca de um contador decrementado por segundo.
 */
class RestCompletionCalculatorTest {

    @Test
    fun `descanso nao expirado conta os segundos restantes`() {
        val target = 10_000L
        val now = 7_000L // faltam 3s
        val reading = RestCompletionCalculator.read(target, now, RestCompletionBehavior.AUTO_ADVANCE)

        assertEquals(3L, reading.remainingSeconds)
        assertEquals(0L, reading.overtimeSeconds)
        assertFalse(reading.isExpired)
        assertFalse(reading.isOvertime)
    }

    @Test
    fun `now igual a targetTime e a fronteira - mostra 00-00, nao -00-00`() {
        val target = 10_000L
        val reading = RestCompletionCalculator.read(target, target, RestCompletionBehavior.MANUAL_OVERTIME)

        assertEquals(0L, reading.remainingSeconds)
        assertEquals(0L, reading.overtimeSeconds)
        assertTrue(reading.isExpired)
        // Expirou, mas ainda não decorreu um segundo cheio: a fronteira mostra 00:00, nunca -00:00.
        assertFalse(reading.isOvertime)
    }

    @Test
    fun `um segundo cheio apos o alvo ja e overtime`() {
        val target = 10_000L
        val now = 11_000L // exatamente 1s depois
        val reading = RestCompletionCalculator.read(target, now, RestCompletionBehavior.MANUAL_OVERTIME)

        assertEquals(1L, reading.overtimeSeconds)
        assertTrue(reading.isOvertime)
    }

    @Test
    fun `overtime cresce a partir de agora menos restEndsAt`() {
        val target = 10_000L
        val now = 27_000L // 17s além do previsto
        val reading = RestCompletionCalculator.read(target, now, RestCompletionBehavior.MANUAL_OVERTIME)

        assertEquals(0L, reading.remainingSeconds)
        assertEquals(17L, reading.overtimeSeconds)
        assertTrue(reading.isExpired)
        assertTrue(reading.isOvertime)
    }

    @Test
    fun `AUTO_ADVANCE nunca marca overtime mesmo apos expirar`() {
        val target = 10_000L
        val now = 40_000L
        val reading = RestCompletionCalculator.read(target, now, RestCompletionBehavior.AUTO_ADVANCE)

        assertTrue(reading.isExpired)
        assertFalse(reading.isOvertime)
        // O tempo decorrido continua computável (para quem quiser ler), só não vira UI de overtime.
        assertEquals(30L, reading.overtimeSeconds)
    }

    @Test
    fun `remainingSeconds nunca fica negativo mesmo bem depois do fim`() {
        val target = 10_000L
        val now = 999_000L
        val reading = RestCompletionCalculator.read(target, now, RestCompletionBehavior.AUTO_ADVANCE)

        assertEquals(0L, reading.remainingSeconds)
    }

    @Test
    fun `shouldAutoAdvance so e verdadeiro para AUTO_ADVANCE`() {
        assertTrue(RestCompletionCalculator.shouldAutoAdvance(RestCompletionBehavior.AUTO_ADVANCE))
        assertFalse(RestCompletionCalculator.shouldAutoAdvance(RestCompletionBehavior.MANUAL_OVERTIME))
    }

    @Test
    fun `alvo no passado ja nasce expirado, sem exigir uma volta do loop`() {
        // O caso de um alvo "instantaneo" (restDuration = 0, ou um resto de estado restaurado) —
        // a tela nao deve mostrar overtime so por causa de um alvo que ja nasceu vencido; quem
        // decide isso e o `hasCountedDown` da UI, mas a leitura em si precisa refletir a realidade.
        val target = 1_000L
        val now = 1_000L
        val reading = RestCompletionCalculator.read(target, now, RestCompletionBehavior.MANUAL_OVERTIME)
        assertTrue(reading.isExpired)
    }
}
