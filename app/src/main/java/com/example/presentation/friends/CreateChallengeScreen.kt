package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.example.data.social.ChallengeContract
import com.example.domain.social.ChallengeDraft
import com.example.domain.social.ChallengeType
import com.example.domain.social.Friend
import com.example.presentation.account.ChallengeCreationPhase
import com.example.presentation.account.ChallengeUiState
import com.example.presentation.account.ChallengeViewModel
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import androidx.lifecycle.compose.collectAsStateWithLifecycle

const val CREATE_CHALLENGE_DESCRIPTION = "Criar desafio"
const val CREATE_CHALLENGE_TITLE = "Novo desafio"

/**
 * A criação de um desafio (T17.3 §159–§167).
 *
 * Uma tela só, e não um assistente de seis passos: as escolhas são cinco campos curtos, e um
 * wizard obrigaria a percorrer telas para conferir o que já foi decidido — que é exatamente o que
 * a revisão no rodapé resolve de graça.
 *
 * ```text
 * Tipo      ( Treinos realizados ) ( Dias ativos )
 * Nome      [ 12 treinos            ]
 * Meta      [ 12 ]
 * Período   [ 2026-09-10 ] → [ 2026-10-09 ]
 * Amigos    ☑ João   ☑ Jonathas
 *
 * Revisão: 12 treinos · 10/09 — 09/10 · você + 2
 * [ Criar desafio ]
 * ```
 *
 * ## O rascunho vive em memória
 *
 * Nada é persistido, e perder o rascunho ao fechar o app é aceitável: nenhum desafio existe antes
 * de o servidor confirmar. O que **não** seria aceitável é um desafio meio criado sobrevivendo a
 * um crash — e é por isso que a criação é uma transação do outro lado.
 *
 * ## A validação daqui não é a autoridade
 *
 * Ela evita uma ida ao servidor que já se sabe que falharia. Quem decide é o servidor, que
 * revalida tudo — inclusive "começa a partir de amanhã", que depende do relógio dele.
 *
 * ## O fuso não tem seletor
 *
 * O desafio usa o fuso deste aparelho, mostrado discretamente na revisão (§164). Um seletor de
 * fuso na criação de um desafio entre amigos seria uma escolha que ninguém quer fazer — e o caso
 * de amigos em fusos diferentes é raro o bastante para não pagar essa complexidade agora.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChallengeScreen(
    viewModel: ChallengeViewModel,
    onNavigateBack: () -> Unit,
    onCreated: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.startCreation() }

    // Criado: a tela sai e a lista recarrega. `creationHandled` limpa a fase para que voltar a
    // esta tela não caia num estado de "já criado".
    val phase = uiState.creationPhase
    LaunchedEffect(phase) {
        if (phase is ChallengeCreationPhase.Created) {
            val id = phase.challenge.challengeId
            viewModel.creationHandled()
            onCreated(id)
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = CREATE_CHALLENGE_TITLE,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.cancelCreation()
                            onNavigateBack()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = BACK_LABEL,
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
                .padding(16.dp)
                .semantics { contentDescription = CREATE_CHALLENGE_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            uiState.notice?.let { ChallengeMessage(title = "Aviso", body = messageFor(it)) }

            TypePicker(
                selected = uiState.draft.type,
                onSelect = viewModel::updateDraftType
            )

            LabeledField(
                label = "Nome",
                value = uiState.draft.name,
                onChange = viewModel::updateDraftName
            )

            LabeledField(
                label = "Meta",
                value = uiState.draft.target.toString(),
                keyboardType = KeyboardType.Number,
                onChange = { raw -> raw.toIntOrNull()?.let(viewModel::updateDraftTarget) }
            )

            PeriodFields(
                draft = uiState.draft,
                onChange = viewModel::updateDraftPeriod
            )

            FriendPicker(
                friends = uiState.selectableFriends,
                selected = uiState.draft.invitedSocialIds,
                onToggle = viewModel::toggleInvited
            )

            Review(draft = uiState.draft, friends = uiState.selectableFriends)

            if (uiState.isCreating) {
                ChallengeBusy("Criando...")
            } else {
                Button(
                    onClick = viewModel::submitCreation,
                    // O botão bloqueado cobre o toque duplo rápido; o `clientRequestId` cobre o
                    // retry depois de uma falha de rede. Os dois são necessários.
                    enabled = viewModel.isDraftValid(uiState.draft),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400)
                ) {
                    Text(
                        CREATE_CHALLENGE_LABEL,
                        color = BackgroundDark,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun TypePicker(selected: ChallengeType, onSelect: (ChallengeType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChallengeSectionTitle("Tipo")
        ChallengeType.entries.forEach { type ->
            Surface(
                color = SurfaceDark,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (type == selected) Lime400 else BorderLight),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(type) }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = labelFor(type),
                        color = if (type == selected) Lime400 else TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    // A explicação fica visível sempre, e não atrás de um "?": a diferença entre
                    // os dois tipos é exatamente onde alguém se frustraria depois.
                    Text(text = explanationFor(type), color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PeriodFields(draft: ChallengeDraft, onChange: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChallengeSectionTitle("Período")
        LabeledField(
            label = "Início (AAAA-MM-DD)",
            value = draft.startDate,
            onChange = { onChange(it, draft.endDate) }
        )
        LabeledField(
            label = "Fim (AAAA-MM-DD)",
            value = draft.endDate,
            onChange = { onChange(draft.startDate, it) }
        )
        Text(
            text = "O desafio precisa começar a partir de amanhã.",
            color = TextSecondary,
            fontSize = 12.sp
        )
    }
}

/**
 * A escolha dos amigos.
 *
 * A lista é a de amigos **ativos** da T17.1 — ela já não devolve perfis desativados. Isso não é
 * autorização: quem decide se um convidado pode participar é o servidor, na criação.
 */
