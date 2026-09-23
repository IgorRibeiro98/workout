package com.example.presentation.account

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.sync.SyncBlockedGroup
import com.example.data.sync.SyncBlockedKind
import com.example.data.sync.SyncConflictCategory
import com.example.data.sync.SyncConflictChoice
import com.example.data.sync.SyncConflictDifference
import com.example.data.sync.SyncConflictId
import com.example.data.sync.SyncConflictSummary
import com.example.data.sync.SyncEntityType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O alerta de sincronização sempre tem um próximo passo (T19.H2 / H2.5).
 *
 * ## O defeito
 *
 * A tela colapsava duas coisas diferentes num número só (`max(blocked, conflicts)`) e usava o mesmo
 * texto para as duas:
 *
 * ```text
 * "3 itens precisam de atenção"
 * "Nada foi sobrescrito: as duas versões estão guardadas. Escolha qual manter."
 * [ Sincronizar agora ]
 * ```
 *
 * Com `conflicts` vazio não havia o que escolher, o botão rodava um ciclo e devolvia a mesma tela.
 * Um alerta que gira em falso.
 *
 * ## O que este teste afirma
 *
 * 1. conflito real → as duas versões, as escolhas, e **sem** "Sincronizar agora" fingindo resolver;
 * 2. alteração travada sem conflito → outro texto, outro próximo passo, e nenhuma promessa de
 *    "escolha qual manter";
 * 3. "atualize o app" não oferece botão de reenviar, porque reenviar não resolve.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w360dp-h740dp")
class SyncSectionStatesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val conflict = SyncConflictSummary(
        id = SyncConflictId(SyncEntityType.WORKOUT_TEMPLATE.name, "sync-1"),
        title = "Treino A",
        category = SyncConflictCategory.CHANGED_ON_BOTH,
        differences = listOf(SyncConflictDifference("Nome", "Treino A", "Treino Alfa")),
        choices = listOf(SyncConflictChoice.KEEP_LOCAL, SyncConflictChoice.USE_REMOTE),
        awaitingPush = false
    )

    @Test
    fun `um conflito real mostra as duas versoes e as escolhas`() {
        var resolved: Pair<SyncConflictId, SyncConflictChoice>? = null
        composeRule.setContent {
            SyncSection(
                uiState = SyncUiState(
                    phase = SyncPhase.NeedsAttention(1, null),
                    conflictCount = 1,
                    conflicts = listOf(conflict)
                ),
                onSyncNow = {},
                onResolveConflict = { id, choice -> resolved = id to choice }
            )
        }

        composeRule.onNodeWithText("1 item precisa da sua decisão").assertIsDisplayed()
        composeRule.onNodeWithText("Neste aparelho: Treino A").assertIsDisplayed()
        composeRule.onNodeWithText("Na nuvem: Treino Alfa").assertIsDisplayed()

        // O CTA é resolver. "Sincronizar agora" não aparece aqui: ele não decide nada por ninguém.
        composeRule.onNodeWithText("Sincronizar agora").assertDoesNotExist()

        composeRule.onNodeWithText("Manter deste aparelho").performClick()
        assertEquals(conflict.id to SyncConflictChoice.KEEP_LOCAL, resolved)
    }

    @Test
    fun `um conflito ja decidido nao pede a decisao de novo`() {
        composeRule.setContent {
            SyncSection(
                uiState = SyncUiState(
                    phase = SyncPhase.NeedsAttention(1, null),
                    conflictCount = 1,
                    conflicts = listOf(conflict.copy(awaitingPush = true, choices = emptyList()))
                ),
                onSyncNow = {}
            )
        }

        // A escolha já foi feita; o que falta é rede. Pedir de novo seria sugerir que ela se perdeu.
        composeRule.onNodeWithText("Sua escolha foi guardada").assertIsDisplayed()
        composeRule.onNodeWithText("precisa da sua decisão", substring = true).assertDoesNotExist()
        // E aqui "Sincronizar agora" **é** o próximo passo.
        composeRule.onNodeWithText("Sincronizar agora").assertIsDisplayed()
    }

    @Test
    fun `alteracao travada sem conflito nao promete escolher qual versao manter`() {
        composeRule.setContent {
            SyncSection(
                uiState = SyncUiState(
                    phase = SyncPhase.BlockedChanges(
                        listOf(SyncBlockedGroup(SyncBlockedKind.CHANGED_ELSEWHERE, 3)),
                        null
                    ),
                    blockedCount = 3
                ),
                onSyncNow = {}
            )
        }

        composeRule.onNodeWithText("3 alterações não foram enviadas").assertIsDisplayed()
        composeRule.onNodeWithText("Escolha qual manter", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("precisam da sua decisão", substring = true).assertDoesNotExist()
        // Aqui reenviar **é** o próximo passo: o item mudou em outro aparelho, e sincronizar traz
        // a versão de lá para revisar.
        composeRule.onNodeWithText("Sincronizar agora").assertIsDisplayed()
    }

    @Test
    fun `quando reenviar nao resolve, o botao nao e oferecido`() {
        composeRule.setContent {
            SyncSection(
                uiState = SyncUiState(
                    phase = SyncPhase.BlockedChanges(
                        listOf(SyncBlockedGroup(SyncBlockedKind.NEEDS_APP_UPDATE, 1)),
                        null
                    ),
                    blockedCount = 1
                ),
                onSyncNow = {}
            )
        }

        composeRule.onNodeWithText("1 alteração não foi enviada").assertIsDisplayed()
        composeRule.onNodeWithText("atualize o aplicativo", substring = true).assertIsDisplayed()
        // Tentar de novo daria exatamente a mesma resposta; oferecer o botão seria um convite a
        // girar em falso.
        composeRule.onNodeWithText("Sincronizar agora").assertDoesNotExist()
    }

    @Test
    fun `conteudo recusado pelo servidor diz que tentar de novo nao muda nada`() {
        composeRule.setContent {
            SyncSection(
                uiState = SyncUiState(
                    phase = SyncPhase.BlockedChanges(
                        listOf(SyncBlockedGroup(SyncBlockedKind.REJECTED_CONTENT, 1)),
                        null
                    ),
                    blockedCount = 1
                ),
                onSyncNow = {}
            )
        }

        composeRule.onNodeWithText("tentar de novo daria o mesmo resultado", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Sincronizar agora").assertDoesNotExist()
    }

    @Test
    fun `nenhum estado de alerta expoe vocabulario de protocolo`() {
        composeRule.setContent {
            SyncSection(
                uiState = SyncUiState(
                    phase = SyncPhase.BlockedChanges(
                        listOf(
                            SyncBlockedGroup(SyncBlockedKind.CHANGED_ELSEWHERE, 1),
                            SyncBlockedGroup(SyncBlockedKind.UNKNOWN, 1)
                        ),
                        null
                    ),
                    blockedCount = 2
                ),
                onSyncNow = {}
            )
        }

        for (jargon in listOf("revision", "syncId", "cursor", "STALE", "serverSequence", "UNSUPPORTED")) {
            composeRule.onNodeWithText(jargon, substring = true, ignoreCase = true).assertDoesNotExist()
        }
    }
}
