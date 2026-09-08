package com.example.data.social

/**
 * O contrato do domínio social, do lado do Android (T17.0).
 *
 * O espelho TypeScript é `backend/src/modules/social/social.contract.ts`, e a descrição legível
 * está em `docs/architecture/social-domain.md`. Mudou o contrato de um lado, muda do outro — e as
 * duas versões precisam do mesmo commit.
 *
 * ## Sem `socialSchemaVersion`
 *
 * O social não versiona payload próprio, e isso é decisão, não esquecimento. O que ele troca é um
 * documento pequeno e estável de campos escalares — nada parecido com o snapshot de treino, cuja
 * `entitySchemaVersion` existe porque um agregado ganha campos a cada release. `/v1` já é a versão
 * do protocolo; um número a mais seria cerimônia sem consumidor, e cerimônia sem consumidor
 * envelhece errada.
 */
object SocialContract {

    /** `GET /v1/social/me` — o perfil da conta autenticada, ou `{ "enabled": false }`. */
    const val ME_PATH = "v1/social/me"

    /** `POST` — o único caminho que cria identidade social. Idempotente. */
    const val ACTIVATE_PATH = "v1/social/me/activate"

    /** `PATCH` — o nome social. Identidade não muda por aqui. */
    const val PROFILE_PATH = "v1/social/me"

    /** `PATCH` — privacidade. Parcial: o que não for enviado continua como está. */
    const val PRIVACY_PATH = "v1/social/me/privacy"

    /** `POST` — desativa. Não apaga nada. */
    const val DISABLE_PATH = "v1/social/me/disable"

    /** `POST` — reativa, com a mesma identidade. */
    const val ENABLE_PATH = "v1/social/me/enable"

    /** `GET /v1/social/activity` — atividade recente dos amigos (T17.4). */
    const val ACTIVITY_PATH = "v1/social/activity"

    /** `GET /v1/social/rankings/last-7-days` — ranking contextual semanal entre amigos (T17.4). */
    const val RANKINGS_LAST_7_DAYS_PATH = "v1/social/rankings/last-7-days"

    /**
     * Os limites do nome social, iguais aos do servidor.
     *
     * Existem aqui para a tela poder desabilitar o botão antes da requisição — nunca para
     * *substituir* a validação do servidor, que é a que vale. Um cliente que valida sozinho é um
     * cliente que, um release depois, aceita o que o servidor recusa.
     *
     * A contagem é em **code points**: `"💪".length` é 2 em Kotlin, e um limite medido em `Char`
     * significaria coisas diferentes conforme o alfabeto — inclusive diferente do servidor.
     */
    const val DISPLAY_NAME_MIN_LENGTH = 2
    const val DISPLAY_NAME_MAX_LENGTH = 40

    /**
     * O nome social já tem forma aceitável para ser enviado?
     *
     * Só a forma óbvia — vazio, curto, longo, com quebra de linha ou caractere de controle. O
     * veredito final é sempre do servidor.
     */
    fun isDisplayNameAcceptable(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it.isISOControl() }) return false
        val length = trimmed.codePointCount(0, trimmed.length)
        return length in DISPLAY_NAME_MIN_LENGTH..DISPLAY_NAME_MAX_LENGTH
    }

    /** Os códigos de erro do envelope do servidor que o app traduz. */
    object ErrorCodes {
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
        const val AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE"
        const val API_RATE_LIMITED = "API_RATE_LIMITED"
        const val INVALID_SOCIAL_REQUEST = "INVALID_SOCIAL_REQUEST"
        const val INVALID_DISPLAY_NAME = "INVALID_DISPLAY_NAME"
        const val SOCIAL_NOT_ENABLED = "SOCIAL_NOT_ENABLED"
        const val SOCIAL_ALREADY_ENABLED = "SOCIAL_ALREADY_ENABLED"
        const val SOCIAL_ALREADY_DISABLED = "SOCIAL_ALREADY_DISABLED"
        const val SOCIAL_UNAVAILABLE = "SOCIAL_UNAVAILABLE"
        const val RANKING_NOT_ENABLED = "RANKING_NOT_ENABLED"
        const val INVALID_ACTIVITY_TIMEZONE = "INVALID_ACTIVITY_TIMEZONE"
        const val ACTIVITY_NOT_AVAILABLE = "ACTIVITY_NOT_AVAILABLE"
    }
}
