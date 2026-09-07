package com.example.data.remote.spark

/**
 * A regra única sobre qual endereço do Spark Backend o aplicativo aceita usar (T16.8 §6/§7).
 *
 * Existe porque "o release usa HTTPS" precisava ser uma **invariante verificável**, e não uma
 * convenção mantida por atenção. Até a T16.7 o endereço vinha de `BuildConfig` e era usado como
 * chegava: um `-PsparkBackendBaseUrl=http://192.168.0.10:8080` produzia um APK de release que
 * falava em texto claro com uma máquina de desenvolvimento, e nada no app reclamaria.
 *
 * ```text
 * BuildConfig.SPARK_BACKEND_BASE_URL ──▶ resolve(url, isDebugBuild) ──▶ URL válida ou null
 * ```
 *
 * `null` significa "backend não configurado", que é um estado normal e completo do Spark: sem
 * endereço, nenhuma requisição sai, o Coach responde indisponível, backup e sync ficam fora — e
 * treino, execução, histórico, templates e gamificação continuam inteiros. **Recusar um endereço
 * inseguro nunca degrada o núcleo do app**; ele apenas deixa a nuvem desligada, exatamente como um
 * build sem endereço nenhum.
 *
 * A verificação é feita duas vezes de propósito: aqui, em runtime, e no `build.gradle.kts`, que
 * falha o build de release antes de o APK existir. A do Gradle é a que impede o engano de
 * acontecer; esta é a que impede o engano de funcionar.
 */
object SparkBackendEndpoint {

    /** Hosts que só fazem sentido em desenvolvimento — e que jamais podem valer em release. */
    private val DEVELOPMENT_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1", "::1")

    /**
     * O endereço utilizável, ou `null` quando não há um.
     *
     * @param rawBaseUrl o valor de `BuildConfig.SPARK_BACKEND_BASE_URL`.
     * @param isDebugBuild `BuildConfig.DEBUG`. A exceção de texto claro é de desenvolvimento e
     *   acompanha exatamente o que `src/debug/res/xml/network_security_config.xml` permite — os
     *   dois precisam concordar, senão o app tentaria uma conexão que o sistema recusaria.
     */
    fun resolve(rawBaseUrl: String, isDebugBuild: Boolean): String? {
        val trimmed = rawBaseUrl.trim()
        if (trimmed.isEmpty()) return null

        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return null

        val host = hostOf(trimmed) ?: return null
        val isDevelopmentHost = host in DEVELOPMENT_HOSTS || isPrivateAddress(host)

        return when {
            // Release: HTTPS, sempre, e nunca apontando para a máquina de quem desenvolve. Não há
            // fallback, não há "só desta vez" e não há flag que relaxe isto.
            !isDebugBuild -> if (scheme == "https" && !isDevelopmentHost) trimmed else null

            // Depuração: HTTPS vale para qualquer host; texto claro **só** para os hosts nominais
            // que o network security config de debug abre.
            scheme == "https" -> trimmed
            else -> if (host in DEVELOPMENT_HOSTS) trimmed else null
        }
    }

