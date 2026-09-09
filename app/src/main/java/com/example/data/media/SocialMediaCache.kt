package com.example.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.domain.social.WorkoutCheckInGateway
import com.example.domain.social.WorkoutCheckInOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * O cache de fotos do Feed (T17.9 §56/§57/§145).
 *
 * ## Memória, e só memória — a decisão que §56 pede
 *
 * Não existe cache em disco. Nem Coil com `diskCache`, nem `File` no `cacheDir`, nem
 * `HttpResponseCache`: a foto de um amigo é conteúdo autenticado de **outra pessoa**, e um arquivo
 * no disco sobrevive ao logout, à troca de conta e à desinstalação do app pela metade. §57 diz o
 * cenário exato que isso produziria — a foto de A reaparecendo para B —, e §195 o lista como
 * bloqueante.
 *
 * O custo dessa escolha é real e aceito: trocar de tela e voltar rebaixa as imagens. Em troca, não
 * existe caminho em que uma foto de conta anterior possa ser desenhada.
 *
 * ## O escopo é a conta, e ele é trocado **antes** da primeira requisição da conta nova
 *
 * [switchAccount] limpa tudo e passa a rejeitar respostas em voo da conta anterior. É a mesma
 * disciplina do `SocialFeedViewModel` (T17.8 §110): limpar vem primeiro, sempre.
 *
 * ## Uma requisição por foto, mesmo com dez cards pedindo
 *
 * O Feed pode ter várias linhas pedindo a mesma imagem ao entrar na tela (recomposição, scroll de
 * volta). Um `Mutex` por `mediaId` faz a segunda esperar a primeira em vez de abrir outra
 * requisição — o servidor tem teto por conta, e dez chamadas para o mesmo arquivo seriam
 * desperdício que ele acabaria recusando.
 */
class SocialMediaCache(
    private val gateway: WorkoutCheckInGateway,
    maxBytes: Int = DEFAULT_MAX_BYTES
) {

    private val bitmaps = object : LruCache<String, ImageBitmap>(maxBytes) {
        // O tamanho em **bytes**, e não em número de itens: uma foto de 1600×1200 ocupa 7,3 MB
        // descomprimida, e um cache de "20 imagens" seria 150 MB em um aparelho com 2 GB.
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * BYTES_PER_PIXEL
    }

    private val inFlight = mutableMapOf<String, Mutex>()
    private val guard = Mutex()

    /** A conta dona do que está guardado. `null` = ninguém conectado, e o cache está vazio. */
    @Volatile
    private var ownerUid: String? = null

    /**
     * Troca a conta dona do cache (§57/§145).
     *
     * Chamado **antes** de a primeira requisição da conta nova sair. Chamar depois deixaria uma
     * janela em que a foto da conta anterior ainda está desenhável — que é exatamente o defeito
     * que §57 descreve.
     */
    fun switchAccount(uid: String?) {
        if (uid == ownerUid) return
        ownerUid = uid
        bitmaps.evictAll()
    }

    /** Logout: nada de conta nenhuma sobrevive na memória (§145). */
    fun clear() {
        ownerUid = null
        bitmaps.evictAll()
    }

    /** O que já está em memória, sem tocar na rede. Usado para desenhar sem piscar. */
    fun peek(mediaId: String): ImageBitmap? = bitmaps.get(mediaId)

    /**
     * A foto, do cache ou do servidor.
     *
     * `null` quando não deu — sem rede, sem autorização (o servidor devolve `404` para quem não
     * pode ver), ou bytes que não decodificam. A tela desenha o espaço reservado e segue: um card
     * sem imagem é melhor do que um Feed que não carrega.
     *
     * [expectedUid] é a conta que **pediu**. Se ela não for mais a dona quando a resposta chegar, o
     * resultado é descartado sem ir para o cache: uma foto pedida por A não pode virar pixel na
     * tela de B (§57).
     */
    suspend fun load(mediaId: String, expectedUid: String?): ImageBitmap? {
        if (expectedUid == null || expectedUid != ownerUid) return null
        bitmaps.get(mediaId)?.let { return it }

        val lock = guard.withLock { inFlight.getOrPut(mediaId) { Mutex() } }

        return lock.withLock {
            // Outra corrotina pode ter carregado enquanto esta esperava o `Mutex`.
            bitmaps.get(mediaId)?.let { return@withLock it }
            if (expectedUid != ownerUid) return@withLock null

            val outcome = gateway.mediaBytes(mediaId)
            // A conta pode ter trocado durante o voo. Guardar isto seria colocar a foto de uma
            // conta no cache de outra.
            if (expectedUid != ownerUid) return@withLock null

            val bytes = (outcome as? WorkoutCheckInOutcome.Success)?.data ?: return@withLock null
            val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                .getOrNull() ?: return@withLock null

            val image = decoded.asImageBitmap()
            bitmaps.put(mediaId, image)
            image
        }.also {
            guard.withLock { inFlight.remove(mediaId) }
        }
    }

    companion object {
        /**
         * O teto do cache, em bytes de bitmap descomprimido.
         *
         * 24 MB cobrem uma tela de Feed inteira com folga (uma foto de 1600×1200 ocupa 7,3 MB) e
         * ficam bem abaixo do orçamento de memória de qualquer aparelho que rode o Spark. O `LruCache`
         * despeja o mais antigo quando estoura — e a foto despejada é rebaixada, não perdida.
         */
        const val DEFAULT_MAX_BYTES = 24 * 1024 * 1024

        /** `ARGB_8888`, que é o que `BitmapFactory` produz por padrão. */
        private const val BYTES_PER_PIXEL = 4
    }
}

/**
 * O tamanho que um `Bitmap` ocupa, para quem precisar medir fora daqui.
 *
 * Existe como função de topo porque `ImageBitmap` não expõe `allocationByteCount` — e refazer a
 * conta em dois lugares é como as duas versões passam a discordar.
 */
internal fun Bitmap.approximateBytes(): Int = width * height * 4
