package com.example.presentation.friends

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.media.CheckInPhotoSource
import com.example.data.repository.CheckInEligibility
import com.example.data.repository.CheckInMediaUploadResult
import com.example.data.repository.CheckInPublishResult
import com.example.data.repository.WorkoutCheckInPublisher
import com.example.data.social.WorkoutCheckInContract
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.SocialError
import com.example.domain.social.SocialGateway
import com.example.domain.social.SocialOutcome
import com.example.domain.social.SocialProfileStatus
import com.example.domain.social.WorkoutCheckInError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** O que aconteceu com a última tentativa explícita de publicar. */
sealed interface CheckInShareFeedback {

    /** "Check-in compartilhado ✓" (§103). */
    data object Published : CheckInShareFeedback

    /** Já havia um check-in para este treino, e ele foi excluído (§29). */
    data object AlreadyShared : CheckInShareFeedback

    /**
     * A nuvem nunca foi adotada para os dados de treino (§21).
     *
     * O texto convida à adoção explícita da T16.4; o app **não** cria vínculo nenhum sozinho.
     */
    data object CloudNotAdopted : CheckInShareFeedback

    /**
     * O treino continua salvo, e o check-in não foi (§104).
     *
     * É o desfecho de falha de rede, de sync que não convergiu e de recusa do servidor. A mensagem
     * é a mesma de propósito: o que o usuário precisa saber é que o treino está a salvo e que ele
     * pode tentar de novo pelo Histórico.
     */
    data class NotShared(val detail: String) : CheckInShareFeedback
}

/**
 * O estado da foto escolhida para o check-in (T17.9 §43/§124/§125).
 *
 * A falha **não** avança sozinha para "publicar sem foto": ela para em [Failed], e a tela oferece
 * as duas saídas explicitamente. Publicar sem a foto que a pessoa escolheu, em silêncio, seria
 * tomar por ela a decisão que §43 diz ser dela — e ela só descobriria vendo o resultado no Feed.
 */
sealed interface CheckInPhotoState {

    /** Sem foto. O estado inicial, e o de quem removeu a que tinha escolhido (§125). */
    data object None : CheckInPhotoState

    /** Lendo e reduzindo a imagem no aparelho (§46). Nenhuma requisição saiu ainda. */
    data object Preparing : CheckInPhotoState

    /**
     * A foto está pronta e é o que a tela mostra no preview (§124).
     *
     * [bytes] é exatamente o que será enviado. A tela desenha **estes** bytes, e não o `Uri`
     * original: o preview precisa mostrar a imagem que vai ser publicada, incluindo a rotação já
     * aplicada — mostrar o original e publicar outra coisa seria uma promessa quebrada.
     */
    data class Ready(val bytes: ByteArray, val width: Int, val height: Int) : CheckInPhotoState {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Ready &&
                    width == other.width &&
                    height == other.height &&
                    bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = (bytes.contentHashCode() * 31 + width) * 31 + height
    }

    /** A foto foi enviada e está esperando a publicação anexá-la (§33/§38). */
    data class Uploaded(
        val mediaId: String,
        val bytes: ByteArray,
        val width: Int,
        val height: Int
    ) : CheckInPhotoState {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Uploaded &&
                    mediaId == other.mediaId &&
                    width == other.width &&
                    height == other.height &&
                    bytes.contentEquals(other.bytes))

        override fun hashCode(): Int =
            ((mediaId.hashCode() * 31 + bytes.contentHashCode()) * 31 + width) * 31 + height
    }

    /** Enviando ao servidor. */
    data class Uploading(val bytes: ByteArray, val width: Int, val height: Int) :
        CheckInPhotoState {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Uploading &&
                    width == other.width &&
                    height == other.height &&
                    bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = (bytes.contentHashCode() * 31 + width) * 31 + height
    }

    /**
     * O envio falhou (§43).
     *
     * A tela mostra "Não conseguimos enviar a foto." com **duas** ações: tentar de novo e publicar
     * sem a foto. Nenhuma das duas acontece sozinha.
     */
    data class Failed(
        val message: String,
        val bytes: ByteArray,
        val width: Int,
        val height: Int
    ) : CheckInPhotoState {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Failed &&
                    message == other.message &&
                    width == other.width &&
                    height == other.height &&
                    bytes.contentEquals(other.bytes))

        override fun hashCode(): Int =
            ((message.hashCode() * 31 + bytes.contentHashCode()) * 31 + width) * 31 + height
    }
}

