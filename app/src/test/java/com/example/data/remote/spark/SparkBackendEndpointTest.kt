package com.example.data.remote.spark

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A política de endereço do Spark Backend em produção (T16.8 §5/§6/§7).
 *
 * O que estes testes protegem quebra em silêncio e só aparece depois de o APK estar publicado:
 * um release apontando para `http://`, para a máquina de quem desenvolve ou para uma rede
 * privada. Nenhum deles falharia em compilação, e todos os três valeriam para quem instalasse.
 */
class SparkBackendEndpointTest {

    // ------------------------------------------------------------------ release

    @Test
    fun `release aceita HTTPS em host publico`() {
        assertEquals(
            "https://api.exemplo.com",
            SparkBackendEndpoint.resolve("https://api.exemplo.com", isDebugBuild = false)
        )
    }

    @Test
    fun `release recusa texto claro`() {
        // A proibição é da T16.2 e continua valendo: comunicação em texto claro não existe em
        // release. Aqui ela deixa de depender de quem passou a propriedade no build.
        assertNull(SparkBackendEndpoint.resolve("http://api.exemplo.com", isDebugBuild = false))
    }

    @Test
    fun `release recusa o host do emulador e o localhost`() {
        for (url in listOf(
            "https://10.0.2.2:8080",
            "http://10.0.2.2:8080",
            "https://localhost:8080",
            "https://127.0.0.1:8080"
        )) {
            assertNull("release não pode aceitar $url", SparkBackendEndpoint.resolve(url, false))
        }
    }

    @Test
    fun `release recusa endereco de rede privada`() {
        // Numa rede diferente da de quem construiu o APK, esses endereços pertencem a **outra**
        // máquina. Não é só inútil: é apontar o app para um servidor desconhecido.
        for (url in listOf(
            "https://192.168.0.10:8080",
            "https://10.1.2.3",
            "https://172.16.0.5",
            "https://172.31.255.1",
            "https://169.254.1.1",
            "https://100.64.0.1"
        )) {
            assertNull("release não pode aceitar $url", SparkBackendEndpoint.resolve(url, false))
        }
    }

    @Test
    fun `release aceita um IP publico em HTTPS`() {
        // A regra é sobre esquema e alcance, não sobre "ser um nome": 172.15 e 172.32 estão fora
        // do bloco privado, e recusá-los seria uma regra errada disfarçada de cuidado.
        assertEquals(
            "https://172.15.0.1",
            SparkBackendEndpoint.resolve("https://172.15.0.1", isDebugBuild = false)
        )
        assertEquals(
            "https://172.32.0.1",
            SparkBackendEndpoint.resolve("https://172.32.0.1", isDebugBuild = false)
        )
    }

    // ------------------------------------------------------------------ debug

    @Test
    fun `debug aceita texto claro apenas nos hosts nominais de desenvolvimento`() {
        // Exatamente os hosts que `src/debug/res/xml/network_security_config.xml` abre. Os dois
        // precisam concordar: um endereço que o app aceita e o sistema recusa vira uma falha de
        // rede sem explicação.
        assertEquals(
            "http://10.0.2.2:8080",
            SparkBackendEndpoint.resolve("http://10.0.2.2:8080", isDebugBuild = true)
        )
        assertEquals(
            "http://localhost:8080",
            SparkBackendEndpoint.resolve("http://localhost:8080", isDebugBuild = true)
        )
        assertNull(SparkBackendEndpoint.resolve("http://192.168.0.10:8080", isDebugBuild = true))
        assertNull(SparkBackendEndpoint.resolve("http://api.exemplo.com", isDebugBuild = true))
    }

    @Test
    fun `debug tambem aceita HTTPS em qualquer host`() {
        assertEquals(
            "https://api.exemplo.com",
            SparkBackendEndpoint.resolve("https://api.exemplo.com", isDebugBuild = true)
        )
    }

    // ------------------------------------------------------------------ ausência e lixo

