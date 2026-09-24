package com.example.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import com.example.data.social.WorkoutCheckInContract
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A redução da foto **antes** do envio (T17.9 §46/§47).
 *
 * ## O que ela é, e o que ela explicitamente não é
 *
 * Ela é economia de banda. Uma foto de celular tem 4–12 MB e 12 megapixels; o servidor vai
 * reduzi-la para 1600 px de qualquer jeito, então mandar os 12 MB originais é gastar o pacote de
 * dados de quem está em rede móvel para transferir pixels que serão descartados.
 *
 * Ela **não** é a proteção de privacidade. Quem remove EXIF, GPS, modelo do aparelho e data
 * original é o servidor, que decodifica de verdade e re-encoda sem copiar metadata nenhum (§15/§16).
 * Este arquivo ajuda — `BitmapFactory` já descarta metadata ao decodificar, e o que sai daqui é um
 * JPEG novo sem EXIF —, mas confiar nisso seria confiar no cliente: um APK modificado enviaria a
 * foto original com tudo dentro, e o servidor precisa recusar isso sozinho (§46).
 *
 * ## O arquivo original nunca é copiado
 *
 * O `Uri` do Photo Picker é lido **em stream** e vira um `Bitmap` reduzido na memória. Não existe
 * `File` temporário com a foto original (§47): um arquivo assim carrega GPS dentro e sobrevive a um
 * processo morto no meio. O que existe é um `ByteArray` que vive enquanto a tela viver.
 *
 * ## A orientação vira geometria
 *
 * `BitmapFactory` ignora a tag de orientação do EXIF. Sem aplicá-la, uma foto tirada na vertical
 * chegaria deitada ao servidor — que também descarta a tag, e publicaria a foto deitada. Aqui a
 * rotação é aplicada aos **pixels**, e a tag deixa de ser necessária.
 */