@Composable
private fun FriendPicker(
    friends: List<Friend>,
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChallengeSectionTitle("Amigos")

        if (friends.isEmpty()) {
            ChallengeMessage(
                title = "Nenhum amigo ainda",
                body = "Adicione amigos para poder convidá-los para um desafio."
            )
            return@Column
        }

        Text(
            text = "Até ${ChallengeContract.Limits.MAX_PARTICIPANTS} participantes, " +
                "incluindo você.",
            color = TextSecondary,
            fontSize = 12.sp
        )

        friends.forEach { friend ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(friend.socialId) }
            ) {
                Checkbox(
                    checked = friend.socialId in selected,
                    onCheckedChange = { onToggle(friend.socialId) },
                    colors = CheckboxDefaults.colors(checkedColor = Lime400)
                )
                Text(text = friend.displayName, color = TextPrimary, fontSize = 15.sp)
            }
        }
    }
}

/** A revisão: o que vai ser criado, em uma frase, antes do toque (§167). */
@Composable
private fun Review(draft: ChallengeDraft, friends: List<Friend>) {
    val chosen = friends.filter { it.socialId in draft.invitedSocialIds }
    val names = if (chosen.isEmpty()) {
        "só você"
    } else {
        "você + " + chosen.joinToString(", ") { it.displayName }
    }

    ChallengeMessage(
        title = draft.name.ifBlank { "Sem nome" },
        body = buildString {
            append(labelFor(draft.type))
            append(" · meta ")
            append(draft.target)
            append('\n')
            append(shortDate(draft.startDate))
            append(" — ")
            append(shortDate(draft.endDate))
            append('\n')
            append(names)
            if (draft.timeZoneId.isNotBlank()) {
                // O fuso aparece discretamente: ele decide o que é "um dia" para todos, e a
                // pessoa precisa poder conferir — mas ele não é uma escolha nesta fase.
                append("\nFuso: ")
                append(draft.timeZoneId)
            }
        }
    )
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = TextSecondary) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = Lime400,
            unfocusedBorderColor = BorderLight
        )
    )
}

/** Um atalho de leitura para a tela de desafios: há convites esperando resposta? */
fun hasPendingInvites(uiState: ChallengeUiState): Boolean = uiState.pendingInviteCount > 0

/** O botão de voltar sem criar nada. */
@Composable
internal fun DiscardCreationAction(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(BACK_LABEL, color = TextSecondary) }
}
