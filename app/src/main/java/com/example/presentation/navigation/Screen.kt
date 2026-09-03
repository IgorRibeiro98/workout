package com.example.presentation.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.R

sealed class Screen(val route: String, @StringRes val titleRes: Int, val icon: ImageVector) {
    object Today : Screen("today", R.string.nav_today, Icons.Default.CalendarToday)
    object Workouts : Screen("workouts", R.string.nav_workouts, Icons.Default.FitnessCenter)
    object Exercises : Screen("exercises", R.string.nav_exercises, Icons.Default.List)
    object TemplateDetails : Screen("template_details/{templateId}", R.string.nav_workouts, Icons.Default.FitnessCenter) {
        fun createRoute(templateId: Long) = "template_details/$templateId"
    }
    object ExerciseDetails : Screen("exercise_details/{exerciseId}/{exerciseName}", R.string.nav_exercises, Icons.Default.List) {
        fun createRoute(exerciseId: Long, exerciseName: String) = "exercise_details/$exerciseId/${android.net.Uri.encode(exerciseName)}"
    }
    object ProgramDetails : Screen("program_details/{programId}", R.string.nav_workouts, Icons.Default.FitnessCenter) {
        fun createRoute(programId: Long) = "program_details/$programId"
    }
    object History : Screen("history", R.string.nav_history, Icons.Default.History)
    object MyEvolution : Screen("my_evolution", R.string.nav_evolution, Icons.Default.TrendingUp)
    object Profile : Screen("profile", R.string.nav_profile, Icons.Default.Person)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
    object Execution : Screen("execution", R.string.nav_today, Icons.Default.PlayArrow) // Reuse string for now
    object Summary : Screen("summary/{sessionId}", R.string.nav_today, Icons.Default.PlayArrow) {
        fun createRoute(sessionId: Long) = "summary/$sessionId"
    }
    object BodyEvolution : Screen("body_evolution", R.string.body_evolution_title, Icons.Default.Straighten)
    object AddBodyMeasurement : Screen("add_body_measurement", R.string.body_evolution_title, Icons.Default.Straighten)
}
