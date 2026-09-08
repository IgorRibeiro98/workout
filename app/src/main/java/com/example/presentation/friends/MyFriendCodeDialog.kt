package com.example.presentation.friends

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

const val MY_FRIEND_CODE_TITLE = "Meu código"
const val COPY_FRIEND_CODE_LABEL = "Copiar código"
const val SHARE_FRIEND_CODE_LABEL = "Compartilhar"
const val MY_FRIEND_CODE_DESCRIPTION = "Seu código de amigo e o QR Code dele"

/**
 * "Meu código": o código de amigo em texto, o QR e as duas formas de passá-lo adiante (T17.1 §65).
 *
 * ## O que sai daqui, e o que nunca sai
 *
 * Copiar copia **o código**. Compartilhar compartilha **o código**. Nem um nem outro leva
 * `socialId`, Firebase UID, e-mail ou qualquer coisa de treino — o código é a única identidade do
 * Spark feita para circular, e é a única que circula.
 *
 * ## Sobre proteger a tela
 *
 * Ela não é protegida contra captura, e isso é deliberado: o `friendCode` **existe** para ser
 * fotografado, mostrado e colado num grupo. Ele não é credencial — quem o tiver consegue, no
 * máximo, mandar um pedido que você aceita ou recusa. Esconder um dado compartilhável sugeriria
 * uma proteção que ele não tem e não precisa.
 *
 * ## A área de transferência é só de escrita
 *
 * O Spark **nunca lê** a área de transferência — nem para "detectar um código copiado", que seria
 * o caminho educado para ler tudo o que a pessoa copiou. Escrever acontece só no toque.
 */
@Composable
fun MyFriendCodeDialog(
    friendCode: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text(MY_FRIEND_CODE_TITLE, color = TextPrimary) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics { contentDescription = MY_FRIEND_CODE_DESCRIPTION }
            ) {
                Text(
                    text = friendCode,
                    color = Lime400,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Quem tiver este código pode te mandar um pedido de amizade. Ele não " +
                        "dá acesso aos seus treinos, medidas ou histórico.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                FriendCodeQr(friendCode = friendCode, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { copyFriendCode(context, friendCode) }) {
                Text(COPY_FRIEND_CODE_LABEL, color = Lime400)
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { shareFriendCode(context, friendCode) }) {
                    Text(SHARE_FRIEND_CODE_LABEL, color = TextSecondary)
                }
                TextButton(onClick = onDismiss) {
                    Text("Fechar", color = TextSecondary)
                }
            }
        }
    )
}

/** Escreve **o código**, e nada além dele, na área de transferência. */
private fun copyFriendCode(context: Context, friendCode: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(MY_FRIEND_CODE_TITLE, friendCode))
}

/**
 * Abre o menu de compartilhamento do Android com o código.
 *
 * Texto simples, montado aqui: nenhum link, nenhuma URL de servidor, nenhum identificador além do
 * código. O que a pessoa manda é o que ela leria em voz alta.
 */
private fun shareFriendCode(context: Context, friendCode: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Me adiciona no Spark: $friendCode")
    }
    runCatching { context.startActivity(Intent.createChooser(intent, SHARE_FRIEND_CODE_LABEL)) }
}
