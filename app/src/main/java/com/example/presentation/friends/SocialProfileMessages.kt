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
 * AVAILABLE     "Disponível"    nada a fazer
 * UNAVAILABLE   "Ainda não disponível"   sincronizar resolve
 * UNSUPPORTED   "Em breve"                sincronizar NÃO resolve
 * ```
 *
 * Colapsar as duas últimas faria a tela prometer que sincronizar publicaria o nível — e, com um
 * servidor anterior à T19.2, ele não seria publicado. Desde a T19.2 o servidor deriva nível,
 * sequência e conquistas dos treinos sincronizados e dos parâmetros que este app declara; o que
 * sobra como `UNAVAILABLE` é "ainda não sincronizou / ainda não declarou", e sincronizar resolve.
 *
 * `UNSUPPORTED` (T19.H0) não pode soar como "APK antigo" ou "atualização pendente" — é uma
 * limitação arquitetural, não uma versão desatualizada. "Em breve" não promete uma data; apenas
 * evita a leitura errada de "preciso atualizar o app".
 */
fun availabilityLabel(availability: SocialFieldAvailability): String = when (availability) {
    SocialFieldAvailability.AVAILABLE -> "Disponível"
    SocialFieldAvailability.UNAVAILABLE -> "Ainda não disponível"
    SocialFieldAvailability.UNSUPPORTED -> "Em breve"
}

/** A explicação de por que um campo não está disponível. `null` quando ele está. */
fun availabilityHint(availability: SocialFieldAvailability): String? = when (availability) {
    SocialFieldAvailability.AVAILABLE -> null

    // O caso real: a conta ainda não sincronizou treino concluído nenhum, ou o servidor ainda não
    // conhece o fuso e a meta semanal desta pessoa. Tudo se resolve sincronizando e abrindo esta
    // tela conectado.
    SocialFieldAvailability.UNAVAILABLE ->
        "Seu progresso compartilhado é atualizado depois da sincronização."

    // O caso estrutural: esta informação ainda não tem autoridade remota (T19.H0). Não é a versão
    // do app, não é uma sincronização pendente e não há prazo — só não pode ser afirmada por
    // enquanto.
    SocialFieldAvailability.UNSUPPORTED ->
        "Esta informação ainda não pode ser compartilhada."
}

/**
 * O que os amigos veem do nível (T19.2C).
 *
 * O nível publicado é o que o **servidor** consegue verificar a partir dos treinos sincronizados
 * — os recordes pessoais, que valem XP no aparelho, ficam fora. Ele pode ser menor do que o nível
 * mostrado no Perfil, e a tela do dono diz isso em vez de deixar a diferença parecer um defeito.
 */
const val LEVEL_SHARING_NOTE =
    "Nível verificado pelo servidor a partir dos treinos sincronizados. Pode ser menor que o do " +
        "aparelho, porque recordes pessoais não entram."

/**
 * O rótulo de uma conquista publicada, a partir do id canônico do catálogo.
 *
 * O servidor envia só o id (`first_workout`); o título e o ícone são os do catálogo deste APK. Um
 * id que este APK não conhece não vira "conquista desconhecida" — ele é omitido, porque a lista
 * é de destaques e não de lacunas.
 *
 * Desde a T19.7B o rótulo é só o título; o ícone vem como chave de `IconKeys` em
 * [achievementIconKey], e a tela o desenha como vetor em vez de emoji na string.
 */
fun achievementLabel(achievementId: String): String? =
    com.example.domain.evolution.model.achievement.AchievementCatalog.getDefinition(achievementId)?.title

fun achievementIconKey(achievementId: String): String? =
    com.example.domain.evolution.model.achievement.AchievementCatalog.getDefinition(achievementId)?.icon
