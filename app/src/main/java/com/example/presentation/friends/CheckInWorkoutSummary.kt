package com.example.presentation.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.engine.MuscleVisualResolver
import com.example.domain.social.WorkoutSocialExercise
import com.example.domain.social.WorkoutSocialSet
import com.example.domain.social.WorkoutSocialSummary
import com.example.ui.theme.Lime400
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val CHECKIN_WORKOUT_SUMMARY_DESCRIPTION = "Resumo do treino"

/** Quantos exercícios o card do Feed mostra antes de "+N exercícios" (o detalhe mostra todos). */
const val COMPACT_EXERCISE_LIMIT = 3

/**
 * O resumo do treino de um check-in (T19.H3 §28–§31/§59).
 *
 * ```text
 * Superiores A
 * 19:57 · 55 min
 * 2 exercícios · 6 séries · Volume 4.560 kg
 *
 * Supino reto
 * 80 kg × 10 · 80 kg × 10 · 80 kg × 10
 * ```
 *
 * ## Ela desenha o que chegou — e só
 *
 * Não há interruptor lido aqui, não há `if (autor compartilha carga)`. O servidor já tirou do JSON
 * o que o autor não escolheu (§35), então cada linha existe ou não existe conforme o dado veio.
 * Uma tela que "escondesse" campos seria a prova de que eles chegaram.
 *
 * [compact] é o card do Feed e do Squad: até [COMPACT_EXERCISE_LIMIT] exercícios e as séries numa
 * linha só. O detalhe da publicação mostra tudo, série por série.
 */
@Composable
fun CheckInWorkoutSummaryView(
    summary: WorkoutSocialSummary,
    compact: Boolean,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault()
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = CHECKIN_WORKOUT_SUMMARY_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        summary.name?.let { name ->
            Text(
                text = name,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        CheckInSummaryFormat.whenLine(summary, zone)?.let { line ->
            Text(text = line, color = TextSecondary, fontSize = 13.sp)
        }
        CheckInSummaryFormat.countsLine(summary)?.let { line ->
            Text(text = line, color = Lime400, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        val exercises = summary.exercises.orEmpty()
        val shown = if (compact) exercises.take(COMPACT_EXERCISE_LIMIT) else exercises
        shown.forEach { exercise -> ExerciseBlock(exercise, compact) }
        if (compact && exercises.size > shown.size) {
            Text(
                text = CheckInSummaryFormat.moreExercises(exercises.size - shown.size),
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ExerciseBlock(exercise: WorkoutSocialExercise, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = CheckInSummaryFormat.exerciseTitle(exercise),
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val sets = exercise.sets ?: return@Column
        if (compact) {
            Text(
                text = sets.joinToString(" · ") { CheckInSummaryFormat.set(it) },
                color = TextSecondary,
                fontSize = 13.sp
            )
        } else {
            sets.forEachIndexed { index, set ->
                Text(
                    text = "Série ${index + 1} · ${CheckInSummaryFormat.set(set)}",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * A formatação do resumo, sem Compose — é ela que os testes afirmam.
 *
 * `pt-BR` explícito nos números: "4.560 kg" e "22,5 kg" são o que a pessoa lê no resto do app, e
 * depender do `Locale` do aparelho faria o mesmo check-in aparecer "4,560 kg" num telefone em
 * inglês.
 */
object CheckInSummaryFormat {

    private val ptBr: Locale = Locale.forLanguageTag("pt-BR")
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", ptBr)

    /** "19:57 · 55 min" — o horário, a duração, ou os dois, conforme vieram. */
    fun whenLine(summary: WorkoutSocialSummary, zone: ZoneId): String? {
        val parts = listOfNotNull(
            summary.startedAt?.let { clock.format(Instant.ofEpochMilli(it).atZone(zone)) },
            summary.durationSeconds?.let { duration(it) }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** "2 exercícios · 6 séries · Volume 4.560 kg". */
    fun countsLine(summary: WorkoutSocialSummary): String? {
        val parts = listOfNotNull(
            summary.exerciseCount?.let { plural(it, "exercício", "exercícios") },
            summary.completedSetCount?.let { plural(it, "série", "séries") },
            summary.totalVolumeKg?.let { "Volume ${kilograms(it)}" }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** "55 min", "1 h", "1 h 05 min". Menos de um minuto ainda é "1 min": o treino aconteceu. */
    fun duration(seconds: Long): String {
        val minutes = (seconds / 60).coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "$minutes min"
            rest == 0L -> "$hours h"
            else -> "$hours h ${rest.toString().padStart(2, '0')} min"
        }
    }

    /** "80 kg × 10", "10 reps", "60 s", "20 kg × 45 s". */
    fun set(set: WorkoutSocialSet): String {
        val amount = when {
            set.durationSeconds != null -> "${set.durationSeconds} s"
            set.reps != null -> if (set.weightKg != null) "${set.reps}" else "${set.reps} reps"
            else -> "—"
        }
        return set.weightKg?.let { "${kilograms(it)} × $amount" } ?: amount
    }

    /** "Supino reto · Peito" — o grupo muscular pelo nome que o app usa. */
    fun exerciseTitle(exercise: WorkoutSocialExercise): String {
        val muscle = exercise.primaryMuscle?.let { MuscleVisualResolver.getDisplayName(it) }
            ?.takeIf { it.isNotBlank() }
        return if (muscle == null) exercise.name else "${exercise.name} · $muscle"
    }

    fun moreExercises(count: Int): String =
        if (count == 1) "+1 exercício — abra a publicação" else "+$count exercícios — abra a publicação"

    /** "80 kg", "22,5 kg", "22,25 kg", "4.560 kg". */
    fun kilograms(value: Double): String {
        val format = DecimalFormat("#,##0.##", DecimalFormatSymbols(ptBr))
        return "${format.format(value)} kg"
    }

    private fun plural(count: Int, singular: String, plural: String): String =
        "$count ${if (count == 1) singular else plural}"
}
