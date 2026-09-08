package com.example.data.social

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A matriz de um QR Code, gerada **no aparelho** (T17.1 §55).
 *
 * Nada é pedido ao servidor: o `friendCode` já está no perfil que o app leu, e transformar texto em
 * quadradinhos é aritmética. Uma rota que devolvesse uma imagem custaria uma requisição, exigiria
 * rede para mostrar o próprio código e colocaria o código numa URL — os três desnecessários.
 *
 * ## Matriz, e não `Bitmap`
 *
 * O que sai daqui é um valor puro: uma grade de booleanos. Quem pinta é o Compose, no tamanho que
 * a tela tiver, sem reescalar imagem e sem borrar módulo — e um `Bitmap` de tamanho fixo
 * apareceria serrilhado em metade dos aparelhos.
 *
 * A outra consequência é a que o CI usa: **isto roda em teste de JVM**. `zxing:core` é Java puro,
 * então o teste prova `código → QR → leitura → mesmo código` sem câmera, sem emulador e sem
 * device (§145).
 */
data class QrMatrix(
    val size: Int,
    private val modules: BooleanArray
) {
    /** O módulo (linha, coluna) é escuro? */
    operator fun get(row: Int, column: Int): Boolean = modules[row * size + column]

    override fun equals(other: Any?): Boolean =
        other is QrMatrix && size == other.size && modules.contentEquals(other.modules)

    override fun hashCode(): Int = 31 * size + modules.contentHashCode()
}

object QrCode {

    /**
     * Correção de erro média (~15%).
     *
     * `L` deixaria o desenho menor e mais frágil a um vinco no papel ou a um reflexo na tela; `H`
     * engordaria a matriz sem ganho perceptível para 30 caracteres. `M` é o padrão do formato e o
     * que sobrevive a uma foto de tela tirada de lado.
     */
    private const val ERROR_CORRECTION = "M"

    /**
     * Sem margem embutida.
     *
     * A "zona silenciosa" do QR é obrigatória para a leitura, e ela é desenhada pelo Compose como
     * padding — assim ela é branca de verdade na cor da superfície, em vez de virar uma borda
     * escura quando a tela for escura.
     */
    private const val QUIET_ZONE_MODULES = 0

    /**
     * A matriz do payload de convite de [friendCode].
     *
     * O conteúdo é [SparkFriendQr.payloadFor] — `spark://friend/v1/SPK-XXXXXXXX` e nada mais.
     * Nenhum uid, nenhum e-mail, nenhum token, nenhum `socialId`, nenhum endereço de servidor.
     */
    fun forFriendCode(friendCode: String): QrMatrix? {
        val payload = runCatching { SparkFriendQr.payloadFor(friendCode) }.getOrNull() ?: return null
        return encode(payload)
    }

    /** A matriz de um texto qualquer, ou `null` se ele não couber num QR. */
    fun encode(payload: String): QrMatrix? {
        val matrix = runCatching {
            QRCodeWriter().encode(
                payload,
                BarcodeFormat.QR_CODE,
                // Zero pede ao writer a menor matriz que couber: o tamanho de tela é decidido na
                // hora de pintar, e um tamanho fixo aqui obrigaria a reescalar.
                0,
                0,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.valueOf(ERROR_CORRECTION),
                    EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                    EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name()
                )
            )
        }.getOrNull() ?: return null

        val size = matrix.width
        val modules = BooleanArray(size * size)
        for (row in 0 until size) {
            for (column in 0 until size) {
                modules[row * size + column] = matrix.get(column, row)
            }
        }
        return QrMatrix(size = size, modules = modules)
    }
}
