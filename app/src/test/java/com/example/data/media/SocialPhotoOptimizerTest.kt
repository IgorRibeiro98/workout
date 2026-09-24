package com.example.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.social.WorkoutCheckInContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O optimizer de foto do check-in, com o decodificador **nativo** de verdade (T19.H3 §14/§15).
 *
 * `GraphicsMode.NATIVE` é o que dá sentido a esta suíte: com ele, `BitmapFactory` e
 * `Bitmap.compress` são os decodificadores/encoders reais do Android (Skia), e não o dublê que
 * devolve um bitmap para qualquer coisa. Foi com ele que o defeito da T17.9 apareceu — no modo de
 * cabeçalho o decoder real devolve `null` por contrato, e o optimizer lia isso como "ilegível"
 * para **toda** foto. O teste `um JPEG de camera` falhava contra o código anterior.
 *
 * As imagens são geradas aqui, pelo próprio encoder nativo, nos formatos que o Photo Picker
 * entrega na prática: JPEG de câmera (grande, com EXIF de rotação), PNG de captura de tela e WebP
 * de galeria. HEIC não é gerável sem o codec do aparelho e fica para o smoke real.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SocialPhotoOptimizerTest {

    private lateinit var context: Context
    private var nextUri = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ------------------------------------------------------------------ formatos

    @Test
    fun `um JPEG de camera grande sai JPEG reduzido, dentro do teto e sem EXIF`() = runBlocking {
        val input = encode(solid(4000, 3000, Color.rgb(200, 80, 40)), Bitmap.CompressFormat.JPEG)

        val ready = prepareReady(register(input))

        assertEquals(1920, maxOf(ready.width, ready.height))
        assertEquals(4f / 3f, ready.width.toFloat() / ready.height, 0.01f)
        assertTrue(ready.bytes.size <= WorkoutCheckInContract.Limits.MAX_UPLOAD_BYTES)
        assertIsPlainJpeg(ready)
    }

    @Test
    fun `um PNG vira JPEG valido`() = runBlocking {
        val input = encode(solid(1080, 2340, Color.WHITE), Bitmap.CompressFormat.PNG)

        val ready = prepareReady(register(input))

        assertEquals(1920, ready.height)
        assertIsPlainJpeg(ready)
    }

    @Test
    fun `um WebP vira JPEG valido`() = runBlocking {
        @Suppress("DEPRECATION")
        val input = encode(solid(1600, 1200, Color.BLUE), Bitmap.CompressFormat.WEBP)

        val ready = prepareReady(register(input))

        assertEquals(1600 to 1200, ready.width to ready.height)
        assertIsPlainJpeg(ready)
    }

    @Test
    fun `uma foto pequena nao e ampliada`() = runBlocking {
        val input = encode(solid(300, 200, Color.GREEN), Bitmap.CompressFormat.JPEG)

        val ready = prepareReady(register(input))

        assertEquals(300 to 200, ready.width to ready.height)
    }

    // ------------------------------------------------------------------ orientação

    @Test
    fun `a rotacao do EXIF vira geometria, e a tag nao sai`() = runBlocking {
        // 400×200 "deitada", com a tag dizendo que a câmera estava em pé (ROTATE_90).
        val input = withOrientation(
            encode(solid(400, 200, Color.MAGENTA), Bitmap.CompressFormat.JPEG),
            ExifInterface.ORIENTATION_ROTATE_90
        )

        val ready = prepareReady(register(input))

        assertEquals(200 to 400, ready.width to ready.height)
        assertIsPlainJpeg(ready)
    }

    // ------------------------------------------------------------------ falhas locais

    @Test
    fun `bytes que nao sao imagem sao ilegiveis`() = runBlocking {
        val result = SocialPhotoOptimizer(context).prepare(register("não é uma foto".toByteArray()))

        assertEquals(CheckInPhotoPreparation.Unreadable, result)
    }

    @Test
    fun `um provedor que falha ao abrir e ilegivel, sem crash`() = runBlocking {
        val uri = Uri.parse("content://media/picker/0/test/${nextUri++}")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            throw SecurityException("permissão revogada")
        }

        assertEquals(CheckInPhotoPreparation.Unreadable, SocialPhotoOptimizer(context).prepare(uri))
    }

    @Test
    fun `nunca devolve bytes acima do teto — quando nao cabe, falha local (H3 14)`() = runBlocking {
        // Ruído comprime mal de propósito: nem a qualidade 55 cabe em 20 KB.
        val input = encode(noise(1600, 1200), Bitmap.CompressFormat.PNG)
        val optimizer = SocialPhotoOptimizer(context, maxBytes = 20_000)

        assertEquals(CheckInPhotoPreparation.TooLarge, optimizer.prepare(register(input)))
    }

    @Test
    fun `a invariante do teto vale em todos os degraus de qualidade`() = runBlocking {
        val input = encode(noise(1600, 1200), Bitmap.CompressFormat.PNG)

        // Tetos entre o que cabe já em 88 e o que só cabe em 55: o resultado, quando existe,
        // nunca passa do teto que o optimizer recebeu.
        for (maxBytes in listOf(3_000_000, 1_500_000, 900_000, 600_000, 400_000, 250_000)) {
            val result = SocialPhotoOptimizer(context, maxBytes = maxBytes).prepare(register(input))
            if (result is CheckInPhotoPreparation.Ready) {
                assertTrue(
                    "teto $maxBytes, saiu ${result.photo.bytes.size}",
                    result.photo.bytes.size <= maxBytes
                )
            } else {
                assertEquals(CheckInPhotoPreparation.TooLarge, result)
            }
        }
    }

    // ------------------------------------------------------------------ contrato com o backend

    @Test
    fun `a fixture compartilhada com o backend e uma saida valida deste optimizer`() {
        // `contracts/social/v1/checkin-photo-android.jpg` foi produzida por este optimizer a partir
        // de uma foto 4032×3024 com EXIF ROTATE_90, e é o corpo que `social-media.spec.ts` envia
        // como `image/jpeg` pela montagem real do Express (T19.H3 §16–§18). Se o formato de saída
        // mudar, as duas suítes precisam ser olhadas juntas.
        val relative = "contracts/social/v1/checkin-photo-android.jpg"
        val file = File(relative).takeIf { it.isFile }
            ?: File("../$relative").takeIf { it.isFile }
            ?: error("fixture compartilhada não encontrada: $relative")
        val bytes = file.readBytes()

        assertTrue(bytes.size <= WorkoutCheckInContract.Limits.MAX_UPLOAD_BYTES)
        assertIsPlainJpeg(SocialPhotoOptimizer.Optimized(bytes, 1440, 1920))
    }

    // ------------------------------------------------------------------ apoio

    private suspend fun prepareReady(uri: Uri): SocialPhotoOptimizer.Optimized {
        val result = SocialPhotoOptimizer(context).prepare(uri)
        assertTrue("esperava Ready, veio $result", result is CheckInPhotoPreparation.Ready)
        return (result as CheckInPhotoPreparation.Ready).photo
    }

    private fun register(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://media/picker/0/test/${nextUri++}")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            ByteArrayInputStream(bytes)
        }
        return uri
    }

    /** JPEG de verdade (SOI `FF D8`), decodificável, e sem segmento APP1/Exif. */
    private fun assertIsPlainJpeg(photo: SocialPhotoOptimizer.Optimized) {
        val bytes = photo.bytes
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(photo.width to photo.height, decoded.width to decoded.height)
        assertTrue("o JPEG de saída carrega EXIF", !String(bytes, Charsets.ISO_8859_1).contains("Exif\u0000"))
    }

    private fun solid(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun noise(width: Int, height: Int): Bitmap {
        val random = Random(42)
        val pixels = IntArray(width * height) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(format, 95, it) }.toByteArray()

    /** Grava a tag de orientação num JPEG, como a câmera faz. */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val file = File.createTempFile("exif", ".jpg").apply { writeBytes(jpeg) }
        try {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }
}
