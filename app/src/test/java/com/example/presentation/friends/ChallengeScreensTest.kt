package com.example.presentation.friends

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.social.FakeChallengeGateway
import com.example.domain.social.ChallengeParticipantStatus
import com.example.domain.social.ChallengeRole
import com.example.domain.social.ChallengeStatus
import com.example.domain.social.ChallengeType
import com.example.presentation.account.ChallengeDetailPhase
import com.example.presentation.account.ChallengeListPhase
import com.example.presentation.account.ChallengeUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * As telas de desafio, renderizadas de verdade (T17.3 §228/§229).
 *
 * O que só uma tela real prova:
 *
 * 1. **o placar mostra o número real, mesmo acima da meta.** 15 de 12 aparece como `15 / 12`;
 * 2. **empate aparece como empate.** Duas linhas com a mesma posição, e não uma inventada;
 * 3. **um desafio encerrado que ainda pode convergir diz isso.** É a diferença entre um resultado
 *    honesto e uma promessa que a sincronização de amanhã quebra;
 * 4. **cancelado e VOID não mostram placar.** Eles não têm resultado, e um placar de uma linha
 *    seria uma disputa inventada;
 * 5. **nenhuma tela mostra treino bruto, horário, carga, medida ou identidade privada.**
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ChallengeScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------------ placar

    @Test
    fun `o placar mostra posicao, nome e pontuacao`() {
        renderDetail(FakeChallengeGateway.leaderboard())

        composeRule.onNodeWithText("1. Igor (você)").assertIsDisplayed()
        composeRule.onNodeWithText("8 / 12").assertIsDisplayed()
        composeRule.onNodeWithText("2. João").assertIsDisplayed()
        composeRule.onNodeWithText("7 / 12").assertIsDisplayed()
        composeRule.onNodeWithText("3. Jonathas").assertIsDisplayed()
        composeRule.onNodeWithText("5 / 12").assertIsDisplayed()
    }

    @Test
    fun `empate aparece com a mesma posicao nas duas linhas`() {
        renderDetail(
            FakeChallengeGateway.leaderboard(
                scores = listOf(
                    Triple("social-igor", "Igor", 8),
                    Triple("social-joao", "João", 8),
                    Triple("social-jonathas", "Jonathas", 6)
                )
            )
        )

        // Duas primeiras posições, e a terceira pula para 3 — *competition ranking*.
        composeRule.onNodeWithText("1. Igor (você)").assertIsDisplayed()
        composeRule.onNodeWithText("1. João").assertIsDisplayed()
        composeRule.onNodeWithText("3. Jonathas").assertIsDisplayed()
    }

    @Test
    fun `pontuacao acima da meta aparece inteira — a barra e que limita`() {
        renderDetail(
            FakeChallengeGateway.leaderboard(
                scores = listOf(
                    Triple("social-igor", "Igor", 15),
                    Triple("social-joao", "João", 13),
                    Triple("social-jonathas", "Jonathas", 12)
                )
            )
        )

        // O número não é truncado na meta: 15 de 12 é o que aconteceu.
        composeRule.onNodeWithText("15 / 12").assertIsDisplayed()
        composeRule.onNodeWithText("Todos atingiram a meta.").assertIsDisplayed()
    }

    @Test
    fun `meta parcial nao anuncia meta atingida`() {
        renderDetail(FakeChallengeGateway.leaderboard())

        composeRule.onNodeWithText("Todos atingiram a meta.").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ convergência

    @Test
    fun `desafio encerrado que pode convergir avisa que o resultado pode mudar`() {
        renderDetail(
            FakeChallengeGateway.leaderboard(
                challenge = FakeChallengeGateway.challenge(status = ChallengeStatus.ENDED),
                resultMayStillChange = true
            )
        )

        composeRule.onNodeWithText(RESULT_MAY_CHANGE_NOTICE).assertIsDisplayed()
    }

    @Test
    fun `desafio em andamento nao promete nem avisa convergencia`() {
        renderDetail(FakeChallengeGateway.leaderboard(resultMayStillChange = false))

        composeRule.onNodeWithText(RESULT_MAY_CHANGE_NOTICE).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ sem resultado

    @Test
    fun `desafio cancelado nao mostra placar`() {
        renderDetail(
            FakeChallengeGateway.leaderboard(
                challenge = FakeChallengeGateway.challenge(status = ChallengeStatus.CANCELLED)
            )
        )

        composeRule.onNodeWithText("Desafio cancelado").assertIsDisplayed()
        // Sem vencedor, e sem placar: não há resultado para mostrar.
        composeRule.onNodeWithText("8 / 12").assertDoesNotExist()
    }

    @Test
    fun `desafio sem participantes suficientes nao finge competicao`() {
        renderDetail(
            FakeChallengeGateway.leaderboard(
                challenge = FakeChallengeGateway.challenge(
                    status = ChallengeStatus.VOID,
                    participantCount = 1
                )
            )
        )

        composeRule.onNodeWithText("Sem competição").assertIsDisplayed()
        composeRule.onNodeWithText("8 / 12").assertDoesNotExist()
    }

    @Test
    fun `desafio que ainda nao comecou mostra participantes, e nao placar`() {
        renderDetail(
            FakeChallengeGateway.leaderboard(
                challenge = FakeChallengeGateway.challenge(status = ChallengeStatus.UPCOMING)
            )
        )

        composeRule.onNodeWithText("Começa em 10/09").assertIsDisplayed()
        composeRule.onNodeWithText("Participantes").assertIsDisplayed()
        // Pontuação nenhuma antes de a janela abrir.
        composeRule.onNodeWithText("8 / 12").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ ações

    @Test
    fun `quem pode cancelar ve cancelar, e nao sair`() {
        renderDetail(
            FakeChallengeGateway.leaderboard(
                role = ChallengeRole.CREATOR,
                canCancel = true,
                canLeave = false
            )
        )

        composeRule.onNodeWithText(CANCEL_CHALLENGE_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(LEAVE_CHALLENGE_LABEL).assertDoesNotExist()
    }

    @Test
    fun `quem ja saiu nao ve nenhuma das duas acoes`() {
        renderDetail(
            FakeChallengeGateway.leaderboard(
                status = ChallengeParticipantStatus.WITHDRAWN,
                canCancel = false,
                canLeave = false
            )
        )

        composeRule.onNodeWithText(CANCEL_CHALLENGE_LABEL).assertDoesNotExist()
        composeRule.onNodeWithText(LEAVE_CHALLENGE_LABEL).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ lista

    @Test
    fun `a lista agrupa por estado`() {
        composeRule.setContent {
            ChallengesBody(
                uiState = ChallengeUiState(
                    listPhase = ChallengeListPhase.Ready(
                        listOf(
                            FakeChallengeGateway.challenge(
                                id = "a",
                                name = "12 treinos",
                                status = ChallengeStatus.ACTIVE
                            ),
                            FakeChallengeGateway.challenge(
                                id = "b",
                                name = "10 dias ativos",
                                type = ChallengeType.ACTIVE_DAYS,
                                target = 10,
                                status = ChallengeStatus.UPCOMING
                            ),
                            FakeChallengeGateway.challenge(
                                id = "c",
                                name = "Treinos de Agosto",
                                status = ChallengeStatus.ENDED
                            )
                        )
                    )
                ),
                onOpenChallenge = {},
                onCreateChallenge = {},
                onAcceptInvite = {},
                onDeclineInvite = {},
                onRetry = {}
            )
        }

        composeRule.onNodeWithText(SECTION_ACTIVE).assertIsDisplayed()
        composeRule.onNodeWithText(SECTION_UPCOMING).assertIsDisplayed()
        composeRule.onNodeWithText(SECTION_FINISHED).assertIsDisplayed()
        composeRule.onNodeWithText("12 treinos").assertIsDisplayed()
        composeRule.onNodeWithText("10 dias ativos").assertIsDisplayed()
        composeRule.onNodeWithText("Treinos de Agosto").assertIsDisplayed()
        // A lista não mostra pontuação: ler o placar de cada linha custaria uma consulta por
        // participante de cada desafio.
        composeRule.onNodeWithText("8 / 12").assertDoesNotExist()
    }

    @Test
    fun `um convite mostra o aviso de consentimento antes das acoes`() {
        composeRule.setContent {
            ChallengesBody(
                uiState = ChallengeUiState(
                    listPhase = ChallengeListPhase.Ready(emptyList()),
                    invites = listOf(FakeChallengeGateway.invite())
                ),
                onOpenChallenge = {},
                onCreateChallenge = {},
                onAcceptInvite = {},
                onDeclineInvite = {},
                onRetry = {}
            )
        }

        composeRule.onNodeWithText("Igor convidou você").assertIsDisplayed()
        // O consentimento é dito **antes** do aceite, e diz as duas metades: o que passa a ser
        // visto, e o que continua privado.
        composeRule.onNodeWithText(CHALLENGE_CONSENT_NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText(ACCEPT_CHALLENGE_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(DECLINE_CHALLENGE_LABEL).assertIsDisplayed()
    }

    @Test
    fun `offline diz que nada foi alterado e que o treino continua`() {
        composeRule.setContent {
            ChallengesBody(
                uiState = ChallengeUiState(listPhase = ChallengeListPhase.Offline),
                onOpenChallenge = {},
                onCreateChallenge = {},
                onAcceptInvite = {},
                onDeclineInvite = {},
                onRetry = {}
            )
        }

        composeRule.onNodeWithText("Sem conexão").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Não foi possível falar com o servidor, então nada foi alterado. " +
                    "Seus treinos e seu histórico continuam normais."
            )
            .assertIsDisplayed()
    }

    // ------------------------------------------------------------------ privacidade

    @Test
    fun `nenhuma tela de desafio mostra treino bruto ou identidade privada`() {
        renderDetail(FakeChallengeGateway.leaderboard())

        // O DTO não carrega nada disto, e a tela não teria de onde tirar. O teste existe para que
        // um campo novo no contrato não apareça na tela sem alguém decidir isso.
        for (forbidden in listOf(
            "uid-", "@", "SPK-", "supino", "kg", "séries", "repetições", "19:32"
        )) {
            composeRule.onNodeWithText(forbidden, substring = true).assertDoesNotExist()
        }
    }

    // ------------------------------------------------------------------ apoio

    private fun renderDetail(detail: com.example.domain.social.ChallengeDetail) {
        composeRule.setContent {
            ChallengeDetailBody(
                uiState = ChallengeUiState(
                    detailPhase = ChallengeDetailPhase.Ready(detail),
                    openedChallengeId = detail.challenge.challengeId
                ),
                challengeId = detail.challenge.challengeId,
                onRetry = {},
                onRequestLeave = {},
                onRequestCancel = {}
            )
        }
    }
}