data class WorkoutCheckInUiState(
    val sessionId: Long? = null,
    val eligibility: CheckInEligibility = CheckInEligibility.Unavailable,
    val isSocialActive: Boolean = false,
    /** O diálogo de confirmação está aberto. Abrir **não** publica nada (§102/§144). */
    val isConfirming: Boolean = false,
    val isPublishing: Boolean = false,
    /** `true` depois de publicar nesta tela, ou quando o servidor disse que já existia. */
    val isShared: Boolean = false,
    /**
     * A legenda em digitação (T17.9 §7/§123).
     *
     * Rascunho de tela, e nada mais (§146): não vai para Room, DataStore nem para o servidor antes
     * da confirmação. Fechar o compositor a descarta, e isso é o esperado — uma legenda que
     * ressuscita dias depois seria pior do que uma que se perde.
     */
    val caption: String = "",
    val photo: CheckInPhotoState = CheckInPhotoState.None,
    val feedback: CheckInShareFeedback? = null
) {

    /** Quantos caracteres ainda cabem. Conta code points, como o servidor (§7). */
    val captionRemaining: Int
        get() = WorkoutCheckInContract.Limits.MAX_CAPTION_LENGTH - caption.codePointCount()

    /** `true` quando a legenda passou do teto — o botão de publicar fica desabilitado. */
    val isCaptionTooLong: Boolean get() = captionRemaining < 0


    /**
     * Se o CTA aparece (§100).
     *
     * Backend configurado **e** conta conectada (dentro de [eligibility]), Social ativo, sessão
     * concluída e dentro da janela estimada localmente. O servidor revalida tudo isso na
     * publicação — esconder o botão é conveniência, nunca autorização (§27).
     */
    val canShare: Boolean
        get() = eligibility == CheckInEligibility.Eligible && isSocialActive && !isShared

    /** O compositor pode publicar agora? Legenda válida, e nenhuma foto em trânsito. */
    val canConfirm: Boolean
        get() = canShare &&
            !isPublishing &&
            !isCaptionTooLong &&
            photo !is CheckInPhotoState.Preparing &&
            photo !is CheckInPhotoState.Uploading
}

/** Code points, e não `length`: um emoji ocupa dois `char` e um caractere na tela (§7). */
private fun String.codePointCount(): Int = codePointCount(0, length)

/**
 * Os bytes que um estado de foto carrega, quando ele carrega algum.
 *
 * Uma função só, para que o dia em que um estado novo carregar bytes não deixe um `when` para trás
 * — que é como o "Tentar novamente" perderia a foto em um caminho e não em outro.
 */
private fun CheckInPhotoState.carriedBytes(): Triple<ByteArray, Int, Int>? = when (this) {
    is CheckInPhotoState.Ready -> Triple(bytes, width, height)
    is CheckInPhotoState.Uploading -> Triple(bytes, width, height)
    is CheckInPhotoState.Uploaded -> Triple(bytes, width, height)
    is CheckInPhotoState.Failed -> Triple(bytes, width, height)
    CheckInPhotoState.None, CheckInPhotoState.Preparing -> null
}

