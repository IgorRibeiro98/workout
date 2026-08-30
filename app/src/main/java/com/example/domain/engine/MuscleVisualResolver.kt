package com.example.domain.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.ui.theme.Emerald500
import com.example.ui.theme.Lime400
import java.text.Normalizer

enum class MuscleGroup(
    val displayName: String,
    val color: Color
) {
    CHEST("Peitoral", Color(0xFF60A5FA)),         // Blue
    BACK("Costas", Color(0xFF34D399)),           // Emerald
    SHOULDERS("Ombros", Color(0xFFFBBF24)),       // Amber
    QUADS("Quadríceps", Color(0xFFA3E635)),       // Lime
    HAMSTRINGS("Posterior", Color(0xFFF87171)),   // Red/Coral
    GLUTES("Glúteos", Color(0xFFFB923C)),         // Orange
    CALVES("Panturrilhas", Color(0xFFE879F9)),    // Purple
    BICEPS("Bíceps", Color(0xFF38BDF8)),         // Sky
    TRICEPS("Tríceps", Color(0xFF818CF8)),       // Indigo
    CORE("Abdômen", Color(0xFF2DD4BF)),          // Teal
    FOREARMS("Antebraço", Color(0xFFA78BFA)),     // Violet
    TRAPS("Trapézio", Color(0xFFF472B6)),        // Pink
    CARDIO("Cardio", Color(0xFFFACC15)),         // Yellow
    FULL_BODY("Geral", Color(0xFF94A3B8));       // Slate

    val icon: ImageVector
        get() = when (this) {
            CHEST -> Icons.Default.FitnessCenter
            BACK -> Icons.Default.Shield
            SHOULDERS -> Icons.Default.SportsGymnastics
            QUADS, HAMSTRINGS, GLUTES, CALVES -> Icons.Default.DirectionsRun
            BICEPS, TRICEPS, FOREARMS, TRAPS -> Icons.Default.FitnessCenter
            CORE, CARDIO, FULL_BODY -> Icons.Default.Accessibility
        }
}

object MuscleVisualResolver {

    private fun stripAccents(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "").lowercase().trim()
    }

    fun resolveGroup(rawMuscle: String?): MuscleGroup {
        if (rawMuscle.isNullOrBlank()) return MuscleGroup.FULL_BODY
        val clean = stripAccents(rawMuscle)

        return when {
            clean.contains("peit") || clean.contains("chest") || clean.contains("peitoral") -> MuscleGroup.CHEST
            clean.contains("ombro") || clean.contains("deltoide") || clean.contains("shoulder") -> MuscleGroup.SHOULDERS
            clean.contains("costa") || clean.contains("dorsal") || clean.contains("latis") || clean.contains("lats") || clean.contains("back") -> MuscleGroup.BACK
            clean.contains("quadriceps") || clean.contains("coxa anterior") || clean.contains("quad") -> MuscleGroup.QUADS
            clean.contains("posterior") || clean.contains("isquiotibial") || clean.contains("hamstring") -> MuscleGroup.HAMSTRINGS
            clean.contains("gluteo") || clean.contains("glute") || clean.contains("gluteus") -> MuscleGroup.GLUTES
            clean.contains("panturrilha") || clean.contains("soleo") || clean.contains("gastrocnemio") || clean.contains("calf") || clean.contains("calves") -> MuscleGroup.CALVES
            clean.contains("biceps") || clean.contains("braquial") -> MuscleGroup.BICEPS
            clean.contains("triceps") -> MuscleGroup.TRICEPS
            clean.contains("abdomen") || clean.contains("core") || clean.contains("abdominal") || clean.contains("obliquo") -> MuscleGroup.CORE
            clean.contains("antebraco") || clean.contains("forearm") || clean.contains("pegada") -> MuscleGroup.FOREARMS
            clean.contains("trapezio") || clean.contains("traps") -> MuscleGroup.TRAPS
            clean.contains("cardio") || clean.contains("aerobico") -> MuscleGroup.CARDIO
            else -> MuscleGroup.FULL_BODY
        }
    }

    fun getDisplayName(rawMuscle: String?): String {
        return resolveGroup(rawMuscle).displayName
    }

    fun getPredominantMuscles(rawMuscles: List<String?>): List<String> {
        if (rawMuscles.isEmpty()) return emptyList()
        val groups = rawMuscles.map { resolveGroup(it) }.filter { it != MuscleGroup.FULL_BODY }
        if (groups.isEmpty()) return emptyList()
        
        return groups.groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key.displayName }
    }
}
