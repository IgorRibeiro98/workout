package com.example.domain.ai

import com.example.domain.ai.model.AiCoachRequest
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiRecommendationType
import kotlinx.serialization.json.Json

/**
 * O único lugar do Spark onde existe prompt.
 *
 * Nenhum Composable, Screen ou ViewModel escreve instrução para o modelo. As quatro partes ficam
 * conceitualmente separadas: instruções de sistema, contexto, intenção do usuário e schema de
 * saída (este último em [AiCoachResponseSchema], porque é contrato do provider).
 */
object AiCoachPrompt {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = true
    }

    /** Regras invioláveis do Coach. A IA aconselha; o domínio decide. */
    fun systemInstruction(): String = """
        Você é o Coach do Spark, um aplicativo de treino de musculação em português do Brasil.

        Regras obrigatórias:
        1. Use exclusivamente os dados fornecidos no contexto. Não use conhecimento sobre este
           usuário vindo de qualquer outra fonte.
        2. Não invente histórico, sessões, séries, cargas, repetições ou datas.
        3. Não invente exercícios. Cite apenas exercícios presentes no contexto.
        4. Preserve os identificadores: ao citar um exercício, use exatamente o "exerciseId"
           recebido. Nunca use o nome como identificador e nunca crie um id novo.
        5. Você não altera nada no aplicativo. Nunca afirme que aplicou, alterou, salvou ou
           corrigiu algo.
        6. Não declare recorde pessoal que não esteja em "personalRecords".
        7. Não declare que um treino foi concluído; isso é decidido pelo aplicativo.
        8. Use somente os tipos permitidos em "type": ${AiRecommendationType.entries.joinToString(", ") { it.name }}.
        9. Responda estritamente no schema JSON solicitado, sem texto fora dele.
        10. Se os dados forem insuficientes para uma conclusão, diga isso no resumo e prefira
            ${AiRecommendationType.GENERAL.name} em vez de arriscar uma recomendação específica.

        Escreva em português do Brasil, de forma direta e curta.
    """.trimIndent()

    /** Intenção do usuário + contexto serializado, na mesma ordem em toda chamada. */
    fun userPrompt(request: AiCoachRequest): String {
        val intent = when (request.type) {
            AiCoachRequestType.ANALYZE_WORKOUT ->
                "Analise o treino do atleta e explique o que os dados mostram."
        }
        return buildString {
            appendLine(intent)
            appendLine()
            appendLine("requestId: ${request.requestId}")
            appendLine("schemaVersion: ${request.schemaVersion}")
            appendLine()
            appendLine("Contexto (JSON):")
            append(json.encodeToString(request.context))
        }
    }
}
