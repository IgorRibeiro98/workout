package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * O "agora" do tempo relativo, reavaliado de minuto em minuto.
 *
 * Lido uma vez na composição, "há 2 min" congelava: quem deixasse o Feed aberto continuaria lendo
 * o mesmo texto meia hora depois (auditoria 2026-09-12). O intervalo é o mesmo da menor unidade
 * que o texto mostra — atualizar mais rápido não mudaria nenhuma palavra.
 *
 * ## Isto não é polling
 *
 * O laço abaixo só relê o relógio local. Ele **não** pode — e não deve jamais passar a — disparar
 * carregamento de dados: o Feed e o detalhe do check-in têm por regra que nada acontece sem o
 * usuário abrir a tela ou puxar para atualizar (§93/§94 do contrato social), e há um teste
 * estrutural que proíbe `delay(`/`while (true)` naqueles arquivos justamente para proteger isso.
 * O helper vive aqui, compartilhado por Feed, Squad e detalhe, porque é um tique de UI e não uma
 * decisão de rede.
 */
@Composable
fun rememberRelativeNow(intervalMillis: Long = 60_000L): Long {
    val now by produceState(initialValue = System.currentTimeMillis(), intervalMillis) {
        while (true) {
            value = System.currentTimeMillis()
            delay(intervalMillis)
        }
    }
    return now
}
