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
        for (prefName in DEBUG_STORE_PREFS) {
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

    /** Os lugares em que o SDK de depuração procura o segredo, conforme a versão. */
    private val DEBUG_STORE_PREFS = listOf(
        "com.google.firebase.appcheck.debug.DebugAppCheckProvider",
        "com.google.firebase.appcheck.debug.store"
    )
}
