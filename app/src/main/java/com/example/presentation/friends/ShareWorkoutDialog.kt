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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.TemplateExerciseWithDetails
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.repository.SnapshotBuildResult
import com.example.data.repository.WorkoutShareSnapshotBuilder
import com.example.domain.social.Friend
import com.example.domain.social.FriendGateway
import com.example.domain.social.FriendOutcome
import com.example.domain.social.WorkoutShareGateway
import com.example.domain.social.WorkoutShareOutcome
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ShareWorkoutDialog(
    template: WorkoutTemplateEntity,
    exercises: List<TemplateExerciseWithDetails>,
    friendGateway: FriendGateway,
    shareGateway: WorkoutShareGateway,
    onDismiss: () -> Unit,
    onShareSuccess: () -> Unit
) {
    val buildResult = remember(template, exercises) {
        WorkoutShareSnapshotBuilder().buildSnapshot(template, exercises)
    }

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
                        friendGateway = friendGateway,
                        shareGateway = shareGateway,
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
    friendGateway: FriendGateway,
    shareGateway: WorkoutShareGateway,
    onDismiss: () -> Unit,
    onShareSuccess: () -> Unit
) {
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var isLoadingFriends by remember { mutableStateOf(true) }
    var selectedFriend by remember { mutableStateOf<Friend?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        when (val outcome = friendGateway.friends()) {
            is FriendOutcome.Success -> {
                friends = outcome.value.items
                isLoadingFriends = false
            }
            is FriendOutcome.Failure -> {
                isLoadingFriends = false
            }
        }
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
        } else if (friends.isEmpty()) {
            Text(
                text = "Você ainda não possui amigos adicionados.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(friends, key = { it.socialId }) { friend ->
                    val isSelected = selectedFriend?.socialId == friend.socialId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedFriend = friend },
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
                text = errorMessage!!,
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
                onClick = {
                    val target = selectedFriend ?: return@Button
                    isSending = true
                    errorMessage = null
                    scope.launch {
                        val outcome = shareGateway.createShare(
                            recipientSocialId = target.socialId,
                            clientRequestId = UUID.randomUUID().toString(),
                            snapshot = snapshot
                        )
                        isSending = false
                        when (outcome) {
                            is WorkoutShareOutcome.Success -> {
                                onShareSuccess()
                            }
                            is WorkoutShareOutcome.Failure -> {
                                errorMessage = "Falha ao enviar treino. Tente novamente."
                            }
                        }
                    }
                },
                enabled = selectedFriend != null && !isSending,
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
