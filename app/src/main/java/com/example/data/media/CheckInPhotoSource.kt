package com.example.data.media

import android.net.Uri

/**
 * A fronteira entre "o usuário escolheu uma foto" e "temos bytes para enviar" (T17.9 §46).
 *
 * Existe como interface por uma razão prática: a implementação real (`SocialPhotoOptimizer`)
 * precisa de `ContentResolver`, `BitmapFactory` e `Bitmap.compress`, e um teste de ViewModel que
 * quisesse exercitar "a foto falhou ao ser lida" teria de montar um `ContentProvider` inteiro para
 * chegar até a decisão que ele quer verificar. Aqui ele entrega bytes e pronto.
 *
 * A fronteira também deixa explícito o que atravessa: **bytes e dimensões**. O `Uri` original não
 * segue adiante, e nenhum arquivo temporário com a foto original é criado (§47).
 */
interface CheckInPhotoSource {

    /**
     * Lê a imagem apontada por [uri], reduz e reencoda.
     *
     * Nunca devolve bytes acima do teto de envio (T19.H3 §14): quando não dá para reduzir, o
     * resultado é [CheckInPhotoPreparation.TooLarge] — uma falha **local**, e nenhuma requisição
     * sai. A tela transforma cada falha em uma frase própria, e nunca em uma publicação silenciosa
     * sem a foto (§43).
     */
    suspend fun prepare(uri: Uri): CheckInPhotoPreparation
}

/**
 * O desfecho da preparação local de uma foto (T19.H3 §13/§14).
 *
 * Três casos, e não `Optimized?`: até a T19.H2 um `null` significava ao mesmo tempo "não consegui
 * ler" e — por um defeito — "li e descartei", e a tela não tinha como distinguir. Agora a falha
 * diz **qual** foi, e só [Ready] carrega bytes.
 */
sealed interface CheckInPhotoPreparation {

    /** Bytes prontos. Invariante: `photo.bytes.size <= MAX_UPLOAD_BYTES`, garantido na origem. */
    data class Ready(val photo: SocialPhotoOptimizer.Optimized) : CheckInPhotoPreparation

    /** O `Uri` não pôde ser aberto, ou o conteúdo não é uma imagem que o aparelho decodifique. */
    data object Unreadable : CheckInPhotoPreparation

    /** Mesmo na menor qualidade tentada, o JPEG ficou acima do teto. Nada é enviado. */
    data object TooLarge : CheckInPhotoPreparation
}
