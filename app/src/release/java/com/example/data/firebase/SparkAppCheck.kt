package com.example.data.firebase

import android.content.Context
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * App Check do build de **release**.
 *
 * Ele atesta o **aplicativo** perante o Firebase, e desde a T16.2 quem o instala é o
 * `FirebaseAuthGateway` — o Coach não fala mais com o Firebase. A escolha por variante não mudou.
 *
 * Play Integrity é o único provedor desta variante. O provedor de depuração e o token de
 * depuração não existem aqui: não são compilados neste source set, então nenhuma condição de
 * runtime, flag ou refatoração consegue trazê-los para um APK publicado.
 */
internal object SparkAppCheck {

    /** Nome legível do provedor desta variante. Usado em log técnico e em teste. */
    const val PROVIDER_NAME: String = "playIntegrity"

    /** Release não configura token de depuração. */
    const val SUPPORTS_DEBUG_TOKEN: Boolean = false

    fun providerFactory(): AppCheckProviderFactory =
        PlayIntegrityAppCheckProviderFactory.getInstance()

    /** Sem provedor de depuração, não há token a publicar. */
    fun publishDebugToken(context: Context) = Unit

    /** Release nunca usa token de depuração. */
    fun customDebugToken(context: Context): String? = null

    /** Release nunca aceita token de depuração. */
    fun setCustomDebugToken(context: Context, token: String) = Unit
}
