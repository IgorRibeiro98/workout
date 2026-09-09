package com.example.data.social

import com.example.domain.auth.AuthSourceInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provas estruturais das fronteiras do domínio social (T17.0).
 *
 * Comportamento não cobre estas. Um `outboxDao.insert(...)` dentro do `SocialViewModel` funcionaria
 * em teste e ainda assim seria exatamente o defeito que a arquitetura proíbe: o social é
 * **server-authoritative**, e uma entrada na Outbox o faria viajar pelo protocolo de sync do
 * treino — que não sabe o que é um perfil, e cuja resolução de conflito o usuário teria de
 * arbitrar. Um `Log.d(TAG, profile.friendCode)` passaria em toda a suíte e ainda assim colocaria o
 * código de convite de todo mundo no Logcat.
 *
 * Estes testes olham o **código-fonte**.
 */
class SocialBoundaryInspectionTest {

    private val socialDataPackage = "app/src/main/java/com/example/data/social"
    private val socialDomainPackage = "app/src/main/java/com/example/domain/social"
    private val socialUiFiles = listOf(
        "app/src/main/java/com/example/presentation/account/SocialViewModel.kt",
        "app/src/main/java/com/example/presentation/account/SocialUiState.kt",
        "app/src/main/java/com/example/presentation/account/SocialSection.kt",
        // T17.1 — o grafo social. As mesmas fronteiras valem para ele: sem Room, sem Outbox, sem
        // dado de treino, sem log e sem HTTP na tela.
        "app/src/main/java/com/example/presentation/account/FriendsViewModel.kt",
        "app/src/main/java/com/example/presentation/account/FriendsUiState.kt",
        "app/src/main/java/com/example/presentation/friends/FriendsScreen.kt",
        "app/src/main/java/com/example/presentation/friends/FriendRequestsScreen.kt",
        "app/src/main/java/com/example/presentation/friends/AddFriendDialog.kt",
        "app/src/main/java/com/example/presentation/friends/MyFriendCodeDialog.kt",
        "app/src/main/java/com/example/presentation/friends/FriendCodeQr.kt",
        "app/src/main/java/com/example/presentation/friends/FriendsMessages.kt",
        // T17.2 — o perfil enriquecido. As mesmas fronteiras valem: sem Room, sem Outbox, sem
        // dado de treino lido no aparelho, sem log e sem HTTP na tela.
        "app/src/main/java/com/example/presentation/account/SocialProfileViewModel.kt",
        "app/src/main/java/com/example/presentation/account/SocialProfileUiState.kt",
        "app/src/main/java/com/example/presentation/friends/FriendSocialProfileScreen.kt",
        "app/src/main/java/com/example/presentation/friends/ProgressSharingScreen.kt",
        "app/src/main/java/com/example/presentation/friends/SocialProfileMessages.kt",
        // T17.3 — os desafios. As mesmas fronteiras valem: sem Room, sem Outbox, sem dado de
        // treino lido no aparelho, sem log e sem HTTP na tela. E uma a mais, que é o coração da
        // fase: nada aqui **envia** pontuação.
        "app/src/main/java/com/example/presentation/account/ChallengeViewModel.kt",
        "app/src/main/java/com/example/presentation/account/ChallengeUiState.kt",
        "app/src/main/java/com/example/presentation/friends/ChallengesScreen.kt",
        "app/src/main/java/com/example/presentation/friends/ChallengeDetailScreen.kt",
        "app/src/main/java/com/example/presentation/friends/CreateChallengeScreen.kt",
        "app/src/main/java/com/example/presentation/friends/ChallengeMessages.kt",
        // T17.6 — bloqueio e denúncia de abuso.
        "app/src/main/java/com/example/presentation/friends/BlockedUsersViewModel.kt",
        "app/src/main/java/com/example/presentation/friends/BlockedUsersScreen.kt",
        // T17.8 — o Feed de check-ins. As mesmas fronteiras valem: sem Room, sem Outbox, sem dado
        // de treino lido no aparelho, sem log e sem HTTP na tela. A ViewModel do CTA
        // (`WorkoutCheckInViewModel`) fica **fora** desta lista de propósito: ela coordena com o
        // `WorkoutCheckInPublisher`, que é justamente o objeto autorizado a olhar os dois lados —
        // e ele mora em `data/repository`, fora do pacote social.
        "app/src/main/java/com/example/presentation/friends/SocialFeedViewModel.kt",
        "app/src/main/java/com/example/presentation/friends/SocialFeedScreen.kt",
        // T17.4 — atividade e ranking dos amigos. Projeção efêmera: nada em Room, nada na Outbox.
        "app/src/main/java/com/example/presentation/friends/SocialActivityViewModel.kt",
        "app/src/main/java/com/example/presentation/friends/SocialActivityUiState.kt",
        "app/src/main/java/com/example/presentation/friends/ActivityScreen.kt",
        // T17.5 — preferências de notificação. O escopo de push vive em `com.example.service`, e
        // não aqui: a tela só lê e grava preferência pelo gateway.
        "app/src/main/java/com/example/presentation/friends/NotificationPreferencesViewModel.kt",
        "app/src/main/java/com/example/presentation/friends/NotificationPreferencesScreen.kt",
        // T17.7 — compartilhamento de treino. A ViewModel coordena com o `WorkoutShareImporter`,
        // que é o objeto autorizado a escrever no Room — e ele mora em `data/repository`, fora
        // do pacote social, como o `WorkoutCheckInPublisher` da T17.8.
        "app/src/main/java/com/example/presentation/friends/SharedWorkoutsViewModel.kt",
        "app/src/main/java/com/example/presentation/friends/SharedWorkoutsScreen.kt",
        "app/src/main/java/com/example/presentation/friends/ShareWorkoutDialog.kt",
        // T17.9 — o check-in rico. Estas entraram na lista na T17.10 (§103/§105/§120): elas eram
        // as superfícies mais novas do social e as únicas fora da varredura estrutural, que é
        // exatamente a combinação que a auditoria existe para desfazer.
        "app/src/main/java/com/example/presentation/friends/CheckInDetailViewModel.kt",
        "app/src/main/java/com/example/presentation/friends/CheckInDetailScreen.kt",
        "app/src/main/java/com/example/presentation/friends/ShareCheckInSection.kt"
    )

