package com.example.presentation.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.social.FriendRelationship
import com.example.presentation.account.FriendsAction
import com.example.presentation.account.FriendsUiState
import com.example.presentation.account.LookupState
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

const val ADD_FRIEND_TITLE = "Adicionar amigo"
const val ADD_FRIEND_FIELD_LABEL = "Código de amigo"
const val LOOKUP_LABEL = "Procurar"
const val SCAN_QR_LABEL = "Ler QR Code"
const val SEND_REQUEST_LABEL = "Enviar solicitação"
const val SELF_CODE_MESSAGE = "Este é o seu próprio código."
const val CODE_NOT_FOUND_MESSAGE = "Nenhum perfil com esse código."
const val INVALID_QR_MESSAGE = "Este QR Code não é um convite do Spark."
const val REQUEST_SENT_MESSAGE = "Solicitação enviada"
const val ADD_FRIEND_DESCRIPTION = "Adicionar amigo por código ou QR Code"

/**
 * "Adicionar amigo" (T17.1 §68–§70).
 *
 * ```text
 * digitar código  ┐
 *                 ├──▶ Procurar ──▶ preview ──▶ [Enviar solicitação] ──▶ "Solicitação enviada"
 * ler QR Code     ┘                                                             └─ [Cancelar]
 * ```
 *
 * ## Procurar nunca envia
 *
 * Este é o ponto do fluxo que mais importa: a busca mostra **quem** apareceu e para. Enviar exige
 * um segundo toque, sobre um nome visível. Sem isso, um caractere errado viraria um convite para
 * um desconhecido — e convite enviado não é uma coisa que dê para "desfazer sem ninguém ver".
 *
 * ## O que a tela diz quando não encontra
 *
 * Uma frase só, para todos os casos: código inexistente, malformado e de um perfil desativado. Ela
 * é assim porque o **servidor** responde a mesma coisa para os três de propósito, e inventar
 * variações aqui ("esse perfil está desativado") diria justamente o que ele existe para esconder.
 */
@Composable
fun AddFriendDialog(
    uiState: FriendsUiState,
    onCodeChange: (String) -> Unit,
    onLookup: () -> Unit,
    onScan: () -> Unit,
    onSend: () -> Unit,
    onCancelRequest: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text(ADD_FRIEND_TITLE, color = TextPrimary) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics { contentDescription = ADD_FRIEND_DESCRIPTION }
            ) {
                OutlinedTextField(
                    value = uiState.codeInput,
                    onValueChange = onCodeChange,
                    singleLine = true,
                    label = { Text(ADD_FRIEND_FIELD_LABEL, color = TextSecondary) },
                    placeholder = { Text("SPK-________", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onLookup,
                        enabled = uiState.canLookup,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime400,
                            contentColor = BackgroundDark
                        )
                    ) {
                        Text(LOOKUP_LABEL, fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = onScan,
                        enabled = uiState.action == FriendsAction.NONE
                    ) {
                        Text(SCAN_QR_LABEL, color = TextSecondary)
                    }
                }

                when (uiState.action) {
                    FriendsAction.LOOKING_UP -> Busy("Procurando...")
                    FriendsAction.SCANNING -> Busy("Abrindo o leitor...")
                    FriendsAction.SENDING_REQUEST -> Busy("Enviando...")
                    else -> Unit
                }

                LookupResult(
                    lookup = uiState.lookup,
                    isSending = uiState.action == FriendsAction.SENDING_REQUEST,
                    onSend = onSend,
                    onCancelRequest = onCancelRequest
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun LookupResult(
    lookup: LookupState,
    isSending: Boolean,
    onSend: () -> Unit,
    onCancelRequest: (String) -> Unit
) {
    when (lookup) {
        LookupState.Empty -> Unit

        is LookupState.Found -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = lookup.profile.displayName,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            when (lookup.relationship) {
                FriendRelationship.FRIENDS ->
                    Hint("Vocês já são amigos.")
                FriendRelationship.OUTGOING_PENDING ->
                    Hint("Você já enviou uma solicitação para essa pessoa.")
                FriendRelationship.INCOMING_PENDING ->
                    // Enviar aqui **funciona** e vira amizade na hora: os dois já pediram.
                    Hint("Essa pessoa te enviou uma solicitação. Enviar agora já cria a amizade.")
                FriendRelationship.NONE -> Unit
            }
            if (lookup.canSendFriendRequest) {
                Button(
                    onClick = onSend,
                    enabled = !isSending,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Lime400,
                        contentColor = BackgroundDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(SEND_REQUEST_LABEL, fontWeight = FontWeight.Bold)
                }
            } else if (lookup.relationship == FriendRelationship.NONE) {
                Hint("Essa pessoa não está aceitando pedidos de amizade agora.")
            }
        }

        LookupState.Self -> Hint(SELF_CODE_MESSAGE)

        LookupState.NotFound -> Hint(CODE_NOT_FOUND_MESSAGE)

        LookupState.InvalidQr -> Hint(INVALID_QR_MESSAGE)

        LookupState.ScannerUnavailable ->
            Hint("Não foi possível abrir o leitor neste aparelho. Digite o código.")

        is LookupState.RequestSent -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "$REQUEST_SENT_MESSAGE para ${lookup.profile.displayName}",
                color = Lime400,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Hint("Ela precisa aceitar para vocês virarem amigos.")
            TextButton(onClick = { onCancelRequest(lookup.requestId) }) {
                Text("Cancelar solicitação", color = Red400)
            }
        }

        is LookupState.BecameFriends -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Agora vocês são amigos",
                color = Lime400,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Hint("${lookup.friend.displayName} já tinha enviado uma solicitação para você.")
        }

        is LookupState.Failed -> Hint(messageFor(lookup.reason), isError = true)
    }
}

@Composable
private fun Hint(text: String, isError: Boolean = false) {
    Text(
        text = text,
        color = if (isError) Red400 else TextSecondary,
        fontSize = 13.sp
    )
}
