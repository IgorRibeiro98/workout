package com.example.data.social

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * O leitor de QR do Spark, sobre o Google Code Scanner (T17.1).
 *
 * ```text
 * usuário toca "Ler QR Code"
 *        ↓
 * UI do Play Services abre a câmera   ← fora do processo do Spark
 *        ↓
 * texto lido volta para cá
 *        ↓
 * SparkFriendQr.parse → friendCode | Invalid
 * ```
 *
 * ## Por que este, e não CameraX + ML Kit
 *
 * Porque ele **não exige permissão de câmera**. A câmera é aberta pela UI do Play Services, e o
 * Spark recebe apenas o texto — então `android.permission.CAMERA` não precisa existir no
 * manifesto. Pedir acesso permanente à câmera do usuário por causa de uma tela que se usa três
 * vezes na vida seria caro demais pelo que se ganha; e a alternativa (CameraX + análise de frame
 * + o próprio ciclo de vida da câmera) seria mais código, mais superfície e a mesma leitura.
 *
 * O módulo do leitor é entregue sob demanda pelo Play Services. Onde ele não existe — aparelho sem
 * Play Services, por exemplo —, [scan] responde [QrScan.Unavailable] e a tela oferece digitar o
 * código à mão. **Ler QR nunca é o único caminho** para adicionar alguém.
 *
 * ## O que ele devolve, e o que ele nunca faz com isso
 *
 * Texto. Nada mais. Ele não abre `Intent`, não segue URL, não toca `WebView` e não interpreta
 * outro esquema — [SparkFriendQr.parse] é o único juiz do conteúdo, e a única coisa que um QR
 * pode virar aqui é um código de amigo.
 */
class GmsQrScanner(context: Context) : QrScanner {

    private val appContext = context.applicationContext

    /**
     * O leitor é restrito a **QR Code**.
     *
     * Sem `allowManualInput` (a tela do Spark já tem um campo de texto próprio, com a mesma
     * validação) e sem outros formatos: um leitor que aceitasse código de barras de supermercado
     * só produziria leituras inválidas.
     */
    private val scanner by lazy {
        GmsBarcodeScanning.getClient(
            appContext,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    override val isAvailable: Boolean
        get() = runCatching { ModuleInstall.getClient(appContext) }.isSuccess

    override suspend fun scan(): QrScan = suspendCancellableCoroutine { continuation ->
        runCatching {
            scanner
                .startScan()
                .addOnSuccessListener { barcode ->
                    // O conteúdo é sempre tratado como texto de origem desconhecida.
                    if (continuation.isActive) continuation.resume(SparkFriendQr.parse(barcode.rawValue))
                }
                .addOnCanceledListener {
                    // Fechar o leitor não é erro: é uma decisão.
                    if (continuation.isActive) continuation.resume(QrScan.Cancelled)
                }
                .addOnFailureListener {
                    // Módulo indisponível, Play Services ausente, câmera ocupada: todos levam à
                    // mesma saída útil — digitar o código continua funcionando.
                    if (continuation.isActive) continuation.resume(QrScan.Unavailable)
                }
        }.onFailure {
            if (continuation.isActive) continuation.resume(QrScan.Unavailable)
        }
    }
}