    private fun socialSources() =
        AuthSourceInspection.sources(socialDataPackage) +
            AuthSourceInspection.sources(socialDomainPackage) +
            socialUiFiles.map { path ->
                java.io.File(path).takeIf { it.isFile } ?: java.io.File(path.removePrefix("app/"))
            }.filter { it.isFile }

    @Test
    fun `os arquivos sociais existem — senao os testes abaixo nao provam nada`() {
        // Uma inspeção de fonte que não encontra fonte nenhuma passa vazia. Este teste é o que
        // impede os outros de virarem verdes por engano depois de um `git mv`.
        val names = socialSources().map { it.name }.toSet()
        assertTrue(
            "fontes sociais não encontradas: $names",
            names.containsAll(
                setOf(
                    "SocialContract.kt",
                    "SocialDtos.kt",
                    "SparkSocialGateway.kt",
                    "SocialProfile.kt",
                    "SocialGateway.kt",
                    "SocialViewModel.kt",
                    "SocialUiState.kt",
                    "SocialSection.kt",
                    // T17.1
                    "FriendshipContract.kt",
                    "FriendDtos.kt",
                    "SparkFriendGateway.kt",
                    "SparkFriendQr.kt",
                    "QrCode.kt",
                    "QrScanner.kt",
                    "GmsQrScanner.kt",
                    "Friendship.kt",
                    "FriendGateway.kt",
                    "FriendsViewModel.kt",
                    "FriendsUiState.kt",
                    "FriendsScreen.kt",
                    "FriendRequestsScreen.kt",
                    "AddFriendDialog.kt",
                    "MyFriendCodeDialog.kt",
                    // T17.2
                    "SocialProfileContract.kt",
                    "SocialProfileDtos.kt",
                    "SparkSocialProfileGateway.kt",
                    "SocialProfileProgress.kt",
                    "SocialProfileGateway.kt",
                    "SocialProfileViewModel.kt",
                    "SocialProfileUiState.kt",
                    "FriendSocialProfileScreen.kt",
                    "ProgressSharingScreen.kt",
                    // T17.3
                    "ChallengeContract.kt",
                    "ChallengeDtos.kt",
                    "SparkChallengeGateway.kt",
                    "Challenge.kt",
                    "ChallengeGateway.kt",
                    "ChallengeViewModel.kt",
                    "ChallengeUiState.kt",
                    "ChallengesScreen.kt",
                    "ChallengeDetailScreen.kt",
                    "CreateChallengeScreen.kt",
                    // T17.8
                    "WorkoutCheckInContract.kt",
                    "WorkoutCheckInDtos.kt",
                    "SparkWorkoutCheckInGateway.kt",
                    "WorkoutCheckIn.kt",
                    "WorkoutCheckInGateway.kt",
                    "SocialFeedViewModel.kt",
                    "SocialFeedScreen.kt"
                )
            )
        )
    }

