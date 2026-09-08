package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

const val NOTIFICATION_PREFERENCES_TITLE = "Notificações sociais"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPreferencesScreen(
    viewModel: NotificationPreferencesViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = NOTIFICATION_PREFERENCES_TITLE,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDark
                )
            )
        },
        containerColor = BackgroundDark
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is NotificationPreferencesUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Lime400)
                    }
                }
                is NotificationPreferencesUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            color = TextSecondary,
                            fontSize = 15.sp
                        )
                    }
                }
                is NotificationPreferencesUiState.Loaded -> {
                    val prefs = state.preferences
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        if (state.errorMessage != null) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = SurfaceDark,
                                border = BorderStroke(1.dp, BorderLight)
                            ) {
                                Text(
                                    text = state.errorMessage,
                                    color = TextSecondary,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        // Switch Mestre
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = SurfaceDark,
                            border = BorderStroke(1.dp, BorderLight)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Notificações push",
                                        color = TextPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Receber notificações neste aparelho sobre atividades sociais",
                                        color = TextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                                Switch(
                                    checked = prefs.pushEnabled,
                                    onCheckedChange = { viewModel.togglePushEnabled(it) },
                                    enabled = !state.isUpdating,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Lime400,
                                        checkedTrackColor = Lime400.copy(alpha = 0.5f)
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "CATEGORIAS",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = SurfaceDark,
                            border = BorderStroke(1.dp, BorderLight)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                PreferenceItem(
                                    title = "Solicitações de amizade",
                                    description = "Quando alguém enviar um convite de amizade",
                                    checked = prefs.friendRequestReceived,
                                    enabled = prefs.pushEnabled && !state.isUpdating,
                                    onCheckedChange = { viewModel.toggleFriendRequestReceived(it) }
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                PreferenceItem(
                                    title = "Aceites de amizade",
                                    description = "Quando sua solicitação for aceita",
                                    checked = prefs.friendRequestAccepted,
                                    enabled = prefs.pushEnabled && !state.isUpdating,
                                    onCheckedChange = { viewModel.toggleFriendRequestAccepted(it) }
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                PreferenceItem(
                                    title = "Convites para desafios",
                                    description = "Quando você for convidado para um novo desafio",
                                    checked = prefs.challengeInvitationReceived,
                                    enabled = prefs.pushEnabled && !state.isUpdating,
                                    onCheckedChange = { viewModel.toggleChallengeInvitationReceived(it) }
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                PreferenceItem(
                                    title = "Início de desafios",
                                    description = "Aviso antes do início de um desafio participante",
                                    checked = prefs.challengeStartingSoon,
                                    enabled = prefs.pushEnabled && !state.isUpdating,
                                    onCheckedChange = { viewModel.toggleChallengeStartingSoon(it) }
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                PreferenceItem(
                                    title = "Encerramento de desafios",
                                    description = "Avisos de resultado e placar final de desafios",
                                    checked = prefs.challengeEnded,
                                    enabled = prefs.pushEnabled && !state.isUpdating,
                                    onCheckedChange = { viewModel.toggleChallengeEnded(it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferenceItem(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) TextPrimary else TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Lime400,
                checkedTrackColor = Lime400.copy(alpha = 0.5f)
            )
        )
    }
}
