package com.example.data.firebase

import android.content.Context
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * App Check do build de **depuração**.
 *
 * Ele atesta o **aplicativo** perante o Firebase. Até a T14.5 quem o instalava era o gateway do
 * Coach, porque o Coach era o consumidor de Firebase que precisava dele; com a T16.2 o Coach passou
 * a falar com o Spark Backend, e o produto Firebase que resta em uso é o Authentication — que é
 * quem instala o App Check agora (`FirebaseAuthGateway`). O provedor por variante não mudou.
 *
 * Existe uma vez por variante de build, e não um `if (BuildConfig.DEBUG)`: assim o
 * `DebugAppCheckProviderFactory` só é compilado no source set `debug` e não tem como ser
 * escolhido — nem por engano, nem por refatoração — em um APK de release.
 *
 * O segredo de depuração **não vive no código**. Quando nenhum token é informado, o provedor de
 * depuração gera um e o registra no Logcat (`DebugAppCheckProvider`); basta cadastrá-lo no
 * console do Firebase. Quando o console gerar outro, o desenvolvedor cola aquele token na área de
 * Conta do Perfil e ele fica apenas neste aparelho, em `SharedPreferences`.
 */
internal object SparkAppCheck {

    /** Nome legível do provedor desta variante. Usado em log técnico e em teste. */
    const val PROVIDER_NAME: String = "debug"

    /** Se esta variante permite configurar o token de depuração pela UI. */
    const val SUPPORTS_DEBUG_TOKEN: Boolean = true

    fun providerFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()

    /**
     * Publica o token escolhido pelo desenvolvedor no lugar em que o provedor de depuração lê.
     *
     * Sem token configurado nada é escrito: o provedor gera o dele e o registra no Logcat.
     */
    fun publishDebugToken(context: Context) {
        val token = customDebugToken(context) ?: return
        for (prefName in debugStorePrefNames()) {
            context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                .edit()
                .putString(DEBUG_SECRET_KEY, token)
                .apply()
        }
    }

    /** O token que este aparelho está usando, ou `null` quando o provedor gera o seu. */
    fun customDebugToken(context: Context): String? =
        context.getSharedPreferences(CUSTOM_TOKEN_PREF, Context.MODE_PRIVATE)
            .getString(CUSTOM_TOKEN_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Guarda o token que o console do Firebase gerou. Só neste aparelho, só em debug. */
    fun setCustomDebugToken(context: Context, token: String) {
        val clean = token.trim()
        context.getSharedPreferences(CUSTOM_TOKEN_PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(CUSTOM_TOKEN_KEY, clean.ifEmpty { null })
            .apply()
        publishDebugToken(context)
    }

    private const val CUSTOM_TOKEN_PREF = "spark_firebase_app_check_custom"
    private const val CUSTOM_TOKEN_KEY = "custom_debug_token"
    private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

    /**
     * Os lugares em que o SDK de depuração procura o segredo.
     *
     * Os dois primeiros nomes são os de versões antigas. O que o `firebase-appcheck-debug` atual
     * realmente lê é `"com.google.firebase.appcheck.debug.store." + persistenceKey` — e era
     * exatamente esse que faltava (auditoria 2026-09-12): o token colado na área de Conta nunca
     * era lido, o provedor gerava outro a cada instalação, e quem estava desenvolvendo cadastrava
     * token novo no console sem entender por quê.
     *
     * O `persistenceKey` é derivado do nome da `FirebaseApp` e do `applicationId`, pela fórmula do
     * próprio SDK (`FirebaseApp.getPersistenceKey`): Base64 url-safe sem padding de cada um, unidos
     * por `+`. Ele é reproduzido aqui em vez de lido do getter porque a formação não depende de
     * nada além de dados públicos, e porque uma falha aqui não pode derrubar o app de depuração.
     *
     * Continuamos escrevendo nos nomes antigos: gravar um `SharedPreferences` a mais é inofensivo,
     * e cobre um SDK mais velho sem nenhuma detecção de versão.
     */
    private fun debugStorePrefNames(): List<String> {
        val legacy = listOf(
            "com.google.firebase.appcheck.debug.DebugAppCheckProvider",
            "com.google.firebase.appcheck.debug.store"
        )
        val persistenceKey = runCatching {
            val app = com.google.firebase.FirebaseApp.getInstance()
            val name = urlSafeBase64(app.name)
            val applicationId = urlSafeBase64(app.options.applicationId)
            "$name+$applicationId"
        }.getOrNull()
        return if (persistenceKey.isNullOrEmpty()) {
            legacy
        } else {
            legacy + "com.google.firebase.appcheck.debug.store.$persistenceKey"
        }
    }

    private fun urlSafeBase64(value: String): String = android.util.Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
    )
}
