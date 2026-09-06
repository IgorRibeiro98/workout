package com.example.domain.ai

import com.example.domain.ai.model.AiCoachExplanation
import com.example.domain.ai.model.AiCoachExplanationTarget

/**
 * Reutilização de explicações **em memória**, durante a sessão de uso.
 *
 * Não é persistência: não há tabela, migration nem arquivo. O objetivo é único e estreito — o
 * usuário que fecha e reabre a mesma explicação não paga uma segunda chamada ao provider.
 *
 * A chave é [AiCoachExplanationTarget]: origem + id do contexto + revisão da origem + tipo de
 * request. A revisão é o que resolve o problema de contexto obsoleto — quando o rascunho, o
 * treino ou os números mudam, a chave muda junto e a entrada anterior simplesmente não é
 * encontrada. Nada é "invalidado" por evento; a identidade carrega o estado.
 *
 * Só explicação **escrita pelo modelo** entra aqui: a local é recomputada de graça e sempre
 * reflete o estado atual.
 */
class AiCoachExplanationCache(
    /** Teto de entradas. Poucas, porque a janela útil é a navegação atual, não o histórico. */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {

    private val entries = LinkedHashMap<AiCoachExplanationTarget, AiCoachExplanation>()

    val size: Int
        @Synchronized get() = entries.size

    @Synchronized
    fun get(target: AiCoachExplanationTarget): AiCoachExplanation? {
        val cached = entries.remove(target) ?: return null
        // Reinserir mantém a ordem de uso: o descarte é sempre do mais antigo.
        entries[target] = cached
        return cached
    }

    @Synchronized
    fun put(target: AiCoachExplanationTarget, explanation: AiCoachExplanation) {
        entries.remove(target)
        entries[target] = explanation
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 16
    }
}