    // ------------------------------------------------------------------ Room não é autoridade

    @Test
    fun `o social nao conhece Room, DAO nem entidade local`() {
        // Se o social pudesse escrever no Room, existiriam duas verdades sobre a identidade social
        // — e a primeira divergência seria um `friendCode` que a pessoa mostra a um amigo e que o
        // servidor não reconhece. Também é isto que garante que ativar/desativar não toca em
        // treino, histórico, medida ou gamificação: não há por onde.
        assertNoneReference(
            listOf(
                "AppDatabase",
                "WorkoutDao",
                "@Entity",
                "@Dao",
                "RoomDatabase",
                "androidx.room",
                "SettingsManager",
                "DataStore"
            )
        )
    }

    @Test
    fun `o social nao usa a Outbox nem o protocolo de sync`() {
        // Offline, uma edição social **não acontece**. Ela não fica pendente, não é reenviada e
        // não vira mutação: "editei meu nome no avião e ele mudou sozinho três dias depois" seria
        // pior do que "não deu, tente com internet".
        assertNoneReference(
            listOf(
                "SyncOutbox",
                "syncOutboxDao",
                "SyncMutationCoordinator",
                "SyncEntityType",
                "SyncOperation",
                "sync_entities",
                "baseRevision",
                "clientMutationId",
                "tombstone",
                "SyncRemoteApplier",
                "WorkManager",
                "OneTimeWorkRequest",
                "PeriodicWorkRequest"
            )
        )
    }

    @Test
    fun `o social nao alcanca backup, restore nem CloudDataBinding`() {
        // `CloudDataBinding` protege o **dataset de treino**: ele responde "de quem são estes
        // dados". O social pertence à conta autenticada, e reusá-lo como identidade social ligaria
        // duas coisas que precisam poder divergir — o dataset pode ser de A com B logado, e isso
        // bloqueia a nuvem sem ter nada a dizer sobre o perfil social de B.
        assertNoneReference(
            listOf(
                "CloudDataBinding",
                "BackupRepository",
                "RestoreRepository",
                "BackupSnapshotBuilder",
                "backup_snapshots",
                "clientBackupId"
            )
        )
    }

