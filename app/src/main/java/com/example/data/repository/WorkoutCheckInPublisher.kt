package com.example.data.repository

import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutDao
import com.example.data.sync.SyncOutcome
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.UploadedCheckInMedia
import com.example.domain.social.WorkoutCheckIn
import com.example.domain.social.WorkoutCheckInError
import com.example.domain.social.WorkoutCheckInGateway
import com.example.domain.social.WorkoutCheckInOutcome

/**
 * A janela em que uma sessão concluída ainda pode virar check-in, do lado do aparelho.
 *
 * **Ela não decide nada** (§27). O servidor revalida com o relógio dele, e é ele quem responde
 * `CHECKIN_WINDOW_EXPIRED`. Este número existe só para a tela não oferecer uma ação que vai
 * falhar — esconder um botão é conveniência, não autorização.
 */
const val CHECKIN_LOCAL_WINDOW_MS: Long = 48L * 60L * 60L * 1000L

/**
 * Um ciclo do sync da T16, como dependência injetável.
 *
 * **Não é um segundo mecanismo de sincronização** (§20). É um ponteiro para o único que existe —
 * `SyncRepository.syncNow` —, declarado como `fun interface` para que este coordenador possa ser
 * testado sem montar Room, DataStore, `SyncApi` e o vínculo de nuvem inteiro. Nada aqui monta
 * corpo de push, escreve na Outbox ou fala com `sync_entities`: se precisasse, seria o segundo
 * caminho de upload que a T17.8 proíbe.
 */
fun interface CanonicalSyncCycle {
    suspend fun run(currentUid: String?): SyncOutcome
}

/**
 * Se a tela deve oferecer "Compartilhar check-in" para uma sessão (§100).
 *
 * Só [Eligible] mostra o CTA. Todos os outros valores existem para a tela dizer **o que** falta —
 * e não para inventar uma autorização: quem autoriza é o servidor, em cada publicação.
 */
sealed interface CheckInEligibility {

    /** Tudo pronto do lado do aparelho. O servidor ainda revalida na publicação. */
    data object Eligible : CheckInEligibility

    /** Não existe Spark Backend neste build, ou não há conta conectada. */
    data object Unavailable : CheckInEligibility

    /** A sessão não existe mais no aparelho. */
    data object SessionMissing : CheckInEligibility

    /** A sessão não está concluída. Concluir é pré-requisito, e concluir não publica (§3/§98). */
    data object NotCompleted : CheckInEligibility

    /** O treino é antigo demais para virar check-in. */
    data object OutsideWindow : CheckInEligibility
}

/**
 * O desfecho de um envio de foto (T17.9 §33/§43).
 *
 * Separado do desfecho da publicação de propósito: falhar ao enviar a foto **não** é falhar ao
 * publicar. §43 é explícito em que a decisão seguinte — tentar de novo ou publicar sem a foto —
 * precisa ser do usuário, e um tipo que colapsasse os dois casos empurraria a tela a escolher por
 * ele.
 */
sealed interface CheckInMediaUploadResult {

    data class Uploaded(val media: UploadedCheckInMedia) : CheckInMediaUploadResult

    /** A sessão não é elegível para receber foto (§32). */
    data class NotEligible(val reason: CheckInEligibility) : CheckInMediaUploadResult

    /** O servidor recusou, ou a rede falhou. O treino continua salvo, e a foto não subiu. */
    data class Failed(val error: WorkoutCheckInError) : CheckInMediaUploadResult

    /** A conta mudou durante o envio; a resposta foi descartada (§147/§149). */
    data object AccountChanged : CheckInMediaUploadResult
}

/** O desfecho de uma tentativa **explícita** de publicar. */
sealed interface CheckInPublishResult {

    data class Published(val checkIn: WorkoutCheckIn) : CheckInPublishResult

    /** O treino já teve um check-in e ele foi excluído. Um treino não recebe outro (§29). */
    data object AlreadyShared : CheckInPublishResult