/**
 * O CTA de "Compartilhar check-in", usado pelo Resumo e pelo Histórico (T17.8, Etapa 3).
 *
 * ## A ordem é a garantia
 *
 * ```text
 * treino COMPLETED e salvo   ← já aconteceu antes desta ViewModel existir
 *        │
 *        ▼
 * CTA aparece                ← [prepare]
 *        │  toque
 *        ▼
 * preview do que será e do que NÃO será compartilhado   ← [requestShare], sem rede
 *        │  confirmação
 *        ▼
 * publicação                 ← [confirmShare]
 * ```
 *
 * Concluir o treino nunca chega ao passo de baixo sozinho (§3/§99). O primeiro toque abre o
 * preview e **não** faz requisição nenhuma (§102/§144); só a confirmação publica.
 *
 * ## O `clientRequestId`
 *
 * Nasce quando a operação explícita começa — a abertura do preview — e é **reusado** enquanto ela
 * durar (§109). É ele que faz o toque duplo, o retry após resposta perdida e a segunda tentativa
 * depois do ciclo de sync convergirem em uma publicação só. Uma tentativa posterior, depois de
 * fechar o preview, usa um id novo; a `UNIQUE` por sessão no servidor continua impedindo a
 * duplicação.
 *
 * ## Troca de conta
 *
 * O estado é limpo quando o `uid` muda, e a resposta de uma requisição iniciada pela conta
 * anterior é descartada pelo publisher — nunca "Publicado!" para a conta B sobre o que a conta A
 * pediu (§114).
 */
