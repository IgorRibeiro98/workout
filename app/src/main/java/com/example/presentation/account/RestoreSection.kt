package com.example.presentation.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.restore.RestoreCounts
import com.example.data.restore.RestoreError
import com.example.data.restore.RestoreWarning
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
 * Restaurar um backup, dentro da Conta Spark, no Perfil (T16.5).
 *
 * ## O que esta tela pode e não pode dizer
 *
 * Ela **não** diz "sincronizar", "unir os dados" nem "trazer o que falta": restore é substituição.
 * Antes de qualquer escrita, ela mostra o que virá, o que este aparelho tem hoje e o que será
 * descartado — e o botão que faz isso não tem aparência de botão inofensivo.
 *
 * ## Nada acontece sozinho
 *
 * Abrir o Perfil não lista backups. Listar não baixa. Baixar não restaura. Restaurar exige
 * confirmação — duas, quando há dado local a perder.
 *
 * ## Erro é frase, não código
 *
 * `SQLiteConstraintException`, `JsonDecodingException` e "HTTP 410" não aparecem aqui. Cada classe
 * de erro vira uma frase que diz o que houve e o que fazer.
 */
@Composable
fun RestoreSection(
    uiState: RestoreUiState,
    onLoadBackups: () -> Unit,
    onSelectBackup: (String) -> Unit,
    onRequestRestore: () -> Unit,
    onConfirmReplacement: () -> Unit,
    onCancelReplacement: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (uiState.phase is RestorePhaseUi.NotConfigured) return
    if (uiState.phase is RestorePhaseUi.NotAuthenticated) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Restaurar backup",
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
                .semantics { contentDescription = RESTORE_SECTION_DESCRIPTION }
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (val phase = uiState.phase) {
                    RestorePhaseUi.NotConfigured, RestorePhaseUi.NotAuthenticated -> Unit

                    is RestorePhaseUi.Idle -> {
                        Explanation(
                            title = "Trazer dados de um backup",
                            body = "Você pode substituir os dados deste aparelho por uma cópia " +
                                "salva na sua Conta Spark. Nada é alterado antes de você confirmar."
                        )
                        phase.lastRestoredAt?.let {
                            Text(
                                text = "Última restauração neste aparelho: ${formatTimestamp(it)}",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        PrimaryAction(text = "Ver backups", onClick = onLoadBackups)
                    }

                    RestorePhaseUi.LoadingBackups -> Busy("Buscando seus backups...")

                    is RestorePhaseUi.ChoosingBackup -> {
                        if (phase.backups.isEmpty()) {
                            Explanation(
                                title = "Nenhum backup ainda",
                                body = "Esta Conta Spark ainda não tem cópias salvas. Faça um " +
                                    "backup neste ou em outro aparelho para poder restaurar."
                            )
                            SecondaryAction(text = "Voltar", onClick = onCancel)
                        } else {
                            Text(
                                text = "Escolha qual cópia restaurar",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            // A lista pode crescer com a retenção do servidor; ela rola dentro do
                            // próprio cartão em vez de esticar o Perfil inteiro.
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 220.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(phase.backups, key = { it.backupId }) { backup ->
                                    BackupRow(backup = backup, onClick = { onSelectBackup(backup.backupId) })
                                }
                            }
                            SecondaryAction(text = "Cancelar", onClick = onCancel)
                        }
                    }

                    RestorePhaseUi.Preparing -> Busy("Baixando e conferindo o backup...")

                    is RestorePhaseUi.Preview -> PreviewCard(
                        preview = phase.preview,
                        onRestore = onRequestRestore,
                        onCancel = onCancel
                    )

                    RestorePhaseUi.Applying -> Busy("Restaurando...")

                    is RestorePhaseUi.Completed -> {
                        Text(
                            text = "Backup restaurado",
                            color = Lime400,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        CountLines(phase.counts)
                        SecondaryAction(text = "Concluir", onClick = onCancel)
                    }

                    RestorePhaseUi.RecoveryPending -> Warning(
                        title = "Restauração não concluída",
                        body = "Uma restauração começou e não terminou. Feche e abra o Spark: ele " +
                            "conclui ou desfaz automaticamente antes de qualquer outra alteração."
                    )

                    RestorePhaseUi.AccountMismatch -> Warning(
                        title = "Dados de outra Conta Spark",
                        // Nada da conta dona é exposto: nem e-mail, nem nome, nem uid.
                        body = "Os dados deste aparelho pertencem a outra Conta Spark. Restaurar " +
                            "aqui com a conta atual não é possível. Seus treinos e seu histórico " +
                            "continuam funcionando normalmente."
                    )

                    is RestorePhaseUi.Failed -> {
                        Warning(title = "Não foi possível restaurar", body = failureText(phase.error))
                        SecondaryAction(text = "Voltar", onClick = onCancel)
                    }
                }
            }
        }
    }

    if (uiState.isConfirmingReplacement) {
        ReplacementConfirmation(
            preview = (uiState.phase as? RestorePhaseUi.Preview)?.preview,
            onConfirm = onConfirmReplacement,
            onCancel = onCancelReplacement
        )
    }
}

