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
import com.example.data.sync.SyncConflictCategory
import com.example.data.sync.SyncConflictChoice
import com.example.data.sync.SyncConflictId
import com.example.data.sync.SyncConflictSummary
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
 * A sincronização entre aparelhos, dentro da Conta Spark, no Perfil (T16.6/T16.7).
 *
 * ## O que este texto pode e não pode prometer
 *
 * Ele **não** diz "tempo real" nem "sempre sincronizado": não existe WebSocket, não existe push do
 * servidor e a convergência acontece quando o app abre, quando o trabalho agendado roda ou quando
 * o usuário toca no botão. O que a tela afirma é o que existe — "atualizado agora", "aguardando
 * conexão", "precisa de atenção".
 *
 * Desde a T16.7 ela também é onde o usuário **decide**: quando o mesmo item foi alterado aqui e em
 * outro aparelho, as duas versões são mostradas e ele escolhe. A tela nunca escolhe por ele, e
 * nunca oferece "juntar as duas" — combinar o nome de um lado com os exercícios do outro produziria
 * um treino que ninguém montou.
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
    onResolveConflict: (SyncConflictId, SyncConflictChoice) -> Unit = { _, _ -> },
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
                            body = "Nada foi sobrescrito: as duas versões estão guardadas. " +
                                "Escolha qual manter."
                        )
                        ConflictList(uiState, onResolveConflict)
                        SyncButton(onSyncNow)
                    }

                    is SyncPhase.NeedsRebaseline -> {
                        Warning(
                            title = "Sincronização precisa ser reconstruída",
                            // Nada é apagado e nada é restaurado sozinho: o texto orienta, e a
                            // ação com consequência continua sendo do usuário.
                            body = "Este aparelho perdeu a referência do que já recebeu da sua " +
                                "Conta Spark. Seus dados aqui continuam intactos. Restaure um " +
                                "backup nesta conta para reconstruir a sincronização."
                        )
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

/**
 * A lista de itens que precisam de decisão, com as diferenças e as escolhas (T16.7).
 *
 * ## O que ela não mostra
 *
 * `baseRevision=4`, `remoteRevision=5`, `syncId` e `cursor` não aparecem — eles não ajudam ninguém
 * a decidir qual treino manter. O que aparece é o nome dos dois lados e os poucos campos que
 * diferem.
 *
 * ## Por que "manter" e "usar" e não um terceiro botão
 *
 * Não existe "juntar as duas": combinar o nome de um lado com os exercícios do outro produziria um
 * treino que ninguém montou. A escolha é entre duas versões inteiras, que é a única que o usuário
 * consegue conferir.
 */
@Composable
private fun ConflictList(
    uiState: SyncUiState,
    onResolve: (SyncConflictId, SyncConflictChoice) -> Unit
) {
    if (uiState.conflicts.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        uiState.conflicts.forEach { conflict ->
            ConflictCard(
                conflict = conflict,
                enabled = !uiState.isBusy,
                onResolve = onResolve
            )
        }
        uiState.resolutionProblem?.let { problem ->
            Text(
                text = resolutionProblemText(problem),
                color = Red400,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ConflictCard(
    conflict: SyncConflictSummary,
    enabled: Boolean,
    onResolve: (SyncConflictId, SyncConflictChoice) -> Unit
) {
    Surface(
        color = SurfaceHighlight,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = CONFLICT_ITEM_DESCRIPTION }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = conflict.title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(text = categoryText(conflict.category), color = TextSecondary, fontSize = 12.sp)

            conflict.differences.forEach { difference ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = difference.label, color = TextPrimary, fontSize = 12.sp)
                    Text(
                        text = "Neste aparelho: ${difference.local ?: "—"}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Na nuvem: ${difference.remote ?: "—"}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            when {
                conflict.awaitingPush -> Text(
                    text = "Sua escolha está guardada e será enviada na próxima sincronização.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                conflict.choices.isEmpty() -> Text(
                    text = "Nenhuma versão foi sobrescrita. Este caso precisa de atenção manual.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                else -> conflict.choices.forEach { choice ->
                    ChoiceButton(
                        label = choiceLabel(choice),
                        enabled = enabled,
                        onClick = { onResolve(conflict.id, choice) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceDark,
            contentColor = TextPrimary,
            disabledContainerColor = SurfaceDark,
            disabledContentColor = TextSecondary
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
 * Uma limitação de convergência, dita em voz alta quando existir.
 *
 * Exclusões passaram a viajar na T16.7. O que pode sobrar são agregados cuja política não permite
 * exclusão remota — e, se isso acontecer, o usuário precisa saber, porque uma exclusão que ele fez
 * aqui não vai aparecer no outro aparelho.
 */
@Composable
private fun DeleteNotice(deferredDeletes: Int) {
    if (deferredDeletes <= 0) return
    Text(
        text = "Alguns itens excluídos neste aparelho não são removidos nos outros.",
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

private fun categoryText(category: SyncConflictCategory): String = when (category) {
    SyncConflictCategory.CHANGED_ON_BOTH ->
        "Alterado neste aparelho e em outro."
    SyncConflictCategory.DELETED_ELSEWHERE ->
        "Excluído em outro aparelho, mas alterado neste."
    SyncConflictCategory.DELETED_HERE ->
        "Excluído neste aparelho, mas alterado em outro."
    SyncConflictCategory.HISTORY_MISMATCH ->
        "Encontramos uma inconsistência em um treino concluído. Nenhuma versão foi sobrescrita."
    SyncConflictCategory.REJECTED ->
        "O servidor não aceitou esta alteração. Ela continua guardada aqui."
}

private fun choiceLabel(choice: SyncConflictChoice): String = when (choice) {
    SyncConflictChoice.KEEP_LOCAL -> "Manter deste aparelho"
    SyncConflictChoice.USE_REMOTE -> "Usar versão da nuvem"
    SyncConflictChoice.KEEP_LOCAL_AS_NEW -> "Manter como item novo"
    SyncConflictChoice.CONFIRM_REMOTE_DELETE -> "Confirmar exclusão"
    SyncConflictChoice.CONFIRM_LOCAL_DELETE -> "Excluir mesmo assim"
}

private fun resolutionProblemText(problem: SyncResolutionProblem): String = when (problem) {
    SyncResolutionProblem.NO_REMOTE_COPY ->
        "A versão da nuvem ainda não chegou a este aparelho. Sincronize e tente de novo."
    SyncResolutionProblem.STILL_REFERENCED ->
        "Outro treino ainda usa este item. Remova a referência antes de excluí-lo."
    SyncResolutionProblem.PENDING_CHILD_CHANGES ->
        "Há alterações não enviadas dentro deste item. Sincronize antes de excluí-lo."
    SyncResolutionProblem.ACCOUNT_MISMATCH ->
        "A conta conectada mudou. Nada foi alterado."
    SyncResolutionProblem.REMOTE_CHANGED ->
        "A versão na nuvem mudou enquanto você decidia. Atualizamos as informações. Revise " +
            "novamente antes de escolher."
    SyncResolutionProblem.REMOTE_UNAVAILABLE ->
        "Não foi possível confirmar a versão atual da nuvem. Sua versão local não foi alterada. " +
            "Tente novamente quando houver conexão."
    SyncResolutionProblem.REMOTE_INCONSISTENT ->
        "A versão da nuvem não confere com o que este aparelho tinha guardado. Nada foi " +
            "alterado. Sincronize e tente de novo."
    SyncResolutionProblem.FAILED ->
        "Não foi possível aplicar a escolha agora. Nada foi alterado."
}

/** Descrição de acessibilidade da seção — também o gancho dos testes de UI. */
const val SYNC_SECTION_DESCRIPTION = "Sincronização"

/** Descrição de acessibilidade de um item em conflito. */
const val CONFLICT_ITEM_DESCRIPTION = "Item em conflito"
