package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.repository.SnapshotBuildResult
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * O diálogo de compartilhar treino (T17.7).
 *
 * Ele recebe o snapshot **já construído**, e não o `WorkoutTemplateEntity` com a lista de
 * exercícios (T17.10 §105). A diferença não é estética: a fronteira do social é não conhecer Room,
 * DAO nem entidade local, e uma tela social que segura a entidade de treino é o ponto exato em que
 * um campo privado — carga, nota, número de máquina — passa a estar ao alcance de quem for
 * escrever a próxima linha aqui. Quem monta o snapshot é o `WorkoutShareSnapshotBuilder`, chamado
 * de onde a entidade legitimamente mora (`presentation/workouts`), e é lá que a política de
 * exercício CUSTOM é aplicada fail-closed.
 *
 * O que chega aqui é o que vai para o servidor, e nada mais.
 *
 * Listar amigos e enviar o treino correm no `viewModelScope` (T17.10): quando isso vivia num
 * `LaunchedEffect`/`rememberCoroutineScope`, fechar o diálogo cancelava a requisição em voo.
 */
@Composable
fun ShareWorkoutDialog(
    buildResult: SnapshotBuildResult,
    viewModel: ShareWorkoutViewModel,
    onDismiss: () -> Unit,
    onShareSuccess: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceDark,
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            when (buildResult) {
                is SnapshotBuildResult.Blocked -> {
                    ShareBlockedContent(
                        reasons = buildResult.reasons,
                        onDismiss = onDismiss
                    )
                }
                is SnapshotBuildResult.Success -> {
                    ShareWorkoutContent(
                        snapshot = buildResult.snapshot,
                        viewModel = viewModel,
                        onDismiss = onDismiss,
                        onShareSuccess = onShareSuccess
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareBlockedContent(
    reasons: List<String>,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Red400,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Não é possível compartilhar",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        reasons.forEach { reason ->
            Text(
                text = reason,
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, BorderLight),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Entendi", color = TextPrimary)
        }
    }
}

@Composable
private fun ShareWorkoutContent(
    snapshot: com.example.domain.social.SharedWorkoutSnapshot,
    viewModel: ShareWorkoutViewModel,
    onDismiss: () -> Unit,
    onShareSuccess: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val friends = state.friends
    val isLoadingFriends = state.isLoadingFriends
    val isSending = state.isSending
    val errorMessage = state.errorMessage

    LaunchedEffect(viewModel) { viewModel.loadFriends() }

    // A confirmação vem do estado, e não da corrotina que enviou: assim ela chega mesmo que o
    // envio termine depois de a tela recompor.
    LaunchedEffect(state.isSent) {
        if (state.isSent) onShareSuccess()
    }

    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = "Compartilhar Treino",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${snapshot.name} (${snapshot.exercises.size} exercícios)",
            color = TextSecondary,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Card informativo de privacidade
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF1E2638),
            border = BorderStroke(1.dp, Color(0xFF2C3E60)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFF90CAF9),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Apenas exercícios do catálogo, séries, repetições e descansos serão compartilhados. " +
                        "Suas cargas, notas e máquinas NÃO são enviadas.",
                    color = Color(0xFFE3F2FD),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Escolha o amigo:",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (isLoadingFriends) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Lime400)
            }
        } else if (friends.isEmpty() && errorMessage == null) {
            // Só dizemos "você não tem amigos" quando a leitura **deu certo** e voltou vazia. Com
            // falha, o texto de erro logo abaixo é o que aparece — afirmar o contrário seria mentir
            // sobre a conta de quem está sem internet.
            Text(
                text = "Você ainda não possui amigos adicionados.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else if (friends.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(friends, key = { it.socialId }) { friend ->
                    val isSelected = state.selectedFriendId == friend.socialId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectFriend(friend.socialId) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF1E3A2B) else SurfaceDark
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) Lime400 else BorderLight
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = if (isSelected) Lime400 else TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = friend.displayName,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Lime400,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                color = Red400,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = BorderLight)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onDismiss,
                enabled = !isSending
            ) {
                Text("Cancelar", color = TextSecondary)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { viewModel.share(snapshot) },
                enabled = state.selectedFriendId != null && !isSending,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime400,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Enviar Treino", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
