package com.example.presentation.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.SocialGroup
import com.example.domain.social.SocialGroupError
import com.example.domain.social.SocialGroupGateway
import com.example.domain.social.SocialGroupOutcome
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * O texto de confirmação (T17.11 §142).
 *
 * As três frases são deliberadas, e cada uma responde a uma pergunta que a pessoa tem antes de
 * tocar: **quem** vai ver, **o que** vai junto, e **o que não** vai. A terceira é a mais
 * importante: sem ela, "compartilhar um treino" soa como publicar o treino, e é exatamente isso que
 * não acontece (§75).
 */
internal fun shareToSquadConfirmation(groupName: String): String =
    "Compartilhar no Squad \"$groupName\"?\n\n" +
        "Os membros poderão ver esta publicação, incluindo foto e legenda, se houver.\n\n" +
        "Seus dados privados de treino continuam ocultos."

data class ShareToSquadUiState(
    val isLoading: Boolean = true,
    /** As participações **ativas** — o seletor só mostra Squads de que o usuário faz parte (§141). */
    val groups: List<SocialGroup> = emptyList(),
    /** Onde este check-in já está. A tela marca, e não oferece de novo. */
    val alreadySharedGroupIds: Set<String> = emptySet(),
    val sharingGroupId: String? = null,
    val notice: String? = null
)

/**
 * O seletor de Squad para compartilhar um check-in (T17.11 §140/§141/§142).
 *
 * ## Por que ele tem estado próprio
 *
 * Porque a pergunta que ele faz — "em quais dos meus Squads este check-in já está?" — não é do
 * detalhe do check-in nem da lista de Squads: é da interseção dos dois. Guardá-la em qualquer um
 * dos dois faria aquela tela buscar Squads que ela não usa, ou check-ins que ela não mostra.
 *
 * ## Nada é automático, e o compartilhamento é por Squad (§52/§53)
 *
 * Cada toque compartilha em **um** grupo, depois de uma confirmação que diz o que vai junto (§142).
 * Não existe "compartilhar em todos", e não existe memória de escolha anterior: cada publicação é
 * uma decisão nova.
 */
class ShareToSquadViewModel(
    private val gateway: SocialGroupGateway,
    private val authGateway: AuthGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShareToSquadUiState())
    val uiState: StateFlow<ShareToSquadUiState> = _uiState.asStateFlow()

    /** Abre o seletor para um check-in **próprio**. O servidor recusa o de qualquer outro (§56). */
    fun open(checkInId: String) {
        val uid = currentUid() ?: return
        _uiState.value = ShareToSquadUiState(isLoading = true)

        viewModelScope.launch {
            val groupsOutcome = gateway.groups()
            if (currentUid() != uid) return@launch
            if (groupsOutcome is SocialGroupOutcome.Failure) {
                _uiState.value = ShareToSquadUiState(
                    isLoading = false,
                    notice = noticeFor(groupsOutcome.error)
                )
                return@launch
            }

            val sharedOutcome = gateway.groupsForCheckIn(checkInId)
            if (currentUid() != uid) return@launch

            _uiState.value = ShareToSquadUiState(
                isLoading = false,
                groups = (groupsOutcome as SocialGroupOutcome.Success).data,
                // Uma falha aqui não impede compartilhar: o pior caso é a tela oferecer um Squad
                // onde o check-in já está, e o servidor devolver o mesmo vínculo (§55).
                alreadySharedGroupIds =
                    (sharedOutcome as? SocialGroupOutcome.Success)?.data?.toSet().orEmpty()
            )
        }
    }

    fun share(checkInId: String, groupId: String) {
        val uid = currentUid() ?: return
        if (_uiState.value.sharingGroupId != null) return

        _uiState.update { it.copy(sharingGroupId = groupId, notice = null) }

        viewModelScope.launch {
            val outcome = gateway.shareCheckIn(groupId, checkInId)
            if (currentUid() != uid) return@launch

            _uiState.update {
                when (outcome) {
                    is SocialGroupOutcome.Success -> it.copy(
                        sharingGroupId = null,
                        alreadySharedGroupIds = it.alreadySharedGroupIds + groupId,
                        notice = "Compartilhado no squad."
                    )

                    is SocialGroupOutcome.Failure -> it.copy(
                        sharingGroupId = null,
                        notice = noticeFor(outcome.error)
                    )
                }
            }
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun currentUid(): String? =
        (authGateway.state.value as? AuthState.SignedIn)?.account?.uid

    private fun noticeFor(error: SocialGroupError): String = when (error) {
        SocialGroupError.NETWORK -> "Sem conexão. Nada foi compartilhado."
        SocialGroupError.SHARE_LIMIT_REACHED ->
            "Este check-in já está no máximo de squads permitido."
        SocialGroupError.GROUP_NOT_FOUND -> "Este squad não está mais disponível."
        SocialGroupError.CHECKIN_NOT_FOUND -> "Esta publicação não está mais disponível."
        SocialGroupError.RATE_LIMITED -> "Muitas ações seguidas. Tente em instantes."
        SocialGroupError.SOCIAL_NOT_ENABLED -> "Ative seu perfil social para usar os Squads."
        SocialGroupError.NOT_CONFIGURED -> "Os Squads não estão disponíveis nesta versão."
        else -> "Não foi possível compartilhar agora."
    }
}

/**
 * O diálogo em duas etapas: escolher o Squad, e confirmar (§141/§142).
 *
 * A confirmação não é cerimônia: compartilhar torna uma foto e uma legenda visíveis para até vinte
 * pessoas, e é a última tela em que a pessoa ainda pode desistir sem ter de desfazer.
 */
@Composable
fun ShareToSquadDialog(
    viewModel: ShareToSquadViewModel,
    checkInId: String,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var confirming by remember { mutableStateOf<SocialGroup?>(null) }

    LaunchedEffect(checkInId) { viewModel.open(checkInId) }

    val pending = confirming
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirming = null },
            containerColor = SurfaceDark,
            text = {
                Text(text = shareToSquadConfirmation(pending.name), color = TextPrimary)
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    viewModel.share(checkInId, pending.groupId)
                }) {
                    Text(text = "Compartilhar", color = Lime400, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(text = "Cancelar", color = TextSecondary)
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text(text = "Compartilhar no Squad", color = TextPrimary) },
        text = {
            when {
                uiState.isLoading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = Lime400, modifier = Modifier.size(20.dp))
                    Text(text = "Carregando seus squads…", color = TextSecondary)
                }

                uiState.groups.isEmpty() -> Text(
                    text = "Você ainda não participa de nenhum squad.",
                    color = TextSecondary
                )

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(uiState.groups, key = { it.groupId }) { group ->
                        val already = group.groupId in uiState.alreadySharedGroupIds
                        TextButton(
                            onClick = { confirming = group },
                            enabled = !already && uiState.sharingGroupId == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = group.name,
                                    color = if (already) TextSecondary else TextPrimary
                                )
                                if (already) {
                                    Text(
                                        text = "Já compartilhado",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "Fechar", color = Lime400) }
        }
    )

    uiState.notice?.let { notice ->
        SquadNoticeDialog(message = notice, onDismiss = viewModel::dismissNotice)
    }
}
