package com.example.data.social

import com.example.domain.auth.AuthSourceInspection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provas estruturais da fronteira de privacidade do check-in e do Feed (T17.8 §138/§149;
 * T17.9 §59/§133/§134/§185).
 *
 * ## O que a T17.9 mudou aqui, e o que ela **não** mudou
 *
 * `caption`, `media`, `reactions` e `comments` saíram das listas de proibidos: eles são o conteúdo
 * que a T17.9 acrescentou ao mesmo agregado (§5), com sanitização, pipeline de imagem e política de
 * acesso próprios. O que **continua** proibido é o que sempre foi: dado de treino, identidade
 * privada, URL pública de mídia e imagem embutida em base64. Afrouxar a lista para a fase que a
 * define é diferente de afrouxá-la para caber uma implementação — e a segunda metade destes testes
 * ficou mais rígida, não menos.
 *
 * Comportamento não cobre estas. Um `Text(summary.session.finishedAt.toString())` dentro do card
 * do Feed passaria em toda a suíte de ViewModel — que nunca renderiza — e ainda assim publicaria o
 * horário do treino de alguém para os amigos dessa pessoa. Um `val durationMinutes` no DTO
 * passaria igual, e a segunda tela a depender dele tornaria a remoção cara.
 *
 * Estes testes olham o **código-fonte**, com comentários removidos: a documentação desta fase
 * precisa poder citar exatamente o que ela proíbe.
 */
class WorkoutCheckInPrivacyInspectionTest {

    private fun source(path: String): File =
        File(path).takeIf { it.isFile } ?: File(path.removePrefix("app/"))

    private val contractSources = listOf(
        "app/src/main/java/com/example/data/social/WorkoutCheckInContract.kt",
        "app/src/main/java/com/example/data/social/WorkoutCheckInDtos.kt",
        "app/src/main/java/com/example/data/social/SparkWorkoutCheckInGateway.kt",
        "app/src/main/java/com/example/domain/social/WorkoutCheckIn.kt",
        "app/src/main/java/com/example/domain/social/WorkoutCheckInGateway.kt"
    ).map(::source)

    private val feedUiSources = listOf(
        "app/src/main/java/com/example/presentation/friends/SocialFeedScreen.kt",
        "app/src/main/java/com/example/presentation/friends/SocialFeedViewModel.kt",
        // T17.9 — o detalhe é a mesma publicação, com a mesma fronteira (§118/§185).
        "app/src/main/java/com/example/presentation/friends/CheckInDetailScreen.kt",
        "app/src/main/java/com/example/presentation/friends/CheckInDetailViewModel.kt"
    ).map(::source)

    /** T17.9 — a mídia. Ela nunca encosta no disco, e nunca monta URL (§48/§56). */
    private val mediaSources = listOf(
        "app/src/main/java/com/example/data/media/SocialMediaCache.kt",
        "app/src/main/java/com/example/data/media/CheckInPhotoSource.kt"
    ).map(::source)

    @Test
    fun `os arquivos do check-in existem — senao os testes abaixo nao provam nada`() {
        for (file in contractSources + feedUiSources + mediaSources) {
            assertTrue("fonte não encontrada: ${file.path}", file.isFile)
        }
    }

    // ------------------------------------------------------------------ o DTO não carrega treino

