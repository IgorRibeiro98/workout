package com.example.domain.ai

import com.example.domain.ai.model.AiCapabilitiesGatewayResult
import kotlinx.coroutines.CompletableDeferred

/**
 * Dublê do [AiCapabilitiesGateway] para os testes (T19.0).
 *
 * Vive em `src/test`, como [com.example.domain.auth.FakeAuthGateway]: nenhum caminho de produção
 * pode usá-lo. [gate], quando presente, é aguardado antes de devolver [nextResult] — é o que
 * permite a um teste prender uma consulta "no ar" para provar que a resposta de uma conta que já
 * saiu não vira o estado de quem entrou depois (o mesmo padrão de `FakeFriendGateway`).
 */
class FakeAiCapabilitiesGateway(
    var nextResult: AiCapabilitiesGatewayResult = AiCapabilitiesGatewayResult.Success(emptySet())
) : AiCapabilitiesGateway {

    var gate: CompletableDeferred<Unit>? = null

    var fetchCount: Int = 0
        private set

    override suspend fun fetch(): AiCapabilitiesGatewayResult {
        fetchCount++
        gate?.await()
        return nextResult
    }
}