class SocialPhotoOptimizer(
    private val context: Context,
    private val maxEdgePx: Int = WorkoutCheckInContract.Limits.MAX_UPLOAD_EDGE_PX,
    private val maxBytes: Int = WorkoutCheckInContract.Limits.MAX_UPLOAD_BYTES
) : CheckInPhotoSource {

    /** O resultado: bytes prontos para enviar, e as dimensões para o preview (§124). */
    data class Optimized(
        val bytes: ByteArray,
        val width: Int,
        val height: Int
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Optimized &&
                    width == other.width &&
                    height == other.height &&
                    bytes.contentEquals(other.bytes))

        override fun hashCode(): Int =
            (bytes.contentHashCode() * 31 + width) * 31 + height
    }

    /**
     * Lê, reduz e reencoda a imagem apontada por [uri].
     *
     * ## O defeito que a T19.H3 corrigiu aqui
     *
     * Até a T19.H2 a primeira passagem era
     * `openStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null`. Com
     * `inJustDecodeBounds = true`, `decodeStream` **sempre** devolve `null` — é o contrato dele: as
     * dimensões vão para `bounds.outWidth/outHeight`, e o retorno fica vazio. O `?:` lia esse
     * `null` como "não consegui abrir", e **toda** foto parava ali, antes de qualquer requisição.
     * Em 30 dias de produção nenhum `POST /v1/social/checkin-media` chegou ao Cloud Run. A
     * abertura do stream e o retorno do decoder agora são perguntas separadas.
     *
     * ## O teto é invariante, não intenção
     *
     * Um [CheckInPhotoPreparation.Ready] **nunca** carrega mais que [maxBytes]. Antes, o laço de
     * qualidade guardava o último candidato mesmo acima do teto, e o servidor recusaria depois de
     * a pessoa ter gastado a banda do envio. Agora, se nem a menor qualidade couber, o desfecho é
     * [CheckInPhotoPreparation.TooLarge] e nada sai do aparelho.
     */
    override suspend fun prepare(uri: Uri): CheckInPhotoPreparation = withContext(Dispatchers.IO) {
        try {
            decodeAndEncode(uri)
        } catch (error: Exception) {
            // Um provedor de conteúdo que falha no meio da leitura (arquivo de nuvem que não
            // baixou, permissão revogada) lança em vez de devolver `null`. É "não consegui ler",
            // e não um crash da tela de compartilhamento.
            CheckInPhotoPreparation.Unreadable
        } catch (error: OutOfMemoryError) {
            // A subamostragem torna isto improvável; se acontecer, a foto não é legível **neste
            // aparelho**, e a pessoa pode escolher outra — o treino não é afetado.
            CheckInPhotoPreparation.Unreadable
        }
    }

    private fun decodeAndEncode(uri: Uri): CheckInPhotoPreparation {
        // Primeira passagem: só o cabeçalho. `inJustDecodeBounds` lê as dimensões sem alocar os
        // pixels, e é o que permite escolher o fator de subamostragem antes de gastar memória —
        // decodificar 12 MP para depois reduzir é como um `OutOfMemoryError` acontece.
        //
        // O `true` no fim do bloco é o que distingue "abri o stream" de "o decoder devolveu
        // bitmap": no modo de cabeçalho o decoder devolve `null` por contrato, sempre.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
            true
        } ?: return CheckInPhotoPreparation.Unreadable
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return CheckInPhotoPreparation.Unreadable
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return CheckInPhotoPreparation.Unreadable

        val oriented = applyOrientation(uri, decoded)
        if (oriented !== decoded) decoded.recycle()
        val scaled = scaleWithinBounds(oriented)
        if (scaled !== oriented) oriented.recycle()

        return try {
            encodeWithinLimit(scaled)
                ?.let { CheckInPhotoPreparation.Ready(Optimized(it, scaled.width, scaled.height)) }
                ?: CheckInPhotoPreparation.TooLarge
        } finally {
            scaled.recycle()
        }
    }

    /**
     * Degraus de qualidade até caber em [maxBytes], ou `null` quando nenhum cabe.
     *
     * A imagem já está reduzida em dimensão, então o primeiro degrau resolve o caso normal; os
     * seguintes existem para a foto de textura densa que comprime mal. Só um candidato **dentro**
     * do teto é devolvido.
     */
    private fun encodeWithinLimit(bitmap: Bitmap): ByteArray? {
        for (quality in QUALITY_STEPS) {
            val stream = ByteArrayOutputStream()
            // O encoder recusar um bitmap já decodificado não é "grande demais": é "não consegui
            // produzir o JPEG", e o `catch` de [prepare] transforma isso em ilegível.
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) { "JPEG encoder" }
            val candidate = stream.toByteArray()
            if (candidate.isNotEmpty() && candidate.size <= maxBytes) return candidate
        }
        return null
    }

    private fun openStream(uri: Uri) = runCatching {
        context.contentResolver.openInputStream(uri)
    }.getOrNull()

    /**
     * O fator de subamostragem: a maior potência de 2 que ainda deixa a imagem acima do alvo.
     *
     * `BitmapFactory` só aceita potências de 2, e arredondar para baixo é deliberado: subamostrar
     * demais produziria uma imagem menor que o alvo, e ampliar depois inventaria pixels.
     */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var currentEdge = maxOf(width, height)
        while (currentEdge / 2 >= maxEdgePx) {
            currentEdge /= 2
            sample *= 2
        }
        return sample
    }

    /** Reduz para caber em [maxEdgePx]. Nunca amplia: uma foto pequena sai como entrou. */
    private fun scaleWithinBounds(source: Bitmap): Bitmap {
        val longestEdge = maxOf(source.width, source.height)
        if (longestEdge <= maxEdgePx) return source

        val ratio = maxEdgePx.toFloat() / longestEdge
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /**
     * Aplica a orientação declarada no EXIF aos pixels.
     *
     * O EXIF em si é lido e **descartado**: o que sai daqui é um bitmap já girado, e o JPEG que o
     * `compress` produz não carrega tag nenhuma. Ler a orientação não é preservar metadata — é o
     * que impede a foto de chegar deitada do outro lado.
     *
     * `android.media.ExifInterface` (a da plataforma), e não a de `androidx`: o construtor com
     * `InputStream` existe desde a API 24, que é o `minSdk` do projeto, e o único atributo lido
     * aqui é a orientação. Uma dependência nova para ler um inteiro seria custo sem ganho.
     */
    private fun applyOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            openStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            // Espelhar e girar: raros, mas existem em câmeras frontais que gravam "como no
            // espelho". Sem estes dois casos a selfie saía deitada.
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private companion object {
        /** As qualidades tentadas, em ordem, até o JPEG caber no teto de envio. */
        val QUALITY_STEPS = intArrayOf(88, 78, 68, 55)
    }
}
