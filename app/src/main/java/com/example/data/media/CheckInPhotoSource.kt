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
     * `null` quando o `Uri` não pode ser lido ou não descreve uma imagem — a tela transforma isso
     * em "não conseguimos ler essa foto", e nunca em uma publicação silenciosa sem ela (§43).
     */
    suspend fun optimize(uri: Uri): SocialPhotoOptimizer.Optimized?
}
