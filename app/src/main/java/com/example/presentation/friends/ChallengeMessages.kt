package com.example.presentation.friends

import com.example.domain.social.Challenge
import com.example.domain.social.ChallengeError
import com.example.domain.social.ChallengeStatus
import com.example.domain.social.ChallengeType

/**
 * Os textos dos desafios (T17.3 §159–§180).
 *
 * Um lugar só, e mensagens do **produto** — nunca do servidor. Mensagem de servidor descreve a
 * forma do defeito para quem depura; a tela precisa dizer o que a pessoa pode fazer agora.
 *
 * Duas frases aqui carregam decisão de produto, e não estilo:
 *
 * - a de [ChallengeError.NETWORK], que diz **nada foi enviado**. Sem ela, alguém sairia da tela
 *   achando que o aceite ficou guardado para quando a internet voltar — e não ficou, porque o
 *   social não tem Outbox;
 * - a de [RESULT_MAY_CHANGE_NOTICE], que diz que o resultado ainda pode mudar. Prometer um
 *   resultado irrevogável que a sincronização de amanhã pode alterar seria mentir sobre a única
 *   coisa que o desafio afirma.
 */

// --------------------------------------------------------------------------------- rótulos

const val CHALLENGES_TITLE = "Desafios"
const val CHALLENGES_ENTRY_LABEL = "Desafios"
const val CREATE_CHALLENGE_LABEL = "Criar desafio"
const val CHALLENGE_INVITES_TITLE = "Convites de desafio"
const val ACCEPT_CHALLENGE_LABEL = "Aceitar"
const val DECLINE_CHALLENGE_LABEL = "Recusar"
const val LEAVE_CHALLENGE_LABEL = "Sair do desafio"
const val CANCEL_CHALLENGE_LABEL = "Cancelar desafio"
const val CHALLENGES_LIST_DESCRIPTION = "Seus desafios"

const val SECTION_ACTIVE = "Ativos"
const val SECTION_UPCOMING = "Próximos"
const val SECTION_FINISHED = "Encerrados"

const val CHALLENGES_EMPTY_MESSAGE =
    "Você ainda não participa de nenhum desafio. Crie um e convide seus amigos."

const val CHALLENGE_INVITES_EMPTY_MESSAGE = "Nenhum convite de desafio pendente."

/** O texto que a tela de convite mostra **antes** do aceite (§124/§168). */
const val CHALLENGE_CONSENT_NOTICE =
    "Ao participar, os outros participantes verão seu progresso neste desafio.\n\n" +
        "Seus treinos, exercícios, cargas e medidas continuam privados."

/** O aviso de convergência, em desafio encerrado (§74/§179). */
const val RESULT_MAY_CHANGE_NOTICE =
    "O resultado pode atualizar se algum participante ainda estiver sincronizando " +
        "treinos feitos no período."

const val CANCEL_CONFIRM_TITLE = "Cancelar desafio?"
const val CANCEL_CONFIRM_BODY =
    "O desafio será encerrado para todos e não terá resultado."

const val LEAVE_CONFIRM_TITLE = "Sair do desafio?"
const val LEAVE_CONFIRM_BODY =
    "Seu progresso deixará de aparecer para os participantes."

const val BACK_LABEL = "Voltar"

// --------------------------------------------------------------------------------- tipos

/** O nome do tipo, na tela (§160). */
fun labelFor(type: ChallengeType): String = when (type) {
    ChallengeType.WORKOUTS_COMPLETED -> "Treinos realizados"
    ChallengeType.ACTIVE_DAYS -> "Dias ativos"
}

/**
 * A explicação curta de cada tipo (§161/§162).
 *
 * Ela existe porque a diferença entre os dois **não é óbvia**, e é justamente onde uma pessoa se
 * frustraria: dois treinos no mesmo dia valem 2 num, e 1 no outro.
 */
fun explanationFor(type: ChallengeType): String = when (type) {
    ChallengeType.WORKOUTS_COMPLETED ->
        "Cada treino concluído durante o período vale 1."
    ChallengeType.ACTIVE_DAYS ->
        "Um ou mais treinos concluídos no mesmo dia contam como 1 dia ativo."
}

/** O rótulo de estado, na lista e no cabeçalho. */
fun labelFor(status: ChallengeStatus): String = when (status) {
    ChallengeStatus.UPCOMING -> "Ainda não começou"
    ChallengeStatus.ACTIVE -> "Em andamento"
    ChallengeStatus.ENDED -> "Encerrado"
    ChallengeStatus.CANCELLED -> "Cancelado"
    ChallengeStatus.VOID -> "Sem participantes suficientes"
}

