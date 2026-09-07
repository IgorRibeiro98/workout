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

    // ------------------------------------------------------------------ IPv6 (T16.8.1 §10)

    @Test
    fun `release recusa loopback, ULA e link-local em IPv6`() {
        // O buraco que a T16.8 deixou: o endurecimento conhecia só os quatro octetos do IPv4, e um
        // endereço IPv6 privado passava pelo portão do build sem que nada reclamasse.
        for (url in listOf(
            "https://[::1]",
            "https://[::1]:8443",
            "https://[0:0:0:0:0:0:0:1]",
            "https://[::]",
            "https://[fc00::1]",
            "https://[fd12:3456:789a::1]",
            "https://[fe80::1]",
            "https://[fe80::1%25eth0]",
            "https://[febf::1]",
            "https://[fec0::1]"
        )) {
            assertNull("release não pode aceitar $url", SparkBackendEndpoint.resolve(url, false))
        }
    }

    @Test
    fun `release recusa IPv4 privado escrito como IPv6 mapeado`() {
        // `::ffff:192.168.1.1` é `192.168.1.1` escrito de outro jeito. Uma regra que olha só a
        // forma do texto deixaria passar exatamente o endereço que ela existe para recusar.
        assertNull(SparkBackendEndpoint.resolve("https://[::ffff:127.0.0.1]", false))
        assertNull(SparkBackendEndpoint.resolve("https://[::ffff:192.168.1.1]", false))
    }

    @Test
    fun `release aceita IPv6 publico`() {
        // Recusar todo IPv6 seria mais fácil e estaria errado: a regra é sobre alcance, não sobre
        // família de endereço.
        assertEquals(
            "https://[2001:db8::1]",
            SparkBackendEndpoint.resolve("https://[2001:db8::1]", isDebugBuild = false)
        )
        assertEquals(
            "https://[2606:4700:4700::1111]",
            SparkBackendEndpoint.resolve("https://[2606:4700:4700::1111]", isDebugBuild = false)
        )
    }
}

/**
 * Os dois portões de endereço concordam, caso a caso (T16.8.1 §10).
 *
 * `contracts/endpoint/release-endpoint-cases.tsv` é a tabela de decisão compartilhada. Este teste a
 * aplica ao portão de **runtime**; `./gradlew :app:verifyReleaseEndpointGate` aplica a mesma tabela
 * ao portão de **build**. É o que impede a divergência que a auditoria da T16.8 encontrou — o
 * Gradle aceitava `https://[fc00::1]` porque extraía o host `"["`, enquanto o runtime o recusava.
 *
 * Uma divergência nessa direção publica um APK com a nuvem inexplicavelmente desligada; na direção
 * contrária, bloqueia uma publicação legítima. Nenhuma das duas apareceria em revisão de código.
 */
class SparkReleaseEndpointTableTest {

    @Test
    fun `o portao de runtime concorda com a tabela compartilhada`() {
        val table = tableFile()
        assertTrue("contracts/endpoint/release-endpoint-cases.tsv não encontrado", table != null)

        val failures = mutableListOf<String>()
        var checked = 0

        table!!.readLines().forEach { line ->
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("#")) return@forEach

            val columns = text.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
            assertEquals("linha malformada na tabela: '$line'", 2, columns.size)

            val (verdict, url) = columns
            val expected = when (verdict) {
                "ACCEPT" -> true
                "REJECT" -> false
                else -> throw AssertionError("veredito desconhecido '$verdict' na tabela")
            }
            checked++

            val accepted = SparkBackendEndpoint.resolve(url, isDebugBuild = false) != null
            if (accepted != expected) {
                failures += "$url — esperado $verdict, o runtime disse " +
                    if (accepted) "ACCEPT" else "REJECT"
            }
        }

        // Uma tabela vazia passaria em silêncio e não verificaria nada.
        assertTrue("a tabela de casos está vazia", checked > 0)
        assertTrue(
            "o portão de runtime discorda da tabela:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun `a tabela cobre os casos que a T16 8 1 fechou`() {
        // Sem isto, esvaziar a tabela deixaria os dois portões "de acordo" sobre nada.
        val text = tableFile()!!.readText()
        for (obrigatorio in listOf(
            "REJECT\thttp://api.exemplo.com",
            "REJECT\thttps://[::1]",
            "REJECT\thttps://[fc00::1]",
            "REJECT\thttps://[fe80::1]",
            "REJECT\thttps://10.0.0.1",
            "REJECT\thttps://192.168.1.1",
            "REJECT\thttps://127.0.0.1",
            "ACCEPT\thttps://api.exemplo.com"
        )) {
            assertTrue("a tabela precisa conter: $obrigatorio", text.contains(obrigatorio))
        }
    }

    private fun tableFile(): File? =
        listOf(
            File("../contracts/endpoint/release-endpoint-cases.tsv"),
            File("contracts/endpoint/release-endpoint-cases.tsv")
        ).firstOrNull { it.isFile }
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