    /** O host de uma URL, sem depender de `java.net.URI` (que aceita coisas demais em silêncio). */
    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        // Credencial embutida na URL não é suportada: `https://a@b/` esconde qual é o host real.
        if (authority.contains('@')) return null
        val host = if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        return host.lowercase().takeIf { it.isNotEmpty() }
    }

    /**
     * Endereço de rede privada, em IPv4 ou IPv6.
     *
     * Um APK de release apontando para `192.168.x.x` não é apenas inútil fora daquela rede: é um
     * endereço que, na rede de outra pessoa, pertence a outra máquina. Vale igual para `fc00::/7`,
     * que é o equivalente IPv6 — e que até a T16.8.1 passava, porque o endurecimento da T16.8 só
     * conhecia os quatro octetos do IPv4.
     */
    private fun isPrivateAddress(host: String): Boolean =
        if (host.contains(':')) isPrivateIpv6(host) else isPrivateIpv4(host)

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4) return false
        val numbers = octets.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        return isPrivateIpv4Octets(numbers)
    }

    private fun isPrivateIpv4Octets(numbers: List<Int>): Boolean = when {
        numbers[0] == 10 -> true
        numbers[0] == 127 -> true
        numbers[0] == 172 && numbers[1] in 16..31 -> true
        numbers[0] == 192 && numbers[1] == 168 -> true
        // Link-local (169.254.0.0/16) e carrier-grade NAT (100.64.0.0/10).
        numbers[0] == 169 && numbers[1] == 254 -> true
        numbers[0] == 100 && numbers[1] in 64..127 -> true
        else -> false
    }

    /**
     * Endereço IPv6 que não é publicamente roteável (T16.8.1 §10).
     *
     * Um endereço que não conseguimos entender é tratado como **privado**, e a assimetria é
     * deliberada: em release, o custo de recusar um endereço estranho é a nuvem ficar desligada —
     * um estado normal e completo do Spark. O custo de aceitar um que não entendemos é o APK
     * publicado falar com uma máquina qualquer da rede de quem o instalou.
     */
    private fun isPrivateIpv6(host: String): Boolean {
        // O identificador de zona (`fe80::1%eth0`, ou `%25eth0` percent-encoded na URL) descreve a
        // interface local, nunca um destino público.
        val withoutZone = host.substringBefore('%')
        val hextets = ipv6Hextets(withoutZone) ?: return true

        return when {
            // `::` — endereço não especificado.
            hextets.all { it == 0 } -> true
            // `::1` — loopback, na forma comprimida ou na expandida.
            hextets.take(7).all { it == 0 } && hextets[7] == 1 -> true
            // `fc00::/7` — unique local address, o equivalente a 10/8 e 192.168/16.
            hextets[0] and 0xFE00 == 0xFC00 -> true
            // `fe80::/10` — link-local.
            hextets[0] and 0xFFC0 == 0xFE80 -> true
            // `fec0::/10` — site-local, obsoleto mas ainda configurável em rede doméstica.
            hextets[0] and 0xFFC0 == 0xFEC0 -> true
            // `::ffff:a.b.c.d` — IPv4 mapeado. O endereço real é o IPv4, e a decisão é a dele:
            // `::ffff:192.168.1.1` é `192.168.1.1` escrito de outro jeito.
            hextets.take(5).all { it == 0 } && hextets[5] == 0xFFFF -> isPrivateIpv4Octets(
                listOf(
                    hextets[6] shr 8,
                    hextets[6] and 0xFF,
                    hextets[7] shr 8,
                    hextets[7] and 0xFF
                )
            )
            else -> false
        }
    }

    /**
     * Os 8 grupos de 16 bits de um endereço IPv6, ou `null` quando o texto não é um.
     *
     * Escrito à mão pelo mesmo motivo de `hostOf`: `java.net.InetAddress` resolve nomes (uma
     * chamada de rede em um caminho que precisa ser puro) e `java.net.URI` aceita coisas demais em
     * silêncio.
     */
    private fun ipv6Hextets(address: String): List<Int>? {
        var text = address

        // Forma mista `::ffff:1.2.3.4`: os quatro octetos viram os dois últimos hextets.
        val lastColon = text.lastIndexOf(':')
        if (lastColon >= 0 && text.substring(lastColon + 1).contains('.')) {
            val octets = text.substring(lastColon + 1).split('.')
            if (octets.size != 4) return null
            val numbers = octets.map { it.toIntOrNull() ?: return null }
            if (numbers.any { it !in 0..255 }) return null
            val high = (numbers[0] shl 8) or numbers[1]
            val low = (numbers[2] shl 8) or numbers[3]
            text = text.substring(0, lastColon + 1) +
                high.toString(16) + ":" + low.toString(16)
        }

        val halves = text.split("::")
        if (halves.size > 2) return null

        fun groupsOf(part: String): List<String>? {
            if (part.isEmpty()) return emptyList()
            val groups = part.split(':')
            return if (groups.any { it.isEmpty() }) null else groups
        }

        val head = groupsOf(halves[0]) ?: return null
        val tail = if (halves.size == 2) (groupsOf(halves[1]) ?: return null) else emptyList()

        val groups = if (halves.size == 2) {
            val zeros = 8 - head.size - tail.size
            if (zeros < 0) return null
            head + List(zeros) { "0" } + tail
        } else {
            head
        }
        if (groups.size != 8) return null

        return groups.map { group ->
            if (group.length > 4) return null
            val value = group.toIntOrNull(16) ?: return null
            if (value !in 0..0xFFFF) return null
            value
        }
    }
}
