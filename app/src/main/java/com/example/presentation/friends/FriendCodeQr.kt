package com.example.presentation.friends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.social.QrCode
import com.example.data.social.QrMatrix
import com.example.ui.theme.TextSecondary
import kotlin.math.floor

/** Descrição de acessibilidade do QR. O código em si já é lido no texto ao lado. */
const val FRIEND_QR_DESCRIPTION = "QR Code do seu código de amigo"

/** O texto que a tela mostra quando o QR não pôde ser desenhado. */
const val FRIEND_QR_UNAVAILABLE = "Não foi possível gerar o QR Code. Use o código acima."

/**
 * O QR Code do próprio código de amigo (T17.1).
 *
 * Gerado **no aparelho**, a partir do `friendCode` que o perfil já trouxe: nenhuma requisição, e
 * ele funciona sem internet. O conteúdo é `spark://friend/v1/SPK-XXXXXXXX` e nada mais — sem
 * Firebase UID, sem e-mail, sem token, sem `socialId`, sem endereço de servidor.
 *
 * ## Desenhado, e não escalado
 *
 * A matriz é pintada em `Canvas` no tamanho real do espaço disponível, com cada módulo alinhado ao
 * pixel (`floor`). Um `Bitmap` de tamanho fixo reescalado apareceria borrado — e um QR borrado é
 * um QR que a câmera do amigo não lê.
 *
 * ## Sempre claro, mesmo no tema escuro
 *
 * O fundo é branco e os módulos são pretos, por decisão: leitores esperam esse contraste, e um QR
 * "escuro sobre escuro" para de ser lido por parte dos aparelhos. É o único ponto da UI do Spark
 * que ignora o tema, e ignora por causa da câmera do outro lado.
 */
@Composable
fun FriendCodeQr(
    friendCode: String,
    modifier: Modifier = Modifier
) {
    // `remember` pela chave do código: a matriz não muda enquanto o código não muda, e recalculá-la
    // a cada recomposição seria trabalho puro em um valor imutável.
    val matrix = remember(friendCode) { QrCode.forFriendCode(friendCode) }

    if (matrix == null) {
        Text(
            text = FRIEND_QR_UNAVAILABLE,
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth()
        )
        return
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                // A zona silenciosa: o QR precisa de borda clara ao redor para ser localizado.
                .padding(16.dp)
                .semantics { contentDescription = FRIEND_QR_DESCRIPTION }
        ) {
            drawQrMatrix(matrix)
        }
    }
}

/**
 * Pinta a matriz preenchendo o espaço disponível.
 *
 * O tamanho de cada módulo é calculado em ponto flutuante e cada quadrado é desenhado até a borda
 * do **próximo** módulo (`floor` nas duas pontas). Arredondar o tamanho de um módulo e multiplicar
 * acumularia erro ao longo de 25 a 33 módulos e deixaria fresta branca entre eles — que é
 * exatamente o tipo de artefato que faz um leitor desistir.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawQrMatrix(matrix: QrMatrix) {
    val side = minOf(size.width, size.height)
    val module = side / matrix.size
    val originX = (size.width - side) / 2f
    val originY = (size.height - side) / 2f

    for (row in 0 until matrix.size) {
        for (column in 0 until matrix.size) {
            if (!matrix[row, column]) continue

            val left = floor(originX + column * module)
            val top = floor(originY + row * module)
            val right = floor(originX + (column + 1) * module)
            val bottom = floor(originY + (row + 1) * module)

            drawRect(
                color = Color.Black,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top)
            )
        }
    }
}