/**
 * A meta, escrita por extenso.
 *
 * O tipo aparece junto porque "12" sozinho não diz se são treinos ou dias — e as duas metas se
 * parecem na tela.
 */
fun targetLabelFor(challenge: Challenge): String = when (challenge.type) {
    ChallengeType.WORKOUTS_COMPLETED -> "Meta: ${challenge.target} treinos"
    ChallengeType.ACTIVE_DAYS -> "Meta: ${challenge.target} dias ativos"
}

/**
 * O período, em `dd/MM`.
 *
 * As datas vêm como `AAAA-MM-DD` do servidor — datas de calendário, sem fuso. Formatá-las com um
 * `Instant` local as deslocaria: elas **não** são instantes, e "10/09" é 10/09 para todos os
 * participantes, que é exatamente o ponto de o desafio ter um fuso só.
 */
fun periodLabelFor(challenge: Challenge): String =
    "${shortDate(challenge.startDate)} — ${shortDate(challenge.endDate)}"

/** `2026-09-10` → `10/09`. Sem fuso, sem `Instant`: é aritmética de texto sobre uma data. */
fun shortDate(isoDate: String): String {
    val parts = isoDate.split("-")
    return if (parts.size == 3) "${parts[2]}/${parts[1]}" else isoDate
}

/** "Começa em 10/09" — o que a tela de um desafio que ainda não começou mostra (§157/§171). */
fun startsAtLabelFor(challenge: Challenge): String =
    "Começa em ${shortDate(challenge.startDate)}"

/** "3 participantes" / "1 participante". */
fun participantsLabelFor(count: Int): String =
    if (count == 1) "1 participante" else "$count participantes"

/** "2 convites pendentes" — só o criador vê (§172). */
fun pendingInvitesLabelFor(count: Int): String =
    if (count == 1) "1 convite pendente" else "$count convites pendentes"

/** "1 participante saiu" — metadado opcional, sem nomes (§96). */
fun withdrawnLabelFor(count: Int): String =
    if (count == 1) "1 participante saiu" else "$count participantes saíram"

// --------------------------------------------------------------------------------- erros

fun messageFor(error: ChallengeError): String = when (error) {
    ChallengeError.NOT_CONFIGURED ->
        "Os recursos sociais não estão disponíveis nesta versão do app."

    ChallengeError.AUTH_REQUIRED ->
        "Entre na Conta Spark para usar os desafios."

    ChallengeError.SOCIAL_NOT_ENABLED ->
        "Ative os recursos sociais no Perfil para participar de desafios."

    ChallengeError.SOCIAL_DISABLED ->
        "Seus recursos sociais estão desativados. Reative no Perfil para ver seus desafios. " +
            "Nada foi apagado."

    ChallengeError.NOT_FOUND ->
        "Este desafio não está mais disponível para você."

    ChallengeError.INVITATION_NOT_FOUND ->
        "Este convite não existe mais."

    ChallengeError.INVITATION_NOT_PENDING ->
        "Este convite já tinha sido respondido."

    ChallengeError.ALREADY_STARTED ->
        "Este desafio já começou, então não é mais possível entrar."

    ChallengeError.CANCELLED ->
        "Este desafio foi cancelado por quem o criou."

    ChallengeError.NOT_CREATOR ->
        "Apenas quem criou o desafio pode cancelá-lo."

    ChallengeError.CANNOT_LEAVE_AS_CREATOR ->
        "Você criou este desafio. Para encerrá-lo, cancele-o — ele acaba para todos."

    ChallengeError.PARTICIPANT_NOT_AVAILABLE ->
        "Um dos amigos escolhidos não está disponível para participar agora."

    ChallengeError.TOO_MANY_PARTICIPANTS ->
        "Este desafio já tem o máximo de participantes."

    ChallengeError.TOO_MANY_OPEN_CHALLENGES ->
        "Você já tem desafios demais em andamento. Encerre ou cancele algum antes de criar outro."

    ChallengeError.INVALID_CHALLENGE ->
        "Confira o nome, a meta e o período: o desafio precisa começar a partir de amanhã."

    ChallengeError.RATE_LIMITED ->
        "Muitas tentativas seguidas. Espere um minuto e tente de novo."

    ChallengeError.UNAVAILABLE ->
        "O servidor está indisponível agora. Tente de novo em instantes."

    ChallengeError.REJECTED ->
        "O servidor recusou a operação. Se continuar acontecendo, relate o problema."

    // A frase que impede o mal-entendido: offline, a ação **não aconteceu** e não ficou pendente.
    // A segunda metade é a que evita o pânico: o núcleo do Spark não depende disto.
    ChallengeError.NETWORK ->
        "Sem conexão, então nada foi enviado. Seus treinos e seu histórico continuam normais."
}
