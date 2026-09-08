package com.example.presentation.friends

import com.example.domain.social.SocialFieldAvailability
import com.example.domain.social.SocialProfileError

/**
 * O texto de cada classe de falha e de cada disponibilidade do perfil social (T17.2).
 *
 * Um lugar só, e mensagens do **produto** — nunca do servidor. Mensagem de servidor descreve a
 * forma do defeito para quem depura; a tela precisa dizer o que a pessoa pode fazer agora.
 *
 * Duas frases carregam quase todo o peso desta tarefa:
 *
 * - a de [SocialProfileError.NETWORK]: **nada foi enviado**. Sem ela, alguém sairia da tela de
 *   privacidade achando que o interruptor foi salvo para quando a internet voltar — e ele não foi,
 *   porque o social não tem Outbox;
 * - a de [SocialProfileError.PROFILE_UNAVAILABLE]: uma frase para quatro situações que o servidor
 *   responde igual de propósito. Tentar adivinhar qual delas foi — "vocês não são mais amigos"
 *   contra "ele desativou o social" — seria reconstruir na tela a informação que o servidor
 *   recusou dar.
 */
fun messageFor(error: SocialProfileError): String = when (error) {
    SocialProfileError.NOT_CONFIGURED ->
        "Os recursos sociais não estão disponíveis nesta versão do app."

    SocialProfileError.AUTH_REQUIRED ->
        "Entre na Conta Spark para usar os recursos sociais."

    SocialProfileError.SOCIAL_NOT_ENABLED ->
        "Ative os recursos sociais no Perfil para compartilhar seu progresso."

    SocialProfileError.SOCIAL_DISABLED ->
        "Seus recursos sociais estão desativados. Reative no Perfil. Nada foi apagado."

    SocialProfileError.PROFILE_UNAVAILABLE ->
        "Este perfil não está disponível."

    SocialProfileError.INVALID_SETTINGS ->
        "O servidor recusou esta configuração. Se continuar acontecendo, relate o problema."

    SocialProfileError.RATE_LIMITED ->
        "Muitas tentativas seguidas. Espere um minuto e tente de novo."

    SocialProfileError.REJECTED ->
        "O servidor recusou a operação. Se continuar acontecendo, relate o problema."

    SocialProfileError.UNAVAILABLE ->
        "O servidor está indisponível agora. Tente de novo em instantes."

    // A frase que impede o mal-entendido: offline, a alteração **não aconteceu**.
    SocialProfileError.NETWORK ->
        "Sem conexão, então nada foi alterado. Seus treinos e seu histórico continuam normais."
}

/**
 * O que dizer sobre a disponibilidade de um campo — na tela do **dono**, e só nela.
 *
 * As três frases são diferentes porque as três pedem coisas diferentes:
 *
 * ```text
 * AVAILABLE     "Disponível"                   nada a fazer
 * UNAVAILABLE   "Ainda não disponível"         sincronizar resolve
 * UNSUPPORTED   "Não disponível nesta versão"  sincronizar NÃO resolve
 * ```
 *
 * Colapsar as duas últimas faria a tela prometer que sincronizar publicaria o nível — e ele não
 * seria publicado, porque nível, sequência e conquistas são calculados **no aparelho**, a partir
 * de dado que não sai dele. Enquanto isso for verdade, o interruptor pode ser ligado (a preferência
 * fica guardada) e o campo continua ausente para os amigos.
 */
fun availabilityLabel(availability: SocialFieldAvailability): String = when (availability) {
    SocialFieldAvailability.AVAILABLE -> "Disponível"
    SocialFieldAvailability.UNAVAILABLE -> "Ainda não disponível"
    SocialFieldAvailability.UNSUPPORTED -> "Não disponível nesta versão"
}

/** A explicação de por que um campo não está disponível. `null` quando ele está. */
fun availabilityHint(availability: SocialFieldAvailability): String? = when (availability) {
    SocialFieldAvailability.AVAILABLE -> null

    // O caso real: a conta ainda não sincronizou treino concluído nenhum, ou o servidor ainda não
    // conhece o fuso desta pessoa. Os dois se resolvem sincronizando.
    SocialFieldAvailability.UNAVAILABLE ->
        "Seu progresso compartilhado é atualizado depois da sincronização."

    // O caso estrutural: esta informação é calculada no aparelho e não é enviada ao servidor.
    // Dizer "sincronize" aqui seria prometer uma solução que não existe.
    SocialFieldAvailability.UNSUPPORTED ->
        "Esta informação é calculada no seu aparelho e ainda não chega ao servidor, " +
            "então ela não aparece para os amigos."
}
