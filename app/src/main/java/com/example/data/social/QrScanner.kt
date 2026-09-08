package com.example.data.social

/**
 * A leitura de um QR Code, como o app a enxerga (T17.1 §57–§59).
 *
 * Uma interface de um método, e o motivo é o de sempre: a implementação real depende do Google
 * Play Services, e nenhum teste pode depender dele. Com a fronteira, a tela é testável com um
 * dublê e o parser — que é onde mora a decisão de segurança — é testado sozinho.
 *
 * ## O que a implementação real **não** faz
 *
 * - **não pede permissão de câmera.** O Google Code Scanner abre a câmera na UI do próprio Play
 *   Services e devolve só o texto lido; `android.permission.CAMERA` não entra no manifesto do
 *   Spark por causa desta tela;
 * - **não constrói detector próprio.** Nada de `CameraX` + análise de frame + localizador de
 *   padrão: escrever um detector de QR é um projeto, não um passo desta tarefa;
 * - **não abre nada.** O que volta daqui é texto. Quem decide o que ele significa é
 *   [SparkFriendQr.parse], e a única coisa que ele pode virar é um código de amigo — nunca uma
 *   navegação, nunca uma `Intent`, nunca uma `WebView`.
 */
interface QrScanner {

    /** `true` quando este aparelho consegue abrir um leitor. */
    val isAvailable: Boolean

    /**
     * Abre o leitor e espera.
     *
     * Devolve [QrScan.Cancelled] quando a pessoa fecha o leitor — que não é erro —, e
     * [QrScan.Unavailable] quando não há leitor neste aparelho. O conteúdo lido passa por
     * [SparkFriendQr.parse] antes de virar qualquer coisa.
     */
    suspend fun scan(): QrScan
}
