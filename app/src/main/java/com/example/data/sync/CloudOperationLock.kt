package com.example.data.sync

import kotlinx.coroutines.sync.Mutex

/**
 * Uma operação de nuvem por vez, neste aparelho (T16.5).
 *
 * ```text
 * backup em andamento   +  toque em "Restaurar"      → recusado
 * restore em andamento  +  toque em "Fazer backup"   → recusado
 * restore em andamento  +  toque em "Restaurar"      → recusado
 * ```
 *
 * Backup e restore disputam o **mesmo banco**: um lê o dataset inteiro para congelar um snapshot, o
 * outro substitui esse dataset. Deixá-los rodar juntos produziria um backup de um estado que nunca
 * existiu — metade do dataset antigo, metade do restaurado.
 *
 * ## `tryRun`, e não uma fila
 *
 * Enfileirar dez toques em "Fazer backup" criaria dez backups em sequência: resolveria a
 * concorrência sem resolver o problema (é a decisão que a T16.4 já tinha tomado com `tryLock`).
 * Quem chega com uma operação em andamento é **recusado**, e a UI diz que já há algo acontecendo.
 *
 * A trava mora aqui — e não no ViewModel — porque é no repositório que a tentativa nasce, e porque
 * dois ViewModels diferentes (backup e restore) não têm como se coordenar sozinhos.
 */
class CloudOperationLock {

    private val mutex = Mutex()

    /** `true` enquanto alguma operação de nuvem está em andamento. */
    val isBusy: Boolean get() = mutex.isLocked

    /**
     * Executa [block] se nada mais estiver rodando; devolve `null` se já houver operação em curso.
     *
     * `null` significa "não rodou", e nunca "rodou e não deu nada": quem chama devolve o estado
     * "já existe uma operação em andamento" em vez de inventar um resultado.
     */
    suspend fun <R : Any> tryRun(block: suspend () -> R): R? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
