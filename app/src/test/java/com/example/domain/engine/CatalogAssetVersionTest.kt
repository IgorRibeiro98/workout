package com.example.domain.engine

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O atalho que evita abrir 2,4 MB de JSON a cada abertura do app (auditoria 2026-09-12).
 *
 * O teste lê os **assets reais**, e não um arquivo de teste, de propósito: o valor do atalho
 * depende de o `contentVersion` estar perto do começo do arquivo. Se alguém reordenar o manifesto
 * e empurrar o campo para depois dos 8 KB iniciais, o atalho para de funcionar em silêncio — e é
 * este teste que precisa gritar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class CatalogAssetVersionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `le a versao do catalogo base sem abrir o arquivo inteiro`() {
        assertEquals(10, CatalogAssetVersion.peek(context, "catalog/catalogo_exercicios_base_ptbr.v1.json"))
    }

    @Test
    fun `le a versao do manifesto premium`() {
        assertEquals(5, CatalogAssetVersion.peek(context, "catalog/exercise-content-manifest.v2.json"))
    }

    @Test
    fun `asset inexistente responde nao sei, e nao uma versao inventada`() {
        // `null` significa "não sei", e quem não sabe segue pelo caminho completo. Um `0` aqui
        // faria o importador pular o trabalho achando que já está atualizado.
        assertNull(CatalogAssetVersion.peek(context, "catalog/nao-existe.json"))
    }
}
