package com.example.data.auth

import android.content.Context

/**
 * O Web Client ID (server client ID) exigido pelo Sign in with Google.
 *
 * Ele **não** vive no código. O plugin `com.google.gms.google-services` lê o `oauth_client` do
 * `app/google-services.json` (não versionado) e gera o recurso `default_web_client_id`. Duplicar
 * esse valor em uma constante criaria uma segunda autoridade de configuração, silenciosamente
 * divergente do arquivo real — o mesmo motivo pelo qual o Coach lê a configuração do Firebase em
 * vez de montar `FirebaseOptions` na mão (ARCHITECTURE §15.7).
 *
 * A leitura é por *nome* de recurso, e não por `R.string.default_web_client_id`, por uma razão
 * concreta: enquanto o provedor Google não estiver habilitado no console, o `google-services.json`
 * vem com `oauth_client` vazio e o recurso **não é gerado**. Uma referência direta faria o app
 * inteiro parar de compilar por causa de uma etapa de console pendente. Aqui, a ausência vira um
 * `null` — a entrada de conta se apresenta como indisponível e o resto do Spark segue igual.
 *
 * Ver `docs/FIREBASE_AUTH_SETUP.md`.
 */
internal object GoogleServerClientId {

    private const val RESOURCE_NAME = "default_web_client_id"

    fun resolve(context: Context): String? {
        val resourceId = context.resources.getIdentifier(
            RESOURCE_NAME,
            "string",
            context.packageName
        )
        if (resourceId == 0) return null

        return runCatching { context.getString(resourceId) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
