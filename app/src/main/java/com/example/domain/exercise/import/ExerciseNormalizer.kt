package com.example.domain.exercise.import

object ExerciseNormalizer {

    fun normalizeName(rawName: String): String {
        val trimmed = cleanText(rawName).lowercase()
        return when (trimmed) {
            "bench press", "barbell bench press" -> "Supino reto com barra"
            "incline bench press", "incline barbell bench press" -> "Supino inclinado com barra"
            "dumbbell bench press" -> "Supino reto com halter"
            "incline dumbbell bench press" -> "Supino inclinado com halter"
            "squat", "barbell squat" -> "Agachamento livre com barra"
            "deadlift", "barbell deadlift" -> "Levantamento terra com barra"
            "pull up", "pull-up", "pullups" -> "Barra fixa"
            "push up", "push-up", "pushups" -> "Flexão de braço"
            "overhead press", "military press" -> "Desenvolvimento militar"
            "biceps curl", "barbell curl" -> "Rosca direta com barra"
            "triceps dip", "dips" -> "Tríceps paralelas"
            "lat pulldown" -> "Puxada alta"
            "seated cable row" -> "Remada baixa no cabo"
            else -> rawName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    fun normalizeMuscleGroup(rawMuscle: String): String {
        val lower = rawMuscle.trim().lowercase()
        return when {
            lower.contains("chest") || lower.contains("peito") || lower.contains("pectoral") -> "Peito"
            lower.contains("back") || lower.contains("costas") || lower.contains("lat") || lower.contains("trapezius") -> "Costas"
            lower.contains("leg") || lower.contains("perna") || lower.contains("quad") || lower.contains("hamstring") || lower.contains("glute") || lower.contains("calves") -> "Pernas"
            lower.contains("shoulder") || lower.contains("ombro") || lower.contains("deltoid") -> "Ombros"
            lower.contains("bicep") || lower.contains("tricep") || lower.contains("arm") || lower.contains("braço") || lower.contains("forearm") -> "Braços"
            lower.contains("abs") || lower.contains("core") || lower.contains("abdômen") || lower.contains("abdomen") -> "Abdômen"
            else -> rawMuscle.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    fun normalizeEquipment(rawEquipment: String?): String {
        if (rawEquipment.isNullOrBlank()) return "Outro"
        val lower = rawEquipment.trim().lowercase()
        return when {
            lower.contains("barbell") || lower.contains("barra") -> "Barra"
            lower.contains("dumbbell") || lower.contains("halter") -> "Halter"
            lower.contains("cable") || lower.contains("cabo") -> "Cabo"
            lower.contains("machine") || lower.contains("máquina") || lower.contains("maquina") -> "Máquina"
            lower.contains("body weight") || lower.contains("bodyweight") || lower.contains("peso corporal") -> "Peso corporal"
            lower.contains("band") || lower.contains("elástico") -> "Elástico"
            lower.contains("kettlebell") -> "Kettlebell"
            lower.contains("smith") -> "Máquina Smith"
            else -> rawEquipment.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    fun cleanText(text: String): String {
        return text.trim().replace("\\s+".toRegex(), " ")
    }
}
