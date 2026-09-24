package com.example.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.social.WorkoutCheckInContract
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O optimizer de foto num **Android real** (emulador ou aparelho) — T19.H3 §15.
 *
 * O `SocialPhotoOptimizerTest` (Robolectric, decodificador nativo) é a prova de regressão que roda
 * no CI. Este roda o mesmo caminho no runtime de verdade: `ContentResolver` abrindo um arquivo,
 * `BitmapFactory`/`ExifInterface` do sistema e `Bitmap.compress` do aparelho. É o que responde "a
 * foto que eu escolher na galeria passa?" sem depender de Robolectric ter replicado a plataforma.
 *
 * Rodar com `./gradlew :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.example.data.media.SocialPhotoOptimizerDeviceTest`.
 */
@RunWith(AndroidJUnit4::class)
class SocialPhotoOptimizerDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun jpegDeCameraComExifRotacionadoSaiEmPeSemExifEDentroDoTeto() = runBlocking {
        val uri = file("camera.jpg", withOrientation(encode(gradient(4032, 3024), Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_ROTATE_90))

        val photo = ready(uri)

        assertEquals(1440 to 1920, photo.width to photo.height)
        assertPlainJpegWithinLimit(photo)
    }

    @Test
    fun pngDeCapturaDeTelaViraJpeg() = runBlocking {
        val photo = ready(file("screenshot.png", encode(gradient(1080, 2400), Bitmap.CompressFormat.PNG)))
        assertEquals(1920, photo.height)
        assertPlainJpegWithinLimit(photo)
    }

    @Test
    fun webpDeGaleriaViraJpeg() = runBlocking {
        @Suppress("DEPRECATION")
        val photo = ready(file("gallery.webp", encode(gradient(1600, 1200), Bitmap.CompressFormat.WEBP)))
        assertEquals(1600 to 1200, photo.width to photo.height)
        assertPlainJpegWithinLimit(photo)
    }

    @Test
    fun arquivoQueNaoEImagemEIlegivelENaoCrasha() = runBlocking {
        val uri = file("nao-e-foto.jpg", "texto qualquer".toByteArray())
        assertEquals(CheckInPhotoPreparation.Unreadable, SocialPhotoOptimizer(context).prepare(uri))
    }

    @Test
    fun fotoQueNaoCabeNoTetoFalhaNoAparelho() = runBlocking {
        val uri = file("ruido.png", encode(noise(1600, 1200), Bitmap.CompressFormat.PNG))
        val result = SocialPhotoOptimizer(context, maxBytes = 20_000).prepare(uri)
        assertEquals(CheckInPhotoPreparation.TooLarge, result)
    }

    // ------------------------------------------------------------------ apoio

    private suspend fun ready(uri: Uri): SocialPhotoOptimizer.Optimized {
        val result = SocialPhotoOptimizer(context).prepare(uri)
        assertTrue("esperava Ready, veio $result", result is CheckInPhotoPreparation.Ready)
        return (result as CheckInPhotoPreparation.Ready).photo
    }

    private fun assertPlainJpegWithinLimit(photo: SocialPhotoOptimizer.Optimized) {
        val bytes = photo.bytes
        assertTrue(bytes.size <= WorkoutCheckInContract.Limits.MAX_UPLOAD_BYTES)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(photo.width to photo.height, decoded.width to decoded.height)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Exif\u0000"))
    }

    private fun file(name: String, bytes: ByteArray): Uri {
        val target = File(context.cacheDir, "h3-$name").apply { writeBytes(bytes) }
        return Uri.fromFile(target)
    }

    private fun gradient(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.BLUE, Color.YELLOW, Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    private fun noise(width: Int, height: Int): Bitmap {
        val random = java.util.Random(42)
        val pixels = IntArray(width * height) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray =
        java.io.ByteArrayOutputStream().also { bitmap.compress(format, 95, it) }.toByteArray()

    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val file = File(context.cacheDir, "exif-${System.nanoTime()}.jpg").apply { writeBytes(jpeg) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }
}
