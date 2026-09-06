package com.example.domain.auth

/**
 * Por que uma operação de conta falhou, no vocabulário do domínio.
 *
 * Taxonomia pequena de propósito: a UI precisa decidir entre "tente de novo", "não dá para entrar
 * neste build" e "algo inesperado", e nada além disso. Detalhe de SDK não sobe até aqui.
 */
enum class AuthError {

    /** Sem rede, ou a rede caiu no meio. O núcleo local segue funcionando; dá para tentar de novo. */
    NETWORK,

    /**
     * Este build não tem a configuração necessária (Firebase Auth ou o Web Client ID do Google).
     * Não é erro do usuário e não há nada que ele possa fazer na tela.
     */
    NOT_CONFIGURED,

    /** Nenhuma credencial Google utilizável no aparelho (nenhuma conta, Play Services ausente). */
    NO_CREDENTIAL,

    /** O provedor recusou ou respondeu algo que não dá para usar. */
    PROVIDER,

    /** Qualquer outra falha. */
    UNKNOWN
}
