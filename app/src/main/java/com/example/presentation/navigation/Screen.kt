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

/**
 * Escapa um valor para o query string de uma rota.
 *
 * O `+` que o `URLEncoder` produz para espaço é trocado por `%20` porque quem desfaz o escape do
 * outro lado é o `Uri.decode` da navegação, que **não** trata `+` como espaço — sem esta troca,
 * "João Silva" chega à tela como "João+Silva".
 */
private fun encodeRouteArg(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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
            val id = encodeRouteArg(socialId)
            val name = encodeRouteArg(displayName)
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
            val id = encodeRouteArg(challengeId)
            val label = encodeRouteArg(name)
            return "challenge/$id?name=$label"
        }
    }

    // Atividade dos amigos e rankings contextuais (T17.4).
    object Activity : Screen("activity", R.string.nav_profile, Icons.Default.Person)

    // Notificações sociais (T17.5).
    object NotificationPreferences : Screen("notification_preferences", R.string.nav_profile, Icons.Default.Person)

    // Usuários bloqueados (T17.6).
    object BlockedUsers : Screen("blocked_users", R.string.nav_profile, Icons.Default.Person)

    // Treinos compartilhados (T17.7).
    object SharedWorkouts : Screen("shared_workouts", R.string.nav_workouts, Icons.Default.FitnessCenter)

    // Feed de check-ins (T17.8). Dentro da área Social do Perfil, e **sem** item novo de bottom
    // navigation (§83): a barra inferior é do núcleo do produto — treinar, histórico, evolução.
    //
    // Nenhum identificador entra na rota. O Feed é sempre o do próprio usuário: quem aparece nele
    // é derivado no servidor a partir do token, das amizades atuais e da política de bloqueio.
    object SocialFeed : Screen("social_feed", R.string.nav_profile, Icons.Default.Person)

    /**
     * O detalhe de uma publicação (T17.9 §118; T17.12 §35/§63).
     *
     * Empilhada sobre o Feed, **sem** item novo de bottom navigation (§118): a barra inferior
     * continua sendo do núcleo do produto.
     *
     * A rota carrega o `checkInId` — um identificador opaco do servidor — e, desde a T17.12, **de
     * onde o usuário veio**: o Feed de amigos ou um Squad. Nenhum dado de treino, nenhum
     * `socialId` e nenhum uid entram em rota de navegação.
     *
     * ## Por que a audiência viaja na rota
     *
     * Porque o mesmo check-in é alcançável pelo Feed de amigos e por cada Squad em que ele foi
     * compartilhado, e até a T17.11 os dois caminhos abriam **exatamente a mesma rota** — a tela
     * não tinha como saber onde a conversa estava acontecendo, e a interação nascia sempre no Feed
     * de amigos. Levar a origem no destino é o que faz a tela pedir a conversa certa.
     *
     * ## Isto é navegação local, e nunca autorização (T17.12 §67/§69)
     *
     * O `groupId` aqui descreve de onde o **próprio usuário** tocou, neste aparelho. Ele nunca vem
     * de uma resposta do servidor, e não concede nada: o servidor revalida compartilhamento,
     * participação ativa e bloqueio a cada requisição, e responde `404` para um contexto que não
     * confere — em vez de rebaixar em silêncio para o Feed de amigos.
     */
    object CheckInDetail : Screen(
        "check_in/{checkInId}?context={context}&groupId={groupId}&groupName={groupName}",
        R.string.nav_profile,
        Icons.Default.Person
    ) {
        /** Os valores que a rota aceita em `context`. Minúsculos: eles não são o protocolo. */
        const val CONTEXT_FRIEND = "friend"
        const val CONTEXT_GROUP = "group"

        /** Aberto pelo Feed de amigos: a audiência é a relação direta. */
        fun createRoute(checkInId: String): String =
            "check_in/${encodeRouteArg(checkInId)}?context=$CONTEXT_FRIEND"

        /**
         * Aberto de dentro de um Squad.
         *
         * [groupName] viaja só para a tela poder dizer **onde** a conversa acontece ("No squad:
         * Os Monstros"). Ele é texto que alguém digitou, então vai codificado — e a tela nunca
         * mostra o `groupId` nem o nome técnico da audiência, que não significam nada para quem lê.
         */
        fun createGroupRoute(
            checkInId: String,
            groupId: String,
            groupName: String
        ): String =
            "check_in/${encodeRouteArg(checkInId)}?context=$CONTEXT_GROUP" +
                "&groupId=${encodeRouteArg(groupId)}&groupName=${encodeRouteArg(groupName)}"
    }

    /**
     * Squads privados (T17.11 §131).
     *
     * Dentro da área Social do Perfil, e **sem** item novo de bottom navigation: a barra inferior
     * é do núcleo do produto — treinar, histórico, evolução.
     *
     * Nenhum identificador entra na rota. A lista é sempre a do próprio usuário: quem aparece nela
     * é derivado no servidor a partir do token, e não existe busca de Squad (§4/§5).
     */
    object Squads : Screen("squads", R.string.nav_profile, Icons.Default.Person)

    /**
     * O detalhe de um Squad (T17.11 §135).
     *
     * A rota carrega só o `groupId` — um identificador opaco do servidor que **não concede acesso**
     * (§59): quem não é membro recebe o mesmo `404` de "não existe". Nenhum dado de treino, nenhum
     * `socialId` e nenhum uid entram em rota de navegação.
     */
    object SquadDetail : Screen("squad/{groupId}", R.string.nav_profile, Icons.Default.Person) {
        fun createRoute(groupId: String) = "squad/$groupId"
    }

    object Execution : Screen("execution", R.string.nav_today, Icons.Default.PlayArrow) // Reuse string for now
    object Summary : Screen("summary/{sessionId}", R.string.nav_today, Icons.Default.PlayArrow) {
        fun createRoute(sessionId: Long) = "summary/$sessionId"
    }
    object BodyEvolution : Screen("body_evolution", R.string.body_evolution_title, Icons.Default.Straighten)
    object AddBodyMeasurement : Screen("add_body_measurement", R.string.body_evolution_title, Icons.Default.Straighten)
}
