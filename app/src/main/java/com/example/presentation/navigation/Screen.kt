package com.example.presentation.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Flag
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
    object Missions : Screen("missions", R.string.nav_missions, Icons.Default.Flag)
    object AiCoach : Screen("ai_coach", R.string.nav_ai_coach, Icons.Default.AutoAwesome)
    object GenerateWorkout : Screen("generate_workout", R.string.nav_ai_coach, Icons.Default.AutoAwesome)
    object AdaptWorkout : Screen("adapt_workout/{templateId}", R.string.nav_ai_coach, Icons.Default.AutoAwesome) {
        fun createRoute(templateId: Long) = "adapt_workout/$templateId"
    }
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)

    // Grafo social (T17.1). São telas alcançadas **a partir do Perfil** — e não itens novos de
    // bottom navigation: o social é uma capacidade opcional, e uma aba permanente para ela
    // apareceria vazia para quem nunca ativou.
    //
    // O `friendCode` **não** entra em rota nenhuma. "Meu código" é uma folha dentro do Perfil,
    // onde o código já está carregado; uma rota `my_code/{friendCode}` colocaria um identificador
    // compartilhável no estado de navegação sem nenhum ganho.
    object Friends : Screen("friends", R.string.nav_profile, Icons.Default.Person)
    object FriendRequests : Screen("friend_requests", R.string.nav_profile, Icons.Default.Person)

    // Perfil social enriquecido (T17.2). Também alcançadas a partir do Perfil / da lista de
    // amigos, e também sem item novo de bottom navigation.
    //
    // O `socialId` entra na rota porque é ele o identificador público do domínio social — o mesmo
    // que já viaja na URL da requisição. O `friendCode` continua fora de qualquer rota. O nome vai
    // junto, opcional, só para o cabeçalho não piscar enquanto a leitura corre: quem confirma quem
    // é a pessoa continua sendo o servidor.
    object FriendProfile :
        Screen("friend_profile/{socialId}?name={name}", R.string.nav_profile, Icons.Default.Person) {
        fun createRoute(socialId: String, displayName: String): String {
            val id = java.net.URLEncoder.encode(socialId, "UTF-8")
            val name = java.net.URLEncoder.encode(displayName, "UTF-8")
            return "friend_profile/$id?name=$name"
        }
    }

    object ProgressSharing :
        Screen("progress_sharing", R.string.nav_profile, Icons.Default.Person)

    // Desafios (T17.3). Alcançados a partir da seção Social do Perfil, e também **sem item novo
    // de bottom navigation** (§156): a barra inferior é do núcleo do produto — treinar, histórico,
    // evolução —, e o social continua sendo uma área dentro do Perfil.
    //
    // O `challengeId` entra na rota porque é um UUID opaco que só os participantes conhecem, e ele
    // não autoriza nada: a autorização vem do token, verificada a cada leitura. Nenhum `socialId`
    // e nenhum `friendCode` entram em rota de desafio.
    object Challenges : Screen("challenges", R.string.nav_profile, Icons.Default.Person)

    object CreateChallenge :
        Screen("create_challenge", R.string.nav_profile, Icons.Default.Person)

    object ChallengeDetail :
        Screen("challenge/{challengeId}?name={name}", R.string.nav_profile, Icons.Default.Person) {
        fun createRoute(challengeId: String, name: String = ""): String {
            val id = java.net.URLEncoder.encode(challengeId, "UTF-8")
            val label = java.net.URLEncoder.encode(name, "UTF-8")
            return "challenge/$id?name=$label"
        }
    }

    // Atividade dos amigos e rankings contextuais (T17.4).
    object Activity : Screen("activity", R.string.nav_profile, Icons.Default.Person)

    // Notificações sociais (T17.5).
    object NotificationPreferences : Screen("notification_preferences", R.string.nav_profile, Icons.Default.Person)

    // Usuários bloqueados (T17.6).
    object BlockedUsers : Screen("blocked_users", R.string.nav_profile, Icons.Default.Person)

    object Execution : Screen("execution", R.string.nav_today, Icons.Default.PlayArrow) // Reuse string for now
    object Summary : Screen("summary/{sessionId}", R.string.nav_today, Icons.Default.PlayArrow) {
        fun createRoute(sessionId: Long) = "summary/$sessionId"
    }
    object BodyEvolution : Screen("body_evolution", R.string.body_evolution_title, Icons.Default.Straighten)
    object AddBodyMeasurement : Screen("add_body_measurement", R.string.body_evolution_title, Icons.Default.Straighten)
}
