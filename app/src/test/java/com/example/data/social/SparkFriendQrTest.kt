package com.example.data.social

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O QR Code de convite: geração, leitura e o que ele nunca pode carregar (T17.1 §52–§62, §145).
 *
 * ## O round-trip roda sem câmera
 *
 * O teste central é `friendCode → QR → leitura → mesmo friendCode`. Ele é possível em JVM pura
 * porque `zxing:core` é Java puro: o encode produz a matriz, o teste a transforma em pixels e o
 * `QRCodeReader` a lê de volta. Nenhuma câmera, nenhum emulador, nenhum device — e por isso ele é
 * um portão de CI de verdade, e não uma verificação manual que alguém lembra de fazer.
 *
 * A câmera real continua **NOT VERIFIED**: o que este arquivo prova é que o conteúdo do QR está
 * certo e que um leitor conforme o padrão o lê. Que a lente do celular do amigo funciona é outra
 * afirmação, e ela exige dois aparelhos.
 */
class SparkFriendQrTest {

    private val code = "SPK-7K2P9D8Q"

    // ------------------------------------------------------------------ payload

    @Test
    fun `o payload carrega apenas o codigo, com esquema e versao`() {
        assertEquals("spark://friend/v1/SPK-7K2P9D8Q", SparkFriendQr.payloadFor(code))
    }

    @Test
    fun `o payload nao carrega uid, email, token nem endereco de servidor`() {
        val payload = SparkFriendQr.payloadFor(code)

        // O QR circula por WhatsApp, é impresso e é fotografado por quem passa: tudo o que ele
        // carrega é público por construção. Esta é a lista do que **nunca** pode entrar.
        for (forbidden in listOf(
            "uid", "firebaseUid", "ownerUid", "@", "token", "Bearer", "http", "socialId",
            "deviceId", "syncId"
        )) {
            assertTrue(
                "o payload do QR não pode conter \"$forbidden\": $payload",
                !payload.contains(forbidden, ignoreCase = true)
            )
        }
        // E o que **deve** estar lá está — senão o teste passaria com um payload vazio.
        assertTrue(payload.contains(code))
    }

    @Test
    fun `um codigo fora da forma canonica nao vira payload`() {
        assertNull(QrCode.forFriendCode("nada disso"))
    }

    // ------------------------------------------------------------------ round-trip

    @Test
    fun `codigo vira QR e volta como o mesmo codigo normalizado`() {
        val matrix = QrCode.forFriendCode(code)
        assertNotNull("o QR não foi gerado", matrix)

        val decoded = decode(matrix!!)

        assertEquals(SparkFriendQr.payloadFor(code), decoded)
        assertEquals(QrScan.FriendCode(code), SparkFriendQr.parse(decoded))
    }

    @Test
    fun `o round-trip preserva o codigo mesmo quando a entrada veio despadronizada`() {
        // O QR é sempre gerado a partir da forma canônica, venha de onde vier a entrada.
        val matrix = QrCode.forFriendCode(" spk 7k2p 9d8q ")
        assertNotNull(matrix)

        assertEquals(QrScan.FriendCode(code), SparkFriendQr.parse(decode(matrix!!)))
    }

    // ------------------------------------------------------------------ parser

    @Test
    fun `QR que nao e do Spark e recusado, sem tentativa de adivinhacao`() {
        val payloads = listOf(
            "https://example.com/qualquer",
            "00020126580014BR.GOV.BCB.PIX0136chave-pix-aleatoria",
            "spark://workout/v1/abc",
            "spark://friend/v2/SPK-7K2P9D8Q",
            "friend/v1/SPK-7K2P9D8Q",
            "spark://friend/v1/SPK-7K2P9D8",
            "spark://friend/v1/GYM-7K2P9D8Q",
            "spark://friend/v1/SPK-7K2P9D8Q/extra",
            "spark://friend/v1/SPK-7K2P9D8Q?ref=x",
            "spark://friend/v1/SPK-7K2P9D8Q#frag",
            "",
            "   "
        )

        for (payload in payloads) {
            assertEquals("deveria recusar: $payload", QrScan.Invalid, SparkFriendQr.parse(payload))
        }
        assertEquals(QrScan.Invalid, SparkFriendQr.parse(null))
    }

    @Test
    fun `payload gigante e recusado antes de qualquer processamento`() {
        val huge = SparkFriendQr.PREFIX + "A".repeat(SparkFriendQr.MAX_PAYLOAD_LENGTH)

        assertEquals(QrScan.Invalid, SparkFriendQr.parse(huge))
    }

    @Test
    fun `o parser aceita a variacao de caixa do esquema, e nada alem disso`() {
        // Alguns leitores devolvem o esquema em maiúsculas. O **código** continua passando pela
        // normalização canônica, e o resultado é sempre a forma canônica.
        assertEquals(
            QrScan.FriendCode(code),
            SparkFriendQr.parse("SPARK://FRIEND/V1/spk-7k2p9d8q")
        )
    }

    /**
     * Lê a matriz de volta com o `QRCodeReader` — a prova de que o QR é legível por um leitor
     * conforme o padrão, e não só de que o encoder produziu alguma coisa.
     *
     * Os módulos são ampliados e cercados por uma zona silenciosa antes da leitura, exatamente
     * como a tela faz ao pintar: um QR sem margem não é localizável, e ler a matriz "crua" faria o
     * teste passar com um desenho que nenhuma câmera leria.
     */
    private fun decode(matrix: QrMatrix): String {
        val scale = 4
        val quietZone = 4 * scale
        val side = matrix.size * scale + quietZone * 2

        val pixels = IntArray(side * side) { WHITE }
        for (row in 0 until matrix.size) {
            for (column in 0 until matrix.size) {
                if (!matrix[row, column]) continue
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        val x = quietZone + column * scale + dx
                        val y = quietZone + row * scale + dy
                        pixels[y * side + x] = BLACK
                    }
                }
            }
        }

        val source = RGBLuminanceSource(side, side, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
