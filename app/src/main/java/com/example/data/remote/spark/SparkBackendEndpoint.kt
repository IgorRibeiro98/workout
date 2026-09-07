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
     * Endereço de rede privada.
     *
     * Um APK de release apontando para `192.168.x.x` não é apenas inútil fora daquela rede: é um
     * endereço que, na rede de outra pessoa, pertence a outra máquina.
     */
    private fun isPrivateAddress(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4) return false
        val numbers = octets.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        return when {
            numbers[0] == 10 -> true
            numbers[0] == 127 -> true
            numbers[0] == 172 && numbers[1] in 16..31 -> true
            numbers[0] == 192 && numbers[1] == 168 -> true
            // Link-local (169.254.0.0/16) e carrier-grade NAT (100.64.0.0/10).
            numbers[0] == 169 && numbers[1] == 254 -> true
            numbers[0] == 100 && numbers[1] in 64..127 -> true
            else -> false
        }
    }
}
