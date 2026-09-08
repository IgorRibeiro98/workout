package com.example.presentation.friends

import com.example.domain.social.FriendError

/**
 * O texto de cada classe de falha do grafo social (T17.1 §80).
 *
 * Um lugar só, e mensagens do **produto** — nunca do servidor. Mensagem de servidor descreve a
 * forma do defeito para quem depura; a tela precisa dizer o que a pessoa pode fazer agora.
 *
 * A frase que mais importa é a de [FriendError.NETWORK]: **nada foi enviado**. Sem ela, alguém
 * sairia da tela achando que a solicitação ficou guardada para quando a internet voltar — e ela
 * não ficou, porque o social não tem Outbox.
 */
fun messageFor(error: FriendError): String = when (error) {
    FriendError.NOT_CONFIGURED ->
        "Os recursos sociais não estão disponíveis nesta versão do app."

    FriendError.AUTH_REQUIRED ->
        "Entre na Conta Spark para usar os recursos sociais."

    FriendError.SOCIAL_NOT_ENABLED ->
        "Ative os recursos sociais no Perfil para adicionar amigos."

    FriendError.SOCIAL_DISABLED ->
        "Seus recursos sociais estão desativados. Reative no Perfil para ver seus amigos. " +
            "Nada foi apagado."

    FriendError.PROFILE_NOT_FOUND ->
        "Esse perfil não está mais disponível."

    FriendError.SELF_REQUEST ->
        "Este é o seu próprio código."

    FriendError.REQUESTS_DISABLED ->
        "Essa pessoa não está aceitando pedidos de amizade."

    FriendError.ALREADY_FRIENDS ->
        "Vocês já são amigos."

    FriendError.REQUEST_NOT_FOUND ->
        "Esta solicitação não existe mais."

    FriendError.REQUEST_NOT_PENDING ->
        "Esta solicitação já tinha sido respondida."

    FriendError.NOT_ALLOWED ->
        "Você não pode fazer isso nesta solicitação."

    FriendError.FRIENDSHIP_NOT_FOUND ->
        "Vocês não são mais amigos."

    FriendError.RATE_LIMITED ->
        "Muitas tentativas seguidas. Espere um minuto e tente de novo."

    FriendError.REJECTED ->
        "O servidor recusou a operação. Se continuar acontecendo, relate o problema."

    FriendError.UNAVAILABLE ->
        "O servidor está indisponível agora. Tente de novo em instantes."

    // A frase que impede o mal-entendido: offline, a ação **não aconteceu** e não ficou pendente.
    FriendError.NETWORK ->
        "Sem conexão, então nada foi enviado. Seus treinos e seu histórico continuam normais."
}