class WorkoutCheckInViewModel(
    private val publisher: WorkoutCheckInPublisher,
    private val socialGateway: SocialGateway,
    private val authGateway: AuthGateway,
    /**
     * De onde vêm os bytes de uma foto escolhida (T17.9 §46).
     *
     * Opcional porque o compositor sem foto continua funcionando sem ele — e porque um build sem
     * a capacidade de escolher imagem não deve falhar ao montar a ViewModel.
     */
    private val photoSource: CheckInPhotoSource? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkoutCheckInUiState())
    val uiState: StateFlow<WorkoutCheckInUiState> = _uiState.asStateFlow()

    /** Reusado enquanto a mesma intenção estiver em andamento (§109). */
    private var pendingRequestId: String? = null

    /**
     * Reusado enquanto a **mesma foto** estiver em andamento (T17.9 §36).
     *
     * É ele que faz "tentar de novo" depois de uma falha de rede convergir no mesmo `mediaId` em
     * vez de deixar dois arquivos no servidor — um deles órfão, ocupando a quota da pessoa até o
     * cleanup. Escolher **outra** foto gera um identificador novo.
     */
    private var pendingUploadId: String? = null
    private var observedUid: String? = null

    init {
        viewModelScope.launch {
            authGateway.state.collect { state ->
                val newUid = (state as? AuthState.SignedIn)?.account?.uid
                if (newUid == observedUid) return@collect
                observedUid = newUid

                val sessionId = _uiState.value.sessionId
                pendingRequestId = null
                // §145/§147 — o rascunho, a foto e o identificador de upload são da conta
                // anterior. Nada disso sobrevive à troca.
                pendingUploadId = null
                _uiState.value = WorkoutCheckInUiState(sessionId = sessionId)
                if (sessionId != null) prepare(sessionId)
            }
        }
    }

    /** Calcula se o CTA aparece para [sessionId]. Nenhuma publicação acontece aqui. */
    fun prepare(sessionId: Long) {
        resetIfDifferentSession(sessionId)

        viewModelScope.launch {
            val uid = currentUid()
            val eligibility = publisher.eligibility(sessionId)

            // O perfil social só é consultado quando o resto já permite: sem conta, sem backend ou
            // com o treino fora da janela, não há CTA a mostrar e a requisição seria desperdício.
            val socialActive = if (eligibility == CheckInEligibility.Eligible) {
                isSocialActive()
            } else {
                false
            }

            if (currentUid() != uid) return@launch
            _uiState.update { it.copy(eligibility = eligibility, isSocialActive = socialActive) }
        }
    }

    /**
     * Primeiro toque no CTA: abre o preview.
     *
     * **Nenhuma requisição sai daqui** (§144). O `clientRequestId` é criado agora porque é agora
     * que a intenção do usuário começa.
     */
    fun requestShare() {
        if (!_uiState.value.canShare) return
        pendingRequestId = pendingRequestId ?: UUID.randomUUID().toString()
        _uiState.update { it.copy(isConfirming = true, feedback = null) }
    }

    /**
     * Prepara e, se der, abre o preview — o caminho do Histórico (§23/§105).
     *
     * Continua **sem publicar nada**: o que ele faz no melhor caso é abrir o mesmo preview que o
     * Resumo abre, e a confirmação continua sendo um toque separado (§102). Quando a sessão não é
     * elegível, ele explica em vez de abrir um diálogo que não levaria a lugar nenhum.
     */
    fun startShareFor(sessionId: Long) {
        resetIfDifferentSession(sessionId)
        _uiState.update { it.copy(feedback = null) }

        viewModelScope.launch {
            val uid = currentUid()
            val eligibility = publisher.eligibility(sessionId)
            val socialActive = if (eligibility == CheckInEligibility.Eligible) {
                isSocialActive()
            } else {
                false
            }
            if (currentUid() != uid) return@launch

            _uiState.update { it.copy(eligibility = eligibility, isSocialActive = socialActive) }

            if (_uiState.value.canShare) {
                requestShare()
            } else {
                _uiState.update {
                    it.copy(feedback = CheckInShareFeedback.NotShared(explain(eligibility, socialActive)))
                }
            }
        }
    }

    private fun explain(eligibility: CheckInEligibility, socialActive: Boolean): String = when {
        _uiState.value.isShared -> "Este treino já teve um check-in publicado."
        eligibility == CheckInEligibility.OutsideWindow ->
            "O prazo para compartilhar o check-in deste treino já passou."
        eligibility == CheckInEligibility.NotCompleted ->
            "Só um treino concluído pode virar check-in."
        eligibility == CheckInEligibility.SessionMissing ->
            "Este treino não está mais disponível."
        eligibility == CheckInEligibility.Unavailable ->
            "Entre na Conta Spark para compartilhar check-ins."
        !socialActive ->
            "Ative os recursos sociais no Perfil para compartilhar check-ins."
        else -> "Não é possível compartilhar o check-in deste treino."
    }

    /** A legenda em digitação (§123). Nada sai daqui: é rascunho de tela (§146). */
    fun onCaptionChanged(value: String) {
        _uiState.update { it.copy(caption = value) }
    }

    /**
     * A pessoa escolheu uma foto no Photo Picker (§44/§124).
     *
     * A leitura e a redução acontecem aqui, no aparelho, **antes** de qualquer requisição: o
     * preview mostra exatamente os bytes que serão enviados (§124), e não o arquivo original — a
     * rotação já está aplicada, e mostrar uma coisa para publicar outra seria uma promessa
     * quebrada.
     */
    fun onPhotoPicked(uri: Uri) {
        val source = photoSource ?: return
        pendingUploadId = UUID.randomUUID().toString()
        _uiState.update { it.copy(photo = CheckInPhotoState.Preparing, feedback = null) }

        viewModelScope.launch {
            val uid = currentUid()
            val optimized = source.optimize(uri)
            if (currentUid() != uid) return@launch

            _uiState.update {
                it.copy(
                    photo = if (optimized == null) {
                        CheckInPhotoState.None
                    } else {
                        CheckInPhotoState.Ready(optimized.bytes, optimized.width, optimized.height)
                    },
                    feedback = if (optimized == null) {
                        CheckInShareFeedback.NotShared(
                            "Não conseguimos ler essa foto. Escolha outra ou publique sem foto."
                        )
                    } else {
                        it.feedback
                    }
                )
            }
        }
    }

    /** Remover a foto antes de publicar é permitido (§125). */
    fun removePhoto() {
        pendingUploadId = null
        _uiState.update { it.copy(photo = CheckInPhotoState.None) }
    }

    /**
     * "Tentar novamente" depois de uma falha de envio (§43).
     *
     * O mesmo `clientUploadId`: é a mesma foto e a mesma intenção, e reusá-lo é o que impede a
     * segunda tentativa de virar um segundo arquivo no servidor (§36).
     */
    fun retryPhotoUpload() {
        val failed = _uiState.value.photo as? CheckInPhotoState.Failed ?: return
        _uiState.update {
            it.copy(photo = CheckInPhotoState.Ready(failed.bytes, failed.width, failed.height))
        }
        confirmShare()
    }

    /**
     * "Publicar sem foto" depois de uma falha de envio (§43).
     *
     * A decisão é **explícita**: só este método descarta a foto, e ele só é chamado por um toque
     * do usuário no botão que diz exatamente isso.
     */
    fun publishWithoutPhoto() {
        if (_uiState.value.photo !is CheckInPhotoState.Failed) return
        pendingUploadId = null
        _uiState.update { it.copy(photo = CheckInPhotoState.None) }
        confirmShare()
    }

    fun cancelShare() {
        // Fechar o preview encerra a intenção: uma tentativa posterior é outra operação, com id
        // novo. A `UNIQUE` por sessão no servidor continua impedindo a duplicação.
        //
        // O rascunho vai junto (§146). A foto já enviada fica `PENDING` no servidor e expira
        // sozinha em uma hora (§38/§39) — não há nada a limpar daqui, e uma rota de "cancelar
        // upload" seria uma superfície a mais para o mesmo efeito.
        pendingRequestId = null
        pendingUploadId = null
        _uiState.update {
            it.copy(isConfirming = false, caption = "", photo = CheckInPhotoState.None)
        }
    }

    /**
     * A confirmação explícita. É este — e só este — caminho que publica (§102).
     *
     * Com foto, são **duas** operações em sequência, e a ordem importa (§33):
     *
     * ```text
     * upload da foto  ──falhou──▶ CheckInPhotoState.Failed  ← a decisão volta para o usuário (§43)
     *        │
     *        ▼ ok
     * POST do check-in com o mediaId
     * ```
     *
     * Falhar no upload **não** publica sem a foto. §43 é explícito: a escolha entre tentar de novo
     * e publicar sem ela é do usuário, e um caminho que seguisse em frente a tomaria por ele — com
     * o resultado aparecendo no Feed dos amigos antes de ele perceber.
     */
    fun confirmShare() {
        val state = _uiState.value
        val sessionId = state.sessionId ?: return
        if (!state.canConfirm) return

        val requestId = pendingRequestId ?: UUID.randomUUID().toString()
        pendingRequestId = requestId
        _uiState.update { it.copy(isPublishing = true, isConfirming = false) }

        viewModelScope.launch {
            val mediaId = when (val photo = state.photo) {
                is CheckInPhotoState.Uploaded -> photo.mediaId
                is CheckInPhotoState.Ready -> {
                    val uploaded = uploadPhoto(sessionId, photo)
                    // `null` significa que o envio não deu certo **e** que o estado já foi
                    // atualizado para `Failed` — a tela mostra as duas saídas, e nada é publicado.
                    uploaded ?: return@launch
                }
                is CheckInPhotoState.Failed -> return@launch
                else -> null
            }

            val caption = state.caption.trim().takeIf { it.isNotEmpty() }

            when (val result = publisher.publish(sessionId, requestId, caption, mediaId)) {
                is CheckInPublishResult.Published -> {
                    pendingRequestId = null
                    pendingUploadId = null
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            isShared = true,
                            feedback = CheckInShareFeedback.Published
                        )
                    }
                }

                CheckInPublishResult.AlreadyShared -> {
                    pendingRequestId = null
                    pendingUploadId = null
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            isShared = true,
                            feedback = CheckInShareFeedback.AlreadyShared
                        )
                    }
                }

                CheckInPublishResult.CloudNotAdopted -> finishWith(
                    CheckInShareFeedback.CloudNotAdopted
                )

                CheckInPublishResult.SessionNotSynced -> finishWith(
                    CheckInShareFeedback.NotShared(
                        "Seu treino continua salvo. Não conseguimos sincronizá-lo a tempo " +
                            "para compartilhar o check-in. Tente novamente pelo Histórico."
                    )
                )

                is CheckInPublishResult.NotEligible -> {
                    // O estado mudou entre a preparação e a confirmação. Recalcular é mais honesto
                    // do que mostrar um erro sobre uma condição que a tela pode simplesmente
                    // deixar de oferecer.
                    prepare(sessionId)
                    finishWith(
                        CheckInShareFeedback.NotShared(
                            "Seu treino continua salvo. Este treino não pode mais receber um check-in."
                        )
                    )
                }

                is CheckInPublishResult.Failed -> {
                    // §43 — se a recusa foi sobre a **foto**, a publicação não aconteceu e a
                    // decisão volta para o usuário em vez de virar uma publicação sem ela.
                    if (result.error == WorkoutCheckInError.MEDIA_NOT_FOUND) {
                        failPhoto("Não conseguimos enviar a foto.")
                    } else {
                        finishWith(CheckInShareFeedback.NotShared(messageFor(result.error)))
                    }
                }

                // A conta trocou durante o voo: nada é dito, porque não há nada verdadeiro a
                // dizer para quem está na tela agora (§114/§147).
                CheckInPublishResult.AccountChanged -> {
                    pendingRequestId = null
                    pendingUploadId = null
                    _uiState.update { it.copy(isPublishing = false) }
                }
            }
        }
    }

    /**
     * Envia a foto e devolve o `mediaId`, ou `null` quando o envio não deu certo.
     *
     * No caminho de falha ele já deixa o estado em [CheckInPhotoState.Failed], que é o que a tela
     * usa para mostrar "Tentar novamente" e "Publicar sem foto" (§43).
     */
    private suspend fun uploadPhoto(
        sessionId: Long,
        photo: CheckInPhotoState.Ready
    ): String? {
        val uploadId = pendingUploadId ?: UUID.randomUUID().toString()
        pendingUploadId = uploadId
        _uiState.update {
            it.copy(photo = CheckInPhotoState.Uploading(photo.bytes, photo.width, photo.height))
        }

        return when (val result = publisher.uploadPhoto(sessionId, uploadId, photo.bytes)) {
            is CheckInMediaUploadResult.Uploaded -> {
                _uiState.update {
                    it.copy(
                        photo = CheckInPhotoState.Uploaded(
                            mediaId = result.media.mediaId,
                            bytes = photo.bytes,
                            width = photo.width,
                            height = photo.height
                        )
                    )
                }
                result.media.mediaId
            }

            is CheckInMediaUploadResult.Failed -> {
                failPhoto(photoMessageFor(result.error), photo)
                null
            }

            is CheckInMediaUploadResult.NotEligible -> {
                failPhoto("Este treino não pode mais receber uma foto.", photo)
                null
            }

            // §147/§149 — a conta trocou durante o envio. Nada é dito e nada é publicado: a foto
            // que A escolheu não pode virar publicação de B.
            CheckInMediaUploadResult.AccountChanged -> {
                pendingRequestId = null
                pendingUploadId = null
                _uiState.update {
                    it.copy(isPublishing = false, photo = CheckInPhotoState.None)
                }
                null
            }
        }
    }

    /**
     * Leva o estado da foto para [CheckInPhotoState.Failed], preservando os bytes (§43).
     *
     * Os bytes precisam sobreviver **de qualquer estado** que os carregue — `Ready`, `Uploading` e
     * também `Uploaded`. O último é o caso que importa e o menos óbvio: o upload deu certo, o
     * servidor recusou o anexo (mídia expirada, de outra sessão, já usada), e sem preservar os
     * bytes o "Tentar novamente" não teria o que reenviar — a pessoa teria de escolher a foto de
     * novo por um erro que não foi dela.
     */
    private fun failPhoto(message: String, photo: CheckInPhotoState.Ready? = null) {
        _uiState.update { state ->
            val carried = photo?.let { Triple(it.bytes, it.width, it.height) }
                ?: state.photo.carriedBytes()

            state.copy(
                isPublishing = false,
                photo = carried?.let { (bytes, width, height) ->
                    CheckInPhotoState.Failed(message, bytes, width, height)
                } ?: CheckInPhotoState.None
            )
        }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    /**
     * Troca de sessão zera o que era daquela sessão.
     *
     * Sem isto, publicar o check-in do treino de hoje e depois abrir outro treino no Histórico
     * mostraria "Check-in compartilhado" para um treino que nunca foi publicado — e o
     * `clientRequestId` da intenção anterior seria reusado para uma sessão diferente, que o
     * servidor recusa como conflito (§32).
     */
    private fun resetIfDifferentSession(sessionId: Long) {
        if (_uiState.value.sessionId == sessionId) return
        pendingRequestId = null
        // O rascunho é da sessão que o usuário estava vendo. Carregá-lo para outra publicaria a
        // legenda de um treino sobre outro (§146).
        pendingUploadId = null
        _uiState.value = WorkoutCheckInUiState(sessionId = sessionId)
    }

    private fun finishWith(feedback: CheckInShareFeedback) {
        // O `clientRequestId` continua guardado: enquanto a tela estiver aberta, tentar de novo é
        // a **mesma** intenção, e reusar o id é o que impede a segunda tentativa de virar uma
        // segunda publicação caso a primeira tenha chegado ao servidor sem a resposta voltar.
        _uiState.update { it.copy(isPublishing = false, feedback = feedback) }
    }

    private suspend fun isSocialActive(): Boolean = when (val outcome = socialGateway.profile()) {
        is SocialOutcome.Success -> outcome.profile.status == SocialProfileStatus.ACTIVE
        SocialOutcome.NotEnabled -> false
        // Sem servidor não afirmamos que o Social está ativo: oferecer o botão levaria a uma
        // recusa logo em seguida.
        is SocialOutcome.Failure -> outcome.error == SocialError.ALREADY_ENABLED
    }

    /** O que dizer quando a **foto** falhou. Sempre com as duas saídas de §43 na tela. */
    private fun photoMessageFor(error: WorkoutCheckInError): String = when (error) {
        WorkoutCheckInError.NETWORK -> "Não conseguimos enviar a foto. Sem conexão agora."
        WorkoutCheckInError.INVALID_IMAGE ->
            "Não conseguimos enviar a foto: o servidor não aceitou esse formato de imagem."
        WorkoutCheckInError.MEDIA_TOO_LARGE ->
            "Não conseguimos enviar a foto: ela é grande demais."
        WorkoutCheckInError.MEDIA_QUOTA_EXCEEDED ->
            "Não conseguimos enviar a foto: o espaço de fotos da sua conta está cheio."
        WorkoutCheckInError.RATE_LIMITED ->
            "Não conseguimos enviar a foto: muitos envios seguidos. Tente em instantes."
        else -> "Não conseguimos enviar a foto."
    }

    private fun messageFor(error: WorkoutCheckInError): String = when (error) {
        WorkoutCheckInError.NETWORK ->
            "Seu treino continua salvo. Sem conexão para compartilhar o check-in agora. " +
                "Tente novamente pelo Histórico."
        WorkoutCheckInError.SOCIAL_NOT_ENABLED ->
            "Ative os recursos sociais no Perfil para compartilhar check-ins."
        WorkoutCheckInError.CHECKIN_WINDOW_EXPIRED ->
            "Seu treino continua salvo. O prazo para compartilhar o check-in deste treino já passou."
        WorkoutCheckInError.SESSION_NOT_COMPLETED ->
            "Seu treino continua salvo. Só um treino concluído pode virar check-in."
        WorkoutCheckInError.RATE_LIMITED ->
            "Seu treino continua salvo. Muitas publicações seguidas — tente em instantes."
        WorkoutCheckInError.AUTH_REQUIRED ->
            "Entre na Conta Spark para compartilhar check-ins."
        WorkoutCheckInError.INVALID_CONTENT ->
            "Seu treino continua salvo. A legenda tem caracteres que não podemos publicar."
        else ->
            "Seu treino continua salvo. Não foi possível compartilhar o check-in. " +
                "Tente novamente pelo Histórico."
    }

    private fun currentUid(): String? =
        (authGateway.state.value as? AuthState.SignedIn)?.account?.uid
}