    @Test
    fun `nenhum tipo do check-in declara dado de treino ou identidade privada`() {
        // O servidor também não envia nada disso. Declarar aqui seria o primeiro passo para
        // alguém decidir que "seria útil se ele mandasse".
        val forbidden = listOf(
            // Identidade privada — a fronteira da T17.0, intocada.
            "ownerUid", "authorUid", "firebaseUid", "email", "friendCode",
            "sourceSessionSyncId",
            // Dado de treino — a fronteira da T17.8, intocada.
            "startedAt", "finishedAt", "workoutId", "templateId", "templateName", "programId",
            "exercises", "sets", "reps", "repetitions", "load", "weight",
            "duration", "durationMinutes", "volume", "notes", "measurements",
            // T17.9 — a foto entra por `mediaId`, e **nunca** por URL nem por bytes embutidos
            // (§59/§133/§134). `videoUrl` continua fora porque vídeo continua fora de escopo (§3).
            "photoUrl", "imageUrl", "mediaUrl", "videoUrl", "storageKey", "contentHash",
            "base64", "dataUrl"
        )

        val offenders = mutableListOf<String>()
        for (file in contractSources) {
            val code = AuthSourceInspection.code(file)
            for (field in forbidden) {
                if (Regex("""\b(val|var)\s+$field\b""").containsMatchIn(code)) {
                    offenders += "${file.name}: $field"
                }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `o DTO de resposta do check-in tem exatamente os campos do contrato`() {
        // A lista é **fechada** e continua sendo o ponto: o que este teste protege não é o número
        // de campos, é que nada de treino e nada de identidade privada entrem por um campo novo.
        val declared = declaredFields("WorkoutCheckInDto")
        assertEquals(
            listOf(
                "type",
                "checkInId",
                "author",
                "publishedAt",
                "caption",
                "media",
                "reactions",
                "currentUserReaction",
                "commentCount",
                "isCurrentUser",
                // T17.11 §70 — se **este** viewer pode reagir e comentar. Booleano derivado da
                // política de acesso do servidor: não carrega identidade nem dado de treino, e é
                // o que mantém "membro só de Squad não interage" visível para a tela.
                "canInteract"
            ),
            declared
        )
    }

    @Test
    fun `o DTO de midia carrega identificador e dimensoes, e nada mais`() {
        // §59 — `mediaId`, largura e altura. Uma URL aqui seria o servidor entregando um endereço
        // que funciona sem token, e §48 chama isso de proibido; um campo de bytes seria a foto de
        // todo mundo viajando em toda leitura do Feed (§134).
        assertEquals(
            listOf("mediaId", "width", "height"),
            declaredFields("WorkoutCheckInMediaDto")
        )
    }

    @Test
    fun `o DTO de comentario nao carrega uid`() {
        // §88 é bloqueante: o autor de um comentário é identidade **pública**.
        val declared = declaredFields("CheckInCommentDto")
        assertEquals(
            listOf("commentId", "author", "body", "createdAt", "isCurrentUser", "canDelete"),
            declared
        )
    }

    @Test
    fun `a denuncia nao declara quem esta sendo denunciado`() {
        // §103 — o cliente diz **o que**, e o servidor descobre **de quem**. Um `reportedUid`
        // aqui deixaria qualquer pessoa registrar denúncia contra a conta que quisesse.
        val declared = declaredFields("CreateContentReportRequestDto")
        assertEquals(listOf("targetType", "targetId", "reason"), declared)
    }

    /**
     * `sessionSyncId` pode ir do **dono** para o servidor, e nunca voltar (§13/§51).
     *
     * Ele é referência interna: legítimo no corpo do `POST`, proibido em qualquer tipo que
     * descreva uma resposta — porque uma resposta é lida pela tela, e o Feed de um amigo é lido
     * por outra pessoa.
     */
    @Test
    fun `sessionSyncId existe so no corpo da requisicao`() {
        assertEquals(
            listOf("sessionSyncId", "clientRequestId", "caption", "mediaId"),
            declaredFields("CreateWorkoutCheckInRequestDto")
        )
        for (responseType in listOf(
            "WorkoutCheckInDto",
            "WorkoutCheckInAuthorDto",
            "WorkoutCheckInMediaDto",
            "SocialFeedDto",
            "CheckInCommentDto",
            "CheckInCommentsDto",
            "UploadedMediaDto"
        )) {
            assertEquals(
                "$responseType não pode declarar sessionSyncId",
                false,
                declaredFields(responseType).contains("sessionSyncId")
            )
        }
    }

    /**
     * A foto nunca vira URL, arquivo ou base64 no cliente (T17.9 §48/§56/§133/§134).
     *
     * O caminho inteiro — contrato, DTO, gateway, tela — não pode montar um endereço público, não
     * pode escrever a imagem em disco e não pode embutir bytes em JSON. `§195` lista os três como
     * bloqueantes, e nenhum deles quebraria um teste de comportamento: um `AsyncImage(url)` que
     * funcionasse renderizaria a foto perfeitamente, e o defeito só apareceria no dia em que
     * alguém abrisse o endereço sem token.
     */
    @Test
    fun `a foto nao vira URL publica, arquivo em disco nem base64`() {
        val forbidden = listOf(
            "https://", "http://",
            "Base64", "base64", "data:image",
            "cacheDir", "filesDir", "getExternalFilesDir", "FileOutputStream",
            "diskCache", "DiskCache",
            // Coil existe no projeto para outras telas; nesta fronteira ele traria cache em disco
            // por padrão, que é exatamente o que §56 recusa.
            "AsyncImage", "rememberAsyncImagePainter", "coil"
        )

        val offenders = mutableListOf<String>()
        for (file in contractSources + feedUiSources + mediaSources) {
            val code = AuthSourceInspection.code(file)
            for (term in forbidden) {
                if (code.contains(term)) offenders += "${file.name}: $term"
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * O cache de mídia é **de conta**, e ele é trocado antes de qualquer leitura (§57/§145).
     *
     * Um cache sem escopo de conta é o defeito de §195: a foto de A reaparecendo para B depois da
     * troca. Nenhum teste de comportamento pegaria isso sem montar duas contas e uma imagem real.
     */
    @Test
    fun `o cache de midia tem escopo de conta e nao toca o disco`() {
        val cache = source("app/src/main/java/com/example/data/media/SocialMediaCache.kt")
        assertTrue("SocialMediaCache não encontrado", cache.isFile)
        val code = AuthSourceInspection.code(cache)

        assertTrue("o cache precisa saber trocar de conta", code.contains("fun switchAccount("))
        assertTrue("o cache precisa saber limpar no logout", code.contains("fun clear("))

        for (term in listOf(
            "File(", "cacheDir", "filesDir", "FileOutputStream", "openFileOutput",
            "SharedPreferences", "DataStore", "androidx.room"
        )) {
            assertEquals("o cache de mídia não pode tocar o disco: '$term'", false, code.contains(term))
        }
    }

    /**
     * O Photo Picker oficial, e nenhuma permissão nova (§44/§45/§179).
     *
     * `PickVisualMedia` roda no processo do sistema e não exige `READ_MEDIA_IMAGES`. Este teste é
     * o que garante que o manifesto continue sem ela — e sem `CAMERA`, que §45 mantém fora.
     */
    @Test
    fun `o app nao pede acesso amplo a galeria nem CAMERA`() {
        val manifest = File("app/src/main/AndroidManifest.xml")
            .takeIf { it.isFile } ?: File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest não encontrado", manifest.isFile)
        val xml = manifest.readText()

        for (permission in listOf(
            "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO",
            "READ_MEDIA_VISUAL_USER_SELECTED",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "android.permission.CAMERA"
        )) {
            assertEquals("o manifesto não pode declarar $permission", false, xml.contains(permission))
        }

        // E o compositor usa o contrato oficial, e não um `Intent.ACTION_GET_CONTENT` com
        // `*/*` — que abriria o seletor de arquivos inteiro.
        val composer = AuthSourceInspection.code(
            source("app/src/main/java/com/example/presentation/friends/ShareCheckInSection.kt")
        )
        assertTrue(composer.contains("ActivityResultContracts.PickVisualMedia"))
        assertEquals(false, composer.contains("ACTION_GET_CONTENT"))
        assertEquals(false, composer.contains("ACTION_PICK"))
    }

    /** Os campos declarados no construtor de um `data class` de `WorkoutCheckInDtos.kt`. */
    private fun declaredFields(typeName: String): List<String> {
        val code = AuthSourceInspection.code(
            source("app/src/main/java/com/example/data/social/WorkoutCheckInDtos.kt")
        )
        val start = code.indexOf("data class $typeName(")
        assertTrue("tipo não encontrado: $typeName", start >= 0)

        // Percorre até o parêntese que fecha o construtor, contando aninhamento: um valor padrão
        // como `WorkoutCheckInAuthorDto()` tem parênteses próprios, e cortar no primeiro `)`
        // esconderia os campos seguintes — que é exatamente o que este teste precisa enxergar.
        val open = code.indexOf('(', start)
        var depth = 0
        var end = open
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        end = index
                        break
                    }
                }
            }
        }

        return Regex("""\bval\s+(\w+)""").findAll(code.substring(open, end))
            .map { it.groupValues[1] }
            .toList()
    }

    // ------------------------------------------------------------------ a tela não desenha treino

    @Test
    fun `a tela do Feed nao renderiza duracao, exercicio, carga, repeticao nem horario`() {
        // §149. O card diz nome social, "concluiu um treino" e há quanto tempo a **publicação**
        // aconteceu — e nada além disso.
        val forbidden = listOf(
            "durationStr", "durationMin", "totalVolume", "totalSets",
            "exerciseNameSnapshot", "templateNameSnapshot", "sortedExercises",
            "repetitions", "SetType", "SimpleDateFormat", "HH:mm",
            "startedAt", "finishedAt", "VolumeCalculator", "PersonalRecord"
        )

        val offenders = mutableListOf<String>()
        for (file in feedUiSources) {
            val code = AuthSourceInspection.code(file)
            for (term in forbidden) {
                if (code.contains(term)) offenders += "${file.name}: $term"
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `a tela do Feed nao conhece Room, DAO nem o dominio de treino`() {
        val forbidden = listOf(
            "AppDatabase", "WorkoutDao", "androidx.room", "SessionCalendarSummary",
            "WorkoutSessionEntity", "SyncOutbox", "SyncEntityType"
        )
        val offenders = mutableListOf<String>()
        for (file in feedUiSources) {
            val code = AuthSourceInspection.code(file)
            for (term in forbidden) {
                if (code.contains(term)) offenders += "${file.name}: $term"
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    // ------------------------------------------------------------------ nada é automático

    @Test
    fun `o Feed nao faz polling, nao usa WebSocket e nao agenda trabalho`() {
        // §93/§94. O que busca é abrir a tela e o "puxar para atualizar".
        val forbidden = listOf(
            "WorkManager", "PeriodicWorkRequest", "OneTimeWorkRequest",
            "WebSocket", "EventSource", "delay(", "while (true)", "fixedRateTimer", "Timer("
        )
        val offenders = mutableListOf<String>()
        for (file in feedUiSources) {
            val code = AuthSourceInspection.code(file)
            for (term in forbidden) {
                if (code.contains(term)) offenders += "${file.name}: $term"
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `o check-in nao entra na Outbox, no backup nem na gamificacao`() {
        // §119/§120: publicar e ler não são eventos de domínio.
        val publisher = source(
            "app/src/main/java/com/example/data/repository/WorkoutCheckInPublisher.kt"
        )
        assertTrue(publisher.isFile)
        val code = AuthSourceInspection.code(publisher)

        for (term in listOf(
            "SyncOutbox", "syncOutboxDao", "SyncMutationCoordinator", "clientMutationId",
            "BackupRepository", "CloudDataBindingDao", "cloudDataBindingDao",
            "XpCalculator", "AchievementEvaluator", "MissionRepository", "GamificationEvents",
            "updateSession", "insertSession", "deleteSession"
        )) {
            assertEquals("o publisher não pode conhecer '$term'", false, code.contains(term))
        }
    }

    @Test
    fun `existe um caminho unico de sincronizacao, e o check-in nao cria outro`() {
        // §20/§169: nenhum segundo uploader de sessão de treino.
        val code = AuthSourceInspection.code(
            source("app/src/main/java/com/example/data/repository/WorkoutCheckInPublisher.kt")
        )
        // Ele pede um ciclo; ele não monta um.
        assertTrue(code.contains("syncCycle.run("))
        for (term in listOf("SyncPushBuilder", "SyncApi", "sync/push", "SyncAggregateSnapshotBuilder")) {
            assertEquals("o publisher não pode montar sync próprio: '$term'", false, code.contains(term))
        }
    }
}