@Composable
private fun BackupRow(backup: RestoreBackupItem, onClick: () -> Unit) {
    Surface(
        color = SurfaceHighlight,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = formatTimestamp(backup.createdAt),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            // Detalhe técnico (hash, bytes) não aparece: ele não ajuda ninguém a escolher.
            Text(
                text = "${backup.itemCount} itens",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * O preview: o que vem, o que sai e o que fica.
 *
 * As contagens são do snapshot já validado. Os avisos existem porque o usuário precisa decidir com
 * as consequências à vista — em especial quando o backup é mais antigo que o histórico daqui.
 */
@Composable
private fun PreviewCard(
    preview: RestorePreviewUi,
    onRestore: () -> Unit,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Restaurar backup?",
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Text(text = formatTimestamp(preview.backupCreatedAt), color = Lime400, fontSize = 13.sp)

        CountLines(preview.counts)

        preview.warnings.forEach { warning ->
            Text(text = warningText(warning), color = TextSecondary, fontSize = 12.sp)
        }

        if (preview.replacesLocalData) {
            Text(
                text = "Este backup substituirá os dados deste aparelho.",
                color = Red400,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        PrimaryAction(text = "Restaurar", onClick = onRestore)
        SecondaryAction(text = "Cancelar", onClick = onCancel)
    }
}

/**
 * A segunda confirmação, quando há dado local a perder.
 *
 * Não exige digitar palavra nenhuma — e também não deixa a substituição caber em um toque distraído
 * no meio de uma lista.
 */
@Composable
private fun ReplacementConfirmation(
    preview: RestorePreviewUi?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = SurfaceDark,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text(text = "Substituir os dados deste aparelho?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Os treinos, o histórico e as medidas que estão neste aparelho serão " +
                        "substituídos pelo conteúdo do backup. Isso não pode ser desfeito pelo app.",
                    fontSize = 13.sp
                )
                preview?.warnings
                    ?.filterIsInstance<RestoreWarning.OlderThanLocalHistory>()
                    ?.forEach { warning ->
                        Text(
                            text = "Este backup é de ${formatTimestamp(warning.backupCreatedAt)}. " +
                                "${warning.localSessionsAfterBackup} treino(s) concluído(s) depois " +
                                "dessa data deixarão de existir aqui.",
                            color = Red400,
                            fontSize = 13.sp
                        )
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "Substituir e restaurar", color = Red400, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = "Cancelar", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun CountLines(counts: RestoreCounts) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CountLine("Treinos", counts.templates)
        CountLine("Programas", counts.programs)
        CountLine("Treinos concluídos", counts.completedSessions)
        CountLine("Exercícios personalizados", counts.customExercises)
        CountLine("Medidas corporais", counts.bodyMeasurements)
        CountLine("Check-ins", counts.checkIns)
        CountLine("Customizações de exercício", counts.exerciseOverrides)
        CountLine("Metas semanais", counts.weeklyGoals)
    }
}

@Composable
private fun CountLine(label: String, count: Int) {
    Text(text = "$count $label", color = TextPrimary, fontSize = 13.sp)
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

@Composable
private fun Busy(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Sem porcentagem: não há métrica real de progresso aqui, e inventar uma seria pior do que
        // dizer apenas o que está acontecendo.
        CircularProgressIndicator(color = Lime400, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Text(text = text, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun PrimaryAction(text: String, onClick: () -> Unit) {
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
        Text(text = text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text = text, color = TextSecondary, fontSize = 14.sp)
    }
}

/** Cada aviso vira uma frase — nenhuma delas repete conteúdo do backup. */
private fun warningText(warning: RestoreWarning): String = when (warning) {
    is RestoreWarning.ReplacesLocalData ->
        "Este aparelho tem ${warning.localItems} itens que serão substituídos."

    is RestoreWarning.OlderThanLocalHistory ->
        "Este backup é de ${formatTimestamp(warning.backupCreatedAt)}. " +
            "${warning.localSessionsAfterBackup} treino(s) concluído(s) depois dessa data " +
            "deixarão de existir aqui."

    is RestoreWarning.PendingChangesDiscarded ->
        "Há ${warning.pendingMutations} alteração(ões) feita(s) aqui que ainda não estão em " +
            "nenhum backup. Elas serão descartadas."

    RestoreWarning.GamificationRecalculated ->
        "XP, conquistas e recordes são recalculados a partir do histórico restaurado."

    RestoreWarning.MediaStaysLocal ->
        "Fotos personalizadas de exercício continuam somente neste aparelho."
}

private fun failureText(error: RestoreError): String = when (error) {
    RestoreError.NETWORK ->
        "Sem conexão para baixar este backup agora. Nada foi alterado neste aparelho."

    RestoreError.UNAVAILABLE ->
        "O servidor do Spark está indisponível. Nada foi alterado neste aparelho."

    RestoreError.BACKUP_DOWNLOAD_FAILED ->
        "Não foi possível baixar este backup. Nada foi alterado neste aparelho."

    RestoreError.BACKUP_NOT_FOUND ->
        "Este backup não está mais disponível na sua Conta Spark."

    RestoreError.BACKUP_CONTENT_UNAVAILABLE ->
        "Este backup foi criado por uma versão anterior do Spark e não pode ser restaurado. " +
            "Faça um backup novo para poder restaurá-lo em outro aparelho."

    RestoreError.BACKUP_INTEGRITY_ERROR ->
        "O arquivo recebido não passou na verificação de integridade. Nada foi alterado neste " +
            "aparelho — você pode tentar de novo."

    RestoreError.UNSUPPORTED_BACKUP_VERSION ->
        "Este backup foi criado por uma versão mais nova do Spark. Atualize o app para restaurá-lo."

    RestoreError.UNSUPPORTED_ENTITY_VERSION ->
        "Este backup contém dados que esta versão do Spark não sabe ler. Atualize o app."

    RestoreError.INVALID_BACKUP ->
        "Este backup não pôde ser lido por completo e não foi aplicado. Nada foi alterado neste " +
            "aparelho."

    RestoreError.MISSING_CATALOG_EXERCISE ->
        "Este backup usa exercícios que este aparelho ainda não tem. Atualize o app e tente de novo."

    RestoreError.BACKUP_TOO_LARGE ->
        "Este backup é maior do que o Spark consegue restaurar neste aparelho."

    RestoreError.WORKOUT_IN_PROGRESS ->
        "Há um treino em andamento. Finalize ou cancele o treino antes de restaurar um backup."

    RestoreError.ACCOUNT_CHANGED ->
        "A conta mudou durante a restauração, e ela foi cancelada. Nada foi alterado."

    RestoreError.AUTH_REQUIRED ->
        "Entre na Conta Spark novamente para restaurar um backup."

    RestoreError.ACCOUNT_MISMATCH ->
        "Os dados deste aparelho pertencem a outra Conta Spark."

    RestoreError.RESTORE_IN_PROGRESS ->
        "Já existe uma operação de nuvem em andamento neste aparelho."

    RestoreError.RESTORE_CONFIRMATION_REQUIRED ->
        "A restauração precisa ser confirmada por você."

    RestoreError.RESTORE_APPLY_FAILED ->
        "A restauração não foi concluída e os dados deste aparelho foram preservados."

    RestoreError.RESTORE_RECOVERY_REQUIRED ->
        "Uma restauração anterior ficou pela metade. Feche e abra o Spark para resolvê-la."

    RestoreError.NOT_CONFIGURED ->
        "Este aparelho não está configurado para falar com o servidor do Spark."
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(epochMillis))

/** Descrição de acessibilidade da seção — também o gancho dos testes de UI. */
const val RESTORE_SECTION_DESCRIPTION = "Restaurar backup"