    /**
     * A conta ainda não adotou a nuvem para os dados de treino (T16.4).
     *
     * O servidor nunca vai conhecer a sessão, e **não** criamos o vínculo por baixo para resolver
     * isso (§21/§169): adoção de nuvem é decisão explícita, e fazê-la acontecer como efeito
     * colateral de um botão social seria associar o dataset inteiro de alguém a uma conta sem que
     * a pessoa tivesse pedido.
     */
    data object CloudNotAdopted : CheckInPublishResult

    /** Um ciclo de sync foi tentado e a sessão continua desconhecida do servidor (§22). */
    data object SessionNotSynced : CheckInPublishResult

    /** A sessão não é elegível. O treino continua concluído e salvo. */
    data class NotEligible(val reason: CheckInEligibility) : CheckInPublishResult

    /** Qualquer outra recusa. O treino continua concluído e salvo (§10/§22). */
    data class Failed(val error: WorkoutCheckInError) : CheckInPublishResult

    /**
     * A conta mudou durante a requisição, e a resposta foi descartada (§110/§114).
     *
     * A tela não mostra nada: dizer "Publicado!" para a conta B sobre uma operação que a conta A
     * iniciou seria atribuir a B uma publicação que não é dela.
     */
    data object AccountChanged : CheckInPublishResult
}

/**
 * Quem coordena "concluí um treino, quero compartilhar" (T17.8, Etapa 3).
 *
 * ## Por que ele mora aqui, e não em `data/social`
 *
 * Porque ele é o **único** ponto que precisa olhar os dois lados: a sessão no Room e o gateway
 * social. O pacote social do app não conhece Room, DAO nem domínio de treino — há teste estrutural
 * sobre isso, e a fronteira é o que garante que ativar Social não toque em treino. Este
 * coordenador fica do lado de fora dela, como o `WorkoutShareImporter` da T17.7.
 *
 * ## O fluxo, e o que ele deliberadamente não faz
 *
 * ```text
 * usuário confirma o preview
 *        │
 *        ▼
 * sessão local COMPLETED? dentro de 48h?      ← conveniência; o servidor revalida
 *        │
 *        ▼
 * POST /v1/social/workout-checkins
 *        │
 *        ├── ok ─────────────────────────────▶ publicado
 *        └── SESSION_NOT_FOUND
 *                │  o servidor não conhece a sessão — e não diz por quê
 *                ▼
 *            um ciclo do sync T16 (o único que existe)
 *                ├── nuvem não adotada ─────▶ CloudNotAdopted (nada é criado)
 *                ├── falhou ────────────────▶ SessionNotSynced
 *                └── ok ─▶ POST de novo, com o MESMO clientRequestId
 * ```
 *
 * **Nada disso acontece sozinho.** Não há gatilho no fim do treino, no sync, na abertura de tela
 * nem em background: este objeto só age quando [publish] é chamado, e [publish] só é chamado
 * depois de o usuário confirmar (§3/§102).
 *
 * **Falhar aqui não altera treino nenhum** (§10/§146). Nenhum caminho deste arquivo escreve em
 * `workout_sessions`, na Outbox ou na gamificação — ele lê a sessão e fala com o gateway.
 *
 * **Não existe fila.** Uma publicação que não aconteceu não fica pendente e não é reenviada
 * depois (§22): o retry é uma ação nova do usuário, pelo Histórico (§23).
 */
