package com.example.presentation.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import com.example.data.ai.FirebaseAiCoachGateway
import com.example.domain.ai.AiModelConfig
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Orange400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * Prova de integração do Coach IA: um botão explícito, um resumo e as sugestões.
 *
 * A tela não chama o provider ao abrir, ao girar ou ao recompor — só o toque em
 * "Analisar meu treino" fala com o modelo. Nada aqui aplica sugestão: o que aparece é conselho.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCoachScreen(
    viewModel: AiCoachViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    AiCoachScreenContent(
        uiState = uiState,
        onAnalyze = viewModel::analyze,
        onNavigateBack = onNavigateBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiCoachScreenContent(
    uiState: AiCoachUiState,
    onAnalyze: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Coach IA",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "O Coach analisa o que você já treinou e sugere ajustes. " +
                    "Ele nunca altera seus treinos sozinho.",
                color = TextSecondary,
                fontSize = 13.sp
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderLight, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "Modelo ativo:",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Text(
                    text = AiModelConfig.MODEL_NAME,
                    color = Lime400,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }

            Button(
                onClick = onAnalyze,
                enabled = uiState !is AiCoachUiState.Loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime400,
                    contentColor = BackgroundDark,
                    disabledContainerColor = SurfaceDark,
                    disabledContentColor = TextTertiary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "  Analisar meu treino",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            when (uiState) {
                AiCoachUiState.Idle -> Unit

                AiCoachUiState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Lime400, strokeWidth = 3.dp)
                }

                is AiCoachUiState.Success -> AdviceSection(uiState)

                is AiCoachUiState.Unavailable -> MessageCard(
                    message = uiState.message,
                    accent = Orange400,
                    isWarning = true,
                    onRetry = onAnalyze
                )

                is AiCoachUiState.Error -> MessageCard(
                    message = if (uiState.canRetry) {
                        "${uiState.message} Você pode tentar novamente."
                    } else {
                        uiState.message
                    },
                    accent = Red400,
                    isWarning = false,
                    onRetry = onAnalyze
                )
            }
        }
    }
}

@Composable
private fun AdviceSection(state: AiCoachUiState.Success) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Resumo")
        Card {
            Text(text = state.summary, color = TextPrimary, fontSize = 14.sp)
        }

        if (state.recommendations.isNotEmpty()) {
            SectionTitle("Recomendações")
            state.recommendations.forEach { recommendation ->
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = recommendation.label,
                            color = Lime400,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        recommendation.exerciseName?.let { name ->
                            Text(text = name, color = TextSecondary, fontSize = 12.sp)
                        }
                        Text(text = recommendation.reason, color = TextPrimary, fontSize = 14.sp)
                        Text(
                            text = "Confiança: ${recommendation.confidencePercent}%",
                            color = TextTertiary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderLight, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun MessageCard(
    message: String,
    accent: Color,
    isWarning: Boolean,
    onRetry: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isAppCheckError = message.contains("App Check", ignoreCase = true) || message.contains("token is invalid", ignoreCase = true)

    var currentToken by remember {
        mutableStateOf(FirebaseAiCoachGateway.getCurrentDebugToken(context))
    }
    var inputToken by remember { mutableStateOf("") }
    var tokenSavedSuccess by remember { mutableStateOf(false) }

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = if (isWarning) Icons.Default.CloudOff else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Text(text = message, color = TextSecondary, fontSize = 13.sp)
            }

            if (isAppCheckError) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundDark)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Token de depuração ativo no app:",
                        color = Lime400,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = currentToken,
                        color = TextPrimary,
                        fontSize = 11.sp
                    )

                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(currentToken))
                        },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceDark,
                            contentColor = TextPrimary
                        ),
                        modifier = Modifier.border(1.dp, BorderLight, RoundedCornerShape(6.dp))
                    ) {
                        Text(text = "Copiar este Token", fontSize = 11.sp)
                    }

                    Text(
                        text = "Se o Firebase Console gerou outro token, cole aqui:",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    OutlinedTextField(
                        value = inputToken,
                        onValueChange = {
                            inputToken = it
                            tokenSavedSuccess = false
                        },
                        placeholder = {
                            Text(text = "Cole o token do Firebase...", fontSize = 11.sp, color = TextSecondary)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Lime400,
                            unfocusedBorderColor = BorderLight,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            if (inputToken.isNotBlank()) {
                                FirebaseAiCoachGateway.setCustomDebugToken(context, inputToken)
                                currentToken = inputToken.trim()
                                tokenSavedSuccess = true
                                onRetry()
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime400,
                            contentColor = BackgroundDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (tokenSavedSuccess) Icons.Default.Check else Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (tokenSavedSuccess) "  Token Salvo! Reanalisando..." else "  Salvar Token e Reanalisar",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
