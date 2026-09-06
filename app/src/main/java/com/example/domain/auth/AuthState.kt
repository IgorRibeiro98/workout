package com.example.domain.auth

/**
 * O estado da conta Spark, explícito.
 *
 * `null` não é um estado: "talvez deslogado, talvez entrando, talvez falhou" é exatamente a
 * ambiguidade que faz a UI mostrar a coisa errada. Cada situação tem um nome.
 *
 * Ninguém guarda uma cópia disto: o estado é derivado do `FirebaseAuth` (que é a autoridade da
 * sessão) mais a operação em andamento. Uma sessão restaurada, um logout feito em outro lugar ou
 * uma credencial invalidada aparecem aqui sozinhos.
 */
sealed interface AuthState {

    /** Sem conta. O núcleo do Spark funciona por completo neste estado — é o padrão, não uma falha. */
    data object SignedOut : AuthState

    /** Autenticação em andamento, iniciada por toque explícito. Bloqueia um segundo fluxo. */
    data object SigningIn : AuthState

    /** Conta conectada. */
    data class SignedIn(val account: SparkAccount) : AuthState

    /** Saída em andamento. */
    data object SigningOut : AuthState

    /**
     * A última tentativa falhou de forma recuperável.
     *
     * Não é o estado de um cancelamento: fechar o seletor de contas leva a [SignedOut], porque o
     * usuário fez o que queria. Também não bloqueia nada — o núcleo local continua intacto e o
     * usuário pode tentar de novo.
     */
    data class Error(val error: AuthError) : AuthState
}