class WorkoutCheckInPublisher(
    private val workoutDao: WorkoutDao,
    private val gateway: WorkoutCheckInGateway,
    private val authGateway: AuthGateway,
    private val syncCycle: CanonicalSyncCycle,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** O que a tela precisa saber para decidir se mostra o CTA (§100). */
    suspend fun eligibility(sessionId: Long): CheckInEligibility {
        if (!gateway.isConfigured || currentUid() == null) return CheckInEligibility.Unavailable

        val session = workoutDao.getSessionById(sessionId) ?: return CheckInEligibility.SessionMissing
        if (session.status != SessionStatus.COMPLETED.name) return CheckInEligibility.NotCompleted

        // O mesmo instante canônico que o servidor usa: o fim do treino quando ele existe, e o
        // início quando não — nunca um significado novo de "fim do treino" (§25).
        val canonicalInstant = session.finishedAt ?: session.startedAt
        if (clock() - canonicalInstant > CHECKIN_LOCAL_WINDOW_MS) {
            return CheckInEligibility.OutsideWindow
        }

        return CheckInEligibility.Eligible
    }

    /**
     * Envia a foto de uma sessão que ainda não virou check-in (T17.9 §33).
     *
     * Acontece **antes** da publicação, e é uma operação separada de propósito: assim a foto pode
     * falhar sem levar a publicação junto, e a decisão de §43 — tentar de novo ou publicar sem a
     * foto — fica com o usuário.
     *
     * [clientUploadId] torna o retry idempotente (§36): o mesmo identificador devolve o **mesmo**
     * `mediaId` e não duplica arquivo nenhum no servidor.
     */
    suspend fun uploadPhoto(
        sessionId: Long,
        clientUploadId: String,
        bytes: ByteArray
    ): CheckInMediaUploadResult {
        val eligibility = eligibility(sessionId)
        if (eligibility != CheckInEligibility.Eligible) {
            return CheckInMediaUploadResult.NotEligible(eligibility)
        }

        val session = workoutDao.getSessionById(sessionId)
            ?: return CheckInMediaUploadResult.NotEligible(CheckInEligibility.SessionMissing)
        val uidBefore = currentUid()
            ?: return CheckInMediaUploadResult.NotEligible(CheckInEligibility.Unavailable)

        val outcome = gateway.uploadMedia(session.syncId, clientUploadId, bytes)
        // §147/§149 — a conta pode ter trocado durante o envio. A foto que A escolheu não vira
        // publicação de B, e o servidor recusaria de qualquer forma: `owner_uid` está na cláusula
        // `WHERE` do anexo. Este `if` evita que a **tela** de B mostre um sucesso que era de A.
        if (currentUid() != uidBefore) return CheckInMediaUploadResult.AccountChanged

        return when (outcome) {
            is WorkoutCheckInOutcome.Success -> CheckInMediaUploadResult.Uploaded(outcome.data)
            is WorkoutCheckInOutcome.Failure -> CheckInMediaUploadResult.Failed(outcome.error)
        }
    }

    /**
     * Publica o check-in da sessão [sessionId].
     *
     * [clientRequestId] é gerado quando a operação explícita começa e **reusado** enquanto ela
     * estiver em andamento (§109): é ele que faz o retry depois de resposta perdida, o toque duplo
     * e a segunda tentativa após o sync convergirem em uma publicação só.
     *
     * [caption] e [mediaId] são o conteúdo da T17.9. Um `mediaId` recusado pelo servidor **não**
     * publica sem a foto (§43): a requisição inteira falha, e a tela devolve a escolha ao usuário.
     */
    suspend fun publish(
        sessionId: Long,
        clientRequestId: String,
        caption: String? = null,
        mediaId: String? = null
    ): CheckInPublishResult {
        val eligibility = eligibility(sessionId)
        if (eligibility != CheckInEligibility.Eligible) {
            return CheckInPublishResult.NotEligible(eligibility)
        }

        val session = workoutDao.getSessionById(sessionId)
            ?: return CheckInPublishResult.NotEligible(CheckInEligibility.SessionMissing)
        val uidBefore = currentUid() ?: return CheckInPublishResult.NotEligible(
            CheckInEligibility.Unavailable
        )

        val first = gateway.createCheckIn(session.syncId, clientRequestId, caption, mediaId)
        if (currentUid() != uidBefore) return CheckInPublishResult.AccountChanged

        if (first !is WorkoutCheckInOutcome.Failure ||
            first.error != WorkoutCheckInError.SESSION_NOT_FOUND
        ) {
            return interpret(first)
        }

        // O servidor não reconhece a sessão — e ele responde a mesma coisa para "não existe", "é
        // de outra conta" e "ainda não subiu". Aqui sabemos que ela existe **neste aparelho** e é
        // desta conta, então a leitura razoável é a terceira: falta sincronizar (§18/§19).
        return reconcileThenRetry(session.syncId, clientRequestId, caption, mediaId, uidBefore)
    }

    /** Exclui uma publicação própria. Idempotente do lado do servidor (§66). */
    suspend fun delete(checkInId: String): CheckInPublishResult {
        val uidBefore = currentUid() ?: return CheckInPublishResult.NotEligible(
            CheckInEligibility.Unavailable
        )

        val outcome = gateway.deleteCheckIn(checkInId)
        if (currentUid() != uidBefore) return CheckInPublishResult.AccountChanged

        return when (outcome) {
            is WorkoutCheckInOutcome.Success -> CheckInPublishResult.AlreadyShared
            is WorkoutCheckInOutcome.Failure -> CheckInPublishResult.Failed(outcome.error)
        }
    }

    private suspend fun reconcileThenRetry(
        sessionSyncId: String,
        clientRequestId: String,
        caption: String?,
        mediaId: String?,
        uidBefore: String
    ): CheckInPublishResult {
        when (syncCycle.run(uidBefore)) {
            is SyncOutcome.Success -> Unit

            // A conta nunca adotou a nuvem, ou o dataset é de outra conta. Nos dois casos o
            // servidor jamais conhecerá esta sessão — e **não** criamos vínculo nenhum para
            // resolver isso (§21).
            SyncOutcome.NotEnabled,
            is SyncOutcome.AccountMismatch -> return CheckInPublishResult.CloudNotAdopted

            SyncOutcome.AuthRequired ->
                return CheckInPublishResult.Failed(WorkoutCheckInError.AUTH_REQUIRED)

            // Rede, indisponibilidade, teto, recusa, ciclo já em andamento, sem configuração: em
            // todos, a sessão continua desconhecida do servidor e a publicação não acontece.
            else -> return CheckInPublishResult.SessionNotSynced
        }

        if (currentUid() != uidBefore) return CheckInPublishResult.AccountChanged

        // O **mesmo** `clientRequestId`: é a mesma intenção do usuário, e reusá-lo é o que impede
        // esta segunda tentativa de virar uma segunda publicação.
        val retry = gateway.createCheckIn(sessionSyncId, clientRequestId, caption, mediaId)
        if (currentUid() != uidBefore) return CheckInPublishResult.AccountChanged

        if (retry is WorkoutCheckInOutcome.Failure &&
            retry.error == WorkoutCheckInError.SESSION_NOT_FOUND
        ) {
            // O ciclo rodou e o servidor continua sem a sessão. Não insistimos: o treino está
            // salvo, e a próxima tentativa é uma ação nova do usuário pelo Histórico (§23).
            return CheckInPublishResult.SessionNotSynced
        }

        return interpret(retry)
    }

    private fun interpret(outcome: WorkoutCheckInOutcome<WorkoutCheckIn>): CheckInPublishResult =
        when (outcome) {
            is WorkoutCheckInOutcome.Success -> CheckInPublishResult.Published(outcome.data)
            is WorkoutCheckInOutcome.Failure -> when (outcome.error) {
                WorkoutCheckInError.CHECKIN_ALREADY_EXISTS -> CheckInPublishResult.AlreadyShared
                WorkoutCheckInError.SESSION_NOT_FOUND ->
                    CheckInPublishResult.Failed(WorkoutCheckInError.SESSION_NOT_SYNCED)
                else -> CheckInPublishResult.Failed(outcome.error)
            }
        }

    private fun currentUid(): String? =
        (authGateway.state.value as? AuthState.SignedIn)?.account?.uid
}
