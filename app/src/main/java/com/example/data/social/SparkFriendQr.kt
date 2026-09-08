package com.example.data.social

/**
 * O QR Code de convite do Spark (T17.1): o que ele carrega, e o que ele nunca carrega.
 *
 * ```text
 * spark://friend/v1/SPK-7K2P9D8Q
 * └─┬─┘   └──┬──┘ └┬┘ └────┬────┘
 *   │        │     │       └── o friendCode canônico — a ÚNICA informação do payload
 *   │        │     └────────── versão do formato
 *   │        └──────────────── o que este QR é
 *   └───────────────────────── esquema do Spark
 * ```
 *
 * ## O que é proibido entrar aqui
 *
 * Firebase UID, e-mail, token de autenticação, `deviceId`, `syncId`, endereço de servidor e
 * qualquer coisa de treino. O QR é uma foto que circula por WhatsApp, é impressa em papel, fica na
 * galeria e é fotografada por quem passa: tudo o que ele carrega é público **por construção**, não
 * por descuido. O `friendCode` já é a informação compartilhável do produto, e ela sozinha basta —
 * o `socialId` nem entra, porque quem lê vai resolvê-lo pelo lookup de qualquer forma.
 *
 * ## Por que versionado
 *
 * `v1` no caminho custa cinco caracteres e permite que um formato futuro (um convite com validade,
 * um código de grupo) conviva com os QRs já impressos. Sem ele, a única saída seria adivinhar o
 * formato pelo conteúdo.
 *
 * ## Por que `spark://` e não `https://`
 *
 * Um link `https` só é melhor quando existe um domínio real servindo um deep link — e não existe.
 * Um `https://spark.exemplo/f/CODE` que ninguém serve levaria a pessoa a um erro de navegador em
 * vez de ao app. Quando houver domínio e App Links verificados, [parse] ganha o segundo formato
 * sem quebrar os QRs deste.
 *
 * ## O scanner nunca navega
 *
 * [parse] devolve um `friendCode` ou uma recusa. Ele **não** abre `Intent`, não carrega URL, não
 * toca `WebView` e não executa nada — nem para um `spark://` de outro tipo. Um QR é conteúdo
 * arbitrário de origem desconhecida, e a única coisa que se faz com ele aqui é tentar ler um
 * código de amigo.
 */
object SparkFriendQr {

    const val SCHEME = "spark"
    const val HOST = "friend"
    const val VERSION = "v1"

    /** O prefixo completo do payload aceito. */
    const val PREFIX = "$SCHEME://$HOST/$VERSION/"

    /**
     * Teto do payload lido, em caracteres.
     *
     * Um QR válido tem 30 caracteres. O teto existe porque a entrada vem de fora: um QR pode
     * carregar quilobytes, e nada nesse tamanho é um código de amigo. Recusar antes de qualquer
     * processamento é mais barato — e mais seguro — do que descobrir isso depois.
     */
    const val MAX_PAYLOAD_LENGTH = 512

    /** O payload que vira imagem. [friendCode] já vem canônico, do servidor. */
    fun payloadFor(friendCode: String): String {
        val canonical = FriendshipContract.normalizeFriendCode(friendCode)
            ?: error("friendCode fora da forma canônica")
        return "$PREFIX$canonical"
    }

    /**
     * O `friendCode` de um QR lido, ou [QrScan.Invalid].
     *
     * Recusa, sem tentativa de adivinhação: payload grande demais, esquema diferente, host
     * diferente, versão desconhecida, sobra depois do código e código malformado. Uma URL
     * qualquer, um QR de PIX e um `spark://workout/...` são todos [QrScan.Invalid] — e nenhum
     * deles é aberto, seguido ou executado.
     */
    fun parse(raw: String?): QrScan {
        if (raw == null || raw.length > MAX_PAYLOAD_LENGTH) return QrScan.Invalid

        val trimmed = raw.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return QrScan.Invalid

        val remainder = trimmed.substring(PREFIX.length)
        // Nada depois do código: sem query, sem fragmento, sem segmento extra. Aceitar "sobra"
        // permitiria a um QR carregar carga adicional que alguém, um dia, decidiria interpretar.
        if (remainder.any { it == '/' || it == '?' || it == '#' }) return QrScan.Invalid

        val code = FriendshipContract.normalizeFriendCode(remainder) ?: return QrScan.Invalid
        return QrScan.FriendCode(code)
    }
}

/** O resultado de ler um QR. */
sealed interface QrScan {

    /** Um código de amigo do Spark, já na forma canônica. */
    data class FriendCode(val value: String) : QrScan

    /** Não é um convite do Spark. A tela diz isso, e nada é aberto. */
    data object Invalid : QrScan

    /** A leitura foi cancelada pelo usuário (ele fechou o scanner). Não é erro. */
    data object Cancelled : QrScan

    /** Não foi possível abrir o leitor neste aparelho. A tela oferece digitar o código. */
    data object Unavailable : QrScan
}