    @Test
    fun `o social nao le dado de treino, medida ou gamificacao`() {
        // A fronteira da T17.0: nenhuma projeção de progresso existe, e o caminho para uma no
        // futuro é explícito (`SocialProjection`, no backend), nunca um DAO importado aqui.
        assertNoneReference(
            listOf(
                "WorkoutSession",
                "WorkoutTemplate",
                "PersonalRecord",
                "BodyMeasurement",
                "XpTransaction",
                "AchievementRepository",
                "MissionRepository",
                "consistencyRepository"
            )
        )
    }

    // ------------------------------------------------------------------ o que não vai para log

    @Test
    fun `o pacote social nao registra log`() {
        // Nome social, `friendCode` e `socialId` não podem chegar ao Logcat — nem em debug, onde um
        // relatório de bug os levaria junto. A regra é a mesma dos pacotes de sync e restore:
        // ausência total de log, verificada, em vez de disciplina sobre o que logar.
        val offenders = socialSources().filter { file ->
            val code = AuthSourceInspection.code(file)
            listOf("Log.d(", "Log.i(", "Log.w(", "Log.e(", "Log.v(", "println(")
                .any { code.contains(it) }
        }
        assertEquals(
            "o pacote social não pode registrar log: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }

    @Test
    fun `nenhum DTO social carrega identidade privada`() {
        // O Firebase UID é identidade privada de infraestrutura e nunca identidade pública. O
        // e-mail continua sendo assunto da camada de Auth. Nenhum dos dois entra no contrato
        // social — nem na requisição (o servidor recusaria), nem na resposta.
        // `FriendDtos.kt` (T17.1) entra na mesma varredura: o preview que uma pessoa vê da outra
        // é o lugar mais fácil de vazar um uid "só para relacionar" — e o mais difícil de tirar
        // depois que três telas passarem a depender dele.
        // `SocialProfileDtos.kt` (T17.2) entra na mesma varredura pelo mesmo motivo: o perfil
        // enriquecido é onde mais dói vazar um uid "só para relacionar" — e onde é mais difícil
        // tirar depois que três telas passarem a depender dele.
        val dtoFiles = socialSources().filter {
            it.name == "SocialDtos.kt" ||
                it.name == "FriendDtos.kt" ||
                it.name == "SocialProfileDtos.kt"
        }
        assertEquals(3, dtoFiles.size)

        for (dtoFile in dtoFiles) {
            val code = AuthSourceInspection.code(dtoFile)
            for (forbidden in listOf("ownerUid", "firebaseUid", "email", "photoUrl", "avatarUrl")) {
                assertEquals(
                    "${dtoFile.name} não pode declarar '$forbidden'",
                    false,
                    Regex("""\b(val|var)\s+$forbidden\b""").containsMatchIn(code)
                )
            }
        }
    }

    // ------------------------------------------------------------------ a UI não fala protocolo

    @Test
    fun `a UI social nao monta requisicao nem conhece HTTP`() {
        // A tela emite intenção; quem fala HTTP é o gateway. Sem isso, mais cedo ou mais tarde uma
        // Composable monta um corpo de requisição e o contrato passa a ter dois donos.
        val uiSources = socialUiFiles.map { path ->
            java.io.File(path).takeIf { it.isFile } ?: java.io.File(path.removePrefix("app/"))
        }.filter { it.isFile }

        val offenders = uiSources.filter { file ->
            val code = AuthSourceInspection.code(file)
            listOf("okhttp3", "SparkBackendClient", "Request.Builder", "v1/social", "Json {")
                .any { code.contains(it) }
        }
        assertEquals(
            "a UI social não pode falar HTTP: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }

    @Test
    fun `so o gateway HTTP conhece os caminhos do servidor`() {
        val holders = socialSources().filter {
            AuthSourceInspection.code(it).contains("v1/social")
        }
        assertEquals(
            "os caminhos sociais moram nos contratos, e mais nada os escreve",
            listOf(
                "BlockContract.kt",
                "ChallengeContract.kt",
                "FriendshipContract.kt",
                "ReportContract.kt",
                "SocialContract.kt",
                "SocialNotificationContract.kt",
                "SocialProfileContract.kt",
                // T17.8 — check-ins de treino e Feed. `WorkoutShareContract.kt` (T17.7) entra na
                // lista agora porque o `v1/` faltava nele: o caminho batia em `/social/...` e
                // nenhuma rota de compartilhamento respondia.
                "WorkoutCheckInContract.kt",
                "WorkoutShareContract.kt"
            ),
            holders.map { it.name }.sorted()
        )
    }

    // ------------------------------------------------------------------ o QR não vira navegação

    @Test
    fun `o QR nunca e executado, aberto ou seguido`() {
        // Um QR é conteúdo arbitrário de origem desconhecida. A única coisa que o Spark faz com
        // ele é tentar ler um código de amigo: nada de `Intent` com a URL lida, nada de `WebView`,
        // nada de `startActivity` sobre o que a câmera capturou. O único `Intent` do pacote é o
        // Sharesheet de "Compartilhar", que envia **o próprio código** — texto montado pelo app.
        val parser = socialSources().first { it.name == "SparkFriendQr.kt" }
        val scanner = socialSources().first { it.name == "GmsQrScanner.kt" }

        for (file in listOf(parser, scanner)) {
            val code = AuthSourceInspection.code(file)
            for (forbidden in listOf(
                "Intent(", "startActivity", "WebView", "CustomTabs", "Uri.parse", "loadUrl"
            )) {
                assertEquals(
                    "${file.name} não pode executar conteúdo lido: '$forbidden'",
                    false,
                    code.contains(forbidden)
                )
            }
        }
    }

    @Test
    fun `ler QR nao custa permissao de camera`() {
        // O Google Code Scanner abre a câmera na UI do Play Services e devolve só o texto lido, e
        // é por isso que `android.permission.CAMERA` **não** entra no manifesto do Spark. Se
        // alguém trocar o leitor por CameraX + ML Kit, a permissão apareceria aqui — e este teste
        // é o que força essa troca a ser uma decisão, e não um efeito colateral.
        val manifest = java.io.File("app/src/main/AndroidManifest.xml").takeIf { it.isFile }
            ?: java.io.File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml não encontrado", manifest.isFile)

        val declared = manifest.readText()
        for (permission in listOf(
            "android.permission.CAMERA",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES"
        )) {
            assertEquals(
                "ler um código de amigo não pode custar '$permission'",
                false,
                declared.contains(permission)
            )
        }
    }

    @Test
    fun `o app nao le a area de transferencia`() {
        // Escrever no clipboard acontece só no toque em "Copiar código". **Ler** o clipboard —
        // ainda que para "detectar um código copiado" — seria ler tudo o que a pessoa copiou.
        val offenders = socialSources().filter { file ->
            val code = AuthSourceInspection.code(file)
            listOf("getPrimaryClip()", "primaryClip", "addPrimaryClipChangedListener")
                .any { code.contains(it) }
        }
        assertEquals(
            "nenhum arquivo social pode ler a área de transferência: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }

    @Test
    fun `o QR nao carrega identidade privada`() {
        // O payload circula por foto, papel e grupo de mensagem. O que ele carrega é público por
        // construção — e a construção é esta: prefixo, versão e `friendCode`.
        val code = AuthSourceInspection.code(
            socialSources().first { it.name == "SparkFriendQr.kt" }
        )

        for (forbidden in listOf("ownerUid", "firebaseUid", "email", "socialId", "deviceId", "token")) {
            assertEquals(
                "o payload do QR não pode conhecer '$forbidden'",
                false,
                code.contains(forbidden)
            )
        }
    }

    private fun assertNoneReference(forbidden: List<String>) {
        val offenders = socialSources().mapNotNull { file ->
            val code = AuthSourceInspection.code(file)
            val hits = forbidden.filter { code.contains(it) }
            if (hits.isEmpty()) null else "${file.name}: $hits"
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
