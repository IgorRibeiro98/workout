package com.example.data.datastore

/**
 * O que fazer quando o descanso planejado chega a zero (T19.9).
 *
 * É preferência do usuário, não estado de execução: [com.example.domain.engine.WorkoutEngine]
 * continua sendo quem decide o fim do descanso (timestamp real), e esta enum só diz o que
 * acontece depois que ele chega. Nomeada pelo comportamento, não pela mecânica, porque é isso que
 * a tela de Configurações precisa mostrar ao usuário.
 */
enum class RestCompletionBehavior {
    /** Ao chegar em zero, avança para a próxima série/exercício automaticamente — padrão atual. */
    AUTO_ADVANCE,

    /** Ao chegar em zero, continua contando (negativo) até o usuário avançar manualmente. */
    MANUAL_OVERTIME
}