    @Test
    fun `vazio significa backend nao configurado nas duas variantes`() {
        // E isso é um estado normal e completo do Spark: sem endereço, nenhuma requisição sai e o
        // núcleo continua inteiro.
        for (debug in listOf(true, false)) {
            assertNull(SparkBackendEndpoint.resolve("", debug))
            assertNull(SparkBackendEndpoint.resolve("   ", debug))
        }
    }

    @Test
    fun `esquema desconhecido nao vira endereco`() {
        for (url in listOf("api.exemplo.com", "ftp://api.exemplo.com", "javascript:alert(1)", "://x")) {
            assertNull("não pode aceitar $url", SparkBackendEndpoint.resolve(url, false))
            assertNull("não pode aceitar $url", SparkBackendEndpoint.resolve(url, true))
        }
    }

    @Test
    fun `credencial embutida na URL e recusada`() {
        // `https://api.exemplo.com@10.0.2.2/` tem host `10.0.2.2`, e não `api.exemplo.com`. Aceitar
        // essa forma é aceitar que o endereço mente sobre para onde vai.
        assertNull(SparkBackendEndpoint.resolve("https://api.exemplo.com@10.0.2.2/", false))
    }
}

/**
 * A configuração de rede e de endereço, verificada nos arquivos que o build realmente usa.
 *
 * Estes são testes estruturais: eles falham quando alguém reabre em release uma porta que a T16.2
 * e a T16.8 fecharam — e essa é uma alteração que passa despercebida em revisão de código.
 */
class SparkProductionNetworkConfigTest {

    @Test
    fun `nenhum manifesto permite texto claro globalmente`() {
        // `usesCleartextTraffic="true"` no manifesto sobrepõe a política e reabre HTTP para tudo.
        // Ele não pode existir em nenhum source set — nem "temporariamente para desenvolver".
        for (name in listOf("main", "debug", "release")) {
            val manifest = sourceFile("src/$name/AndroidManifest.xml") ?: continue
            assertTrue(
                "usesCleartextTraffic não pode aparecer em src/$name/AndroidManifest.xml",
                !manifest.readText().contains("usesCleartextTraffic")
            )
        }
    }

    @Test
    fun `a configuracao de rede principal proibe texto claro`() {
        val config = sourceFile("src/main/res/xml/network_security_config.xml")
        assertTrue("network_security_config.xml de main não encontrado", config != null)
        val text = config!!.readText()
        assertTrue(
            "a base-config de main precisa proibir texto claro",
            text.contains("cleartextTrafficPermitted=\"false\"")
        )
        // A exceção de desenvolvimento vive só em `src/debug`; se ela aparecer aqui, vale para o
        // APK publicado.
        assertTrue(
            "o config de main não pode abrir exceção para host de desenvolvimento",
            !text.contains("10.0.2.2") && !text.contains("localhost")
        )
    }

    @Test
    fun `a excecao de texto claro existe apenas no source set de depuracao`() {
        val debugConfig = sourceFile("src/debug/res/xml/network_security_config.xml")
        assertTrue("network_security_config.xml de debug não encontrado", debugConfig != null)
        assertTrue(
            "a exceção nominal de desenvolvimento precisa estar em src/debug",
            debugConfig!!.readText().contains("10.0.2.2")
        )
        assertNull(
            "não pode existir configuração de rede própria em src/release",
            sourceFile("src/release/res/xml/network_security_config.xml")
        )
    }

    @Test
    fun `o build de release valida o endereco antes de gerar o APK`() {
        // O portão de build (§6). Sem ele, a proteção de runtime existiria, mas o APK inseguro
        // ainda seria produzido — e alguém o publicaria antes de descobrir.
        val gradle = sourceFile("build.gradle.kts")
        assertTrue("app/build.gradle.kts não encontrado", gradle != null)
        val text = gradle!!.readText()
        assertTrue(
            "o release precisa passar pelo validador de endereço",
            text.contains("releaseBackendBaseUrl(providers)")
        )
        assertTrue(
            "o validador precisa exigir HTTPS",
            text.contains("https://")
        )
    }

    private fun sourceFile(relative: String): File? =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
}
