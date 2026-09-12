package com.example.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O escape dos argumentos de rota.
 *
 * Um teste de comportamento não pegaria isto: a navegação funciona, a tela abre e o nome aparece —
 * só que escrito errado. `URLEncoder` produz `+` para espaço (regra de formulário HTML), e quem
 * desfaz o escape do outro lado é o `Uri.decode` da navegação, que **não** trata `+` como espaço.
 * O resultado era "João+Silva" no cabeçalho do perfil e "Desafio+de+Verão" no do desafio.
 */
class ScreenRouteEncodingTest {

    @Test
    fun `nome de amigo com espaco viaja como %20, nunca como +`() {
        val route = Screen.FriendProfile.createRoute("soc_123", "João Silva")

        assertTrue("a rota precisa levar o nome: $route", route.contains("name=Jo"))
        assertEquals(
            "friend_profile/soc_123?name=Jo%C3%A3o%20Silva",
            route
        )
        assertTrue("nenhum '+' pode sobrar na rota: $route", !route.contains("+"))
    }

    @Test
    fun `nome de desafio com espaco viaja como %20, nunca como +`() {
        val route = Screen.ChallengeDetail.createRoute("ch_1", "Desafio de Verão")

        assertEquals(
            "challenge/ch_1?name=Desafio%20de%20Ver%C3%A3o",
            route
        )
        assertTrue("nenhum '+' pode sobrar na rota: $route", !route.contains("+"))
    }

    @Test
    fun `o check-in de Squad continua escapando o nome do grupo`() {
        // Este caminho já estava certo — o teste existe para que ele continue certo depois de a
        // função de escape ter virado uma só para as três rotas.
        val route = Screen.CheckInDetail.createGroupRoute("ci_1", "grp_1", "Os Monstros")

        assertEquals(
            "check_in/ci_1?context=group&groupId=grp_1&groupName=Os%20Monstros",
            route
        )
    }

    @Test
    fun `nome vazio produz rota valida`() {
        assertEquals("challenge/ch_1?name=", Screen.ChallengeDetail.createRoute("ch_1"))
    }
}
