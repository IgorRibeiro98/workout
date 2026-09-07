package com.example.presentation.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A sincronização entre aparelhos, dentro da Conta Spark, no Perfil (T16.6).
 *
 * ## O que este texto pode e não pode prometer
 *
 * Ele **não** diz "tempo real" nem "sempre sincronizado": não existe WebSocket, não existe push do
 * servidor e a convergência acontece quando o app abre, quando o trabalho agendado roda ou quando
 * o usuário toca no botão. O que a tela afirma é o que existe — "atualizado agora", "aguardando
 * conexão", "precisa de atenção".
 *
 * Ele também informa a limitação que mais pode enganar: **exclusões ainda não chegam aos outros
 * aparelhos**. Esconder isso faria o usuário confiar em uma convergência que a T16.6 não entrega,
 * e a T16.7 é quem a completa.
 *
 * ## Sem jargão
 *
 * `cursor`, `revision` e `serverSequence` não aparecem aqui. Eles existem no banco e no log
 * técnico, e nenhum deles ajuda quem está olhando a tela.
 */
@Composable
fun SyncSection(
    uiState: SyncUiState,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (uiState.phase is SyncPhase.NotConfigured) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Sincronização",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = SYNC_SECTION_DESCRIPTION }
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (val phase = uiState.phase) {
                    SyncPhase.NotConfigured -> Unit

                    SyncPhase.Disabled -> Explanation(
                        title = "Sincronização desligada",
                        body = "Ative o backup acima para que os dados deste aparelho passem a " +
                            "pertencer à sua Conta Spark. Só depois disso eles podem ser " +
                            "sincronizados com outros aparelhos."
                    )

                    SyncPhase.AuthRequired -> Explanation(
                        title = "Entre para sincronizar",
                        body = "Entre na Conta Spark acima. Seus treinos e seu histórico " +
                            "continuam funcionando normalmente enquanto isso."
                    )

                    SyncPhase.AccountMismatch -> Warning(
                        title = "Dados de outra Conta Spark",
                        // Nada da conta dona é exposto: nem e-mail, nem nome, nem uid.
                        body = "Os dados deste aparelho pertencem a outra Conta Spark. A " +
                            "sincronização fica indisponível para a conta atual, e o restante do " +
                            "Spark continua completo."
                    )

                    SyncPhase.Syncing -> Busy("Sincronizando...")

                    is SyncPhase.UpToDate -> {
                        Status(title = "Atualizado", detail = lastSyncText(phase.lastSyncedAt))
                        SyncButton(onSyncNow)
                        DeleteNotice(uiState.deferredDeletes)
                    }

                    is SyncPhase.Pending -> {
                        Status(
                            title = pluralChanges(phase.pending),
                            detail = lastSyncText(phase.lastSyncedAt)
                        )
                        SyncButton(onSyncNow)
                        DeleteNotice(uiState.deferredDeletes)
                    }

                    is SyncPhase.Offline -> {
                        Status(
                            title = pluralChanges(phase.pending),
                            detail = "Sem conexão agora. Nada foi perdido: as alterações sobem " +
                                "quando a rede voltar."
                        )
                        SyncButton(onSyncNow)
                    }

                    is SyncPhase.NeedsAttention -> {
                        Warning(
                            title = attentionTitle(phase.items),
                            // A T16.6 detecta e preserva; a resolução é da T16.7. A tela não
                            // promete uma tela de resolução que ainda não existe.
                            body = "Este item foi alterado aqui e em outro aparelho. As duas " +
                                "versões estão guardadas e nada foi sobrescrito. A escolha entre " +
                                "elas chega em uma próxima versão do Spark."
                        )
                        SyncButton(onSyncNow)
                    }

                    is SyncPhase.Failed -> {
                        Warning(title = "Sincronização não concluída", body = failureText(phase.reason))
                        SyncButton(onSyncNow)
                    }
                }
            }
        }
    }
}

@Composable
private fun Status(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(text = detail, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun Explanation(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(text = body, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun Warning(title: String, body: String) {
    Surface(
        color = SurfaceHighlight,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = title, color = Red400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(text = body, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

/**
 * A limitação que mais pode enganar, dita em voz alta.
 *
 * Nada no app esconde o botão de excluir por causa dela: o usuário continua apagando o que quiser,
 * localmente. O que ele precisa saber é que essa exclusão ainda não viaja.
 */
@Composable
private fun DeleteNotice(deferredDeletes: Int) {
    if (deferredDeletes <= 0) return
    Text(
        text = "Itens excluídos neste aparelho ainda não são removidos nos outros. Isso chega em " +
            "uma próxima versão do Spark.",
        color = TextSecondary,
        fontSize = 12.sp
    )
}

@Composable
private fun Busy(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(color = Lime400, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Text(text = text, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun SyncButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Lime400,
            contentColor = SurfaceDark,
            disabledContainerColor = SurfaceHighlight,
            disabledContentColor = TextSecondary
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        Text(text = "Sincronizar agora", fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

private fun pluralChanges(pending: Int): String =
    if (pending == 1) "1 alteração aguardando envio" else "$pending alterações aguardando envio"

private fun attentionTitle(items: Int): String =
    if (items == 1) "1 item precisa de atenção" else "$items itens precisam de atenção"

/**
 * "Última sincronização", sem prometer tempo real.
 *
 * O Spark não tem WebSocket nem push do servidor, então dizer "sempre atualizado" seria falso.
 * Dizer **quando** foi a última vez é verdade e é útil.
 */
private fun lastSyncText(lastSyncedAt: Long?): String = when (lastSyncedAt) {
    null -> "Ainda não sincronizado com outros aparelhos."
    else -> "Última sincronização: ${formatTimestamp(lastSyncedAt)}"
}

private fun failureText(reason: SyncFailure): String = when (reason) {
    SyncFailure.UNAVAILABLE ->
        "O servidor do Spark está indisponível. Seus treinos continuam normais aqui."
    SyncFailure.RATE_LIMITED ->
        "Muitas tentativas seguidas. A sincronização volta sozinha em instantes."
    SyncFailure.REJECTED ->
        "O servidor recusou esta sincronização. Nada foi alterado neste aparelho."
    SyncFailure.NEEDS_APP_UPDATE ->
        "Outro aparelho enviou algo que esta versão do Spark ainda não entende. Nada foi " +
            "perdido: atualize o app para continuar."
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(epochMillis))

/** Descrição de acessibilidade da seção — também o gancho dos testes de UI. */
const val SYNC_SECTION_DESCRIPTION = "Sincronização"
