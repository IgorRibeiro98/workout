package com.example.domain.auth

/**
 * A conta online do usuário, do ponto de vista do domínio do Spark.
 *
 * O [uid] é o **Firebase UID** e é o único campo obrigatório. Ele identifica *quem é o usuário* e
 * nada além disso. Em particular, ele não é e nunca substitui:
 *
 * ```text
 * Firebase UID          -> quem é o usuário (identidade da conta online)
 * localId               -> identidade de uma linha no Room, dentro deste aparelho
 * syncId                -> identidade global de uma entidade pessoal        [T16.3]
 * canonicalExerciseId   -> identidade de conteúdo do catálogo (já existe)
 * ```
 *
 * Ver `docs/architecture/identity-contract.md`.
 *
 * [displayName], [email] e [photoUrl] são **sempre** opcionais: o Google não garante nenhum dos
 * três, e uma tela que assume que existem quebra para o primeiro usuário sem foto.
 */
data class SparkAccount(
    val uid: String,
    val displayName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null
)
