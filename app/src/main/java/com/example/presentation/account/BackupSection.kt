package com.example.presentation.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.example.data.backup.BackupSummary
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
 * O backup na nuvem, dentro da Conta Spark, no Perfil (T16.4).
 *
 * ## O que este texto pode e não pode dizer
 *
 * Ele **não** diz "seus dados estão totalmente protegidos" nem "todos os seus dispositivos estão
 * sincronizados": nenhuma das duas é verdade. Não há restore, não há download, não há sync e não
 * há convergência entre aparelhos — a T16.4 protege contra a perda **do aparelho**, e é isso que
 * a tela afirma.
 *
 * Ele também informa o que fica de fora: fotos personalizadas de exercício são arquivos deste
 * celular, não sobem, e o usuário precisa saber disso antes de confiar no backup.
 */
@Composable
fun BackupSection(
    uiState: BackupUiState,
    onActivate: () -> Unit,
    onConfirmAdoption: () -> Unit,
    onCancelAdoption: () -> Unit,
    onBackupNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (uiState.phase is BackupPhase.NotConfigured) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Backup na nuvem",
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
                .semantics { contentDescription = BACKUP_SECTION_DESCRIPTION }
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (val phase = uiState.phase) {
                    BackupPhase.NotConfigured -> Unit

                    BackupPhase.NotAuthenticated -> Explanation(
                        title = "Proteja seus dados",
                        body = "Entre na Conta Spark acima para guardar uma cópia dos seus treinos " +
                            "neste servidor."
                    )

                    BackupPhase.Unbound -> {
                        Explanation(
                            title = "Seus dados estão somente neste aparelho",
                            body = "Ao ativar, os dados atuais passam a ser associados à sua " +
                                "Conta Spark e uma cópia é enviada ao servidor."
                        )
                        PrimaryAction(text = "Ativar backup", onClick = onActivate)
                    }

                    BackupPhase.PreparingSnapshot -> Busy("Criando backup...")

                    BackupPhase.Uploading -> Busy("Enviando backup...")

                    is BackupPhase.Ready -> {
                        LastBackup(phase)
                        if (phase.hasPendingAttempt) {
                            Text(
                                text = "Há um backup começado que ainda não foi confirmado pelo " +
                                    "servidor. Tocar em enviar retoma a mesma cópia.",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        PrimaryAction(text = "Fazer backup agora", onClick = onBackupNow)
                        MediaNotice()
                    }

                    BackupPhase.AccountMismatch -> Warning(
                        title = "Dados de outra Conta Spark",
                        // Nada da conta dona é exposto: nem e-mail, nem nome, nem uid.
                        body = "Os dados deste aparelho estão associados a outra Conta Spark. O " +
                            "backup fica indisponível para a conta atual. Seus treinos, seu " +
                            "histórico e a execução continuam funcionando normalmente."
                    )

                    is BackupPhase.Failed -> {
                        Warning(title = "Backup não concluído", body = failureText(phase.reason))
                        // A cópia começada continua guardada: tentar de novo reenvia a mesma.
                        PrimaryAction(text = "Tentar novamente", onClick = onBackupNow)
                    }
                }
            }
        }
    }

    if (uiState.isConfirmingAdoption) {
        AdoptionConfirmation(
            summary = uiState.summary,
            accountEmail = uiState.accountEmail,
            onConfirm = onConfirmAdoption,
            onCancel = onCancelAdoption
        )
    }
}

/**
 * A confirmação explícita da adoção.
 *
 * Ela existe para que ninguém associe o histórico do aparelho a uma conta sem ver o que está
 * associando. Mostra **quanto**, e não **o quê**: contagens, nunca o conteúdo dos treinos.
 */
@Composable
private fun AdoptionConfirmation(
    summary: BackupSummary?,
    accountEmail: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = SurfaceDark,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text(text = "Associar dados à Conta Spark?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (accountEmail != null) {
                        "Os dados atuais deste aparelho serão associados à conta $accountEmail. " +
                            "Depois disso, backups desses dados pertencem a ela."
                    } else {
                        "Os dados atuais deste aparelho serão associados à sua Conta Spark. " +
                            "Depois disso, backups desses dados pertencem a ela."
                    },
                    fontSize = 13.sp
                )

                if (summary != null) {
                    SummaryLine("Treinos", summary.templates)
                    SummaryLine("Programas", summary.programs)
                    SummaryLine("Treinos concluídos", summary.completedSessions)
                    SummaryLine("Exercícios personalizados", summary.customExercises)
                    SummaryLine("Medidas corporais", summary.bodyMeasurements)
                    SummaryLine("Check-ins", summary.checkIns)
                    SummaryLine("Customizações de exercício", summary.exerciseOverrides)
                    SummaryLine("Metas semanais", summary.weeklyGoals)
                }

                Text(
                    text = "Fotos personalizadas de exercício continuam somente neste aparelho.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "Associar e criar backup", color = Lime400, fontWeight = FontWeight.Bold)
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
private fun SummaryLine(label: String, count: Int) {
    Text(text = "$count $label".lowercase(Locale.getDefault()).replaceFirstChar { it.uppercase() },
        color = TextPrimary, fontSize = 13.sp)
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
 * O último backup, com a hora que o **servidor** registrou.
 *
 * Não "quando eu cliquei no botão": se o upload demorou, ou se a resposta só chegou depois, o
 * relógio do aparelho contaria uma história que o servidor não confirma.
 */
@Composable
private fun LastBackup(phase: BackupPhase.Ready) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val at = phase.lastBackupAt
        if (at == null) {
            Text(
                text = "Backup ativado. Nenhuma cópia foi enviada ainda.",
                color = TextSecondary,
                fontSize = 13.sp
            )
        } else {
            Text(
                text = "Último backup salvo no Spark",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(text = formatTimestamp(at), color = Lime400, fontSize = 13.sp)
            val items = phase.lastBackupItemCount
            if (items != null) {
                Text(text = "$items itens enviados", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MediaNotice() {
    Text(
        text = "Fotos personalizadas de exercício continuam somente neste aparelho.",
        color = TextSecondary,
        fontSize = 12.sp
    )
}

@Composable
private fun Busy(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Sem porcentagem: não existe métrica real de bytes aqui, e inventar uma seria pior do
        // que dizer apenas o que está acontecendo.
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

private fun failureText(reason: BackupFailure): String = when (reason) {
    BackupFailure.NETWORK ->
        "Sem conexão para enviar agora. A cópia foi guardada e você pode tentar de novo."
    BackupFailure.UNAVAILABLE ->
        "O servidor do Spark está indisponível. Seus treinos continuam normais aqui."
    BackupFailure.AUTH_REQUIRED ->
        "Entre na Conta Spark novamente para enviar este backup."
    BackupFailure.REJECTED ->
        "O servidor recusou esta cópia. Nada foi alterado neste aparelho."
    BackupFailure.SNAPSHOT ->
        "Não foi possível preparar a cópia a partir dos dados deste aparelho."
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(epochMillis))

/** Descrição de acessibilidade da seção — também o gancho dos testes de UI. */
const val BACKUP_SECTION_DESCRIPTION = "Backup na nuvem"
