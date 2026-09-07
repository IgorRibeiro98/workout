package com.example.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Quem decide **quando** um ciclo de sync acontece (T16.6).
 *
 * ```text
 * toque em "Sincronizar agora"   ─┐
 * app volta para o primeiro plano ├─▶ SyncCoordinator ─▶ SyncRepository ─▶ Room + Spark Backend
 * alteração local entrou na fila ─┘        │
 *                                          └─▶ SyncScheduler (WorkManager, com rede)
 * ```
 *
 * A fronteira existe para que a lógica de ciclo não more na UI: uma tela não sabe se já há um
 * ciclo rodando, não sabe se vale a pena rodar, e não deveria precisar saber.
 *
 * ## Um ciclo por vez
 *
 * Dois toques em "Sincronizar agora" produzem **um** ciclo. A proteção é dupla de propósito: um
 * `Mutex` aqui evita até criar a corrotina, e a `CloudOperationLock` do repositório recusa de novo
 * — e é ela que também impede um ciclo de rodar durante um backup ou um restore, que mexem no
 * mesmo banco.
 *
 * ## Nada em `init`
 *
 * Criar o coordenador não sincroniza, não abre conexão e não agenda nada. Os gatilhos são
 * explícitos: um toque, o app voltando ao primeiro plano, ou uma alteração local entrando na fila.
 * Não existe laço, `while(true)`, timer de segundos nem polling — o Spark não precisa de tempo
 * real, e martelar a VPS custaria bateria e banda por nada.
 */
class SyncCoordinator(
    private val repository: SyncRepository,
    private val accounts: SyncAccountProvider,
    private val scheduler: SyncScheduler,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val mutex = Mutex()

    private val _activity = MutableStateFlow<SyncActivity>(SyncActivity.Idle)

    /** O que está acontecendo agora. A tela combina isto com [SyncRepository.snapshot]. */
    val activity: StateFlow<SyncActivity> = _activity.asStateFlow()

    /** "Sincronizar agora": o gatilho explícito do usuário. */
    fun syncNow() {
        scope.launch { runCycle() }
    }

    /**
     * O app voltou para o primeiro plano.
     *
     * Conservador de propósito: só roda se houver alteração pendente ou se a última sincronização
     * já estiver velha. Abrir o app dez vezes em cinco minutos não produz dez ciclos.
     *
     * Não bloqueia a primeira renderização: é uma corrotina, e a UI continua lendo o Room.
     */
    fun onAppForeground() {
        scope.launch {
            if (!repository.isConfigured) return@launch
            val snapshot = repository.snapshot()
            if (snapshot.ownerUid == null) return@launch

            val stale = snapshot.lastSyncedAt?.let { clock() - it >= FOREGROUND_MIN_INTERVAL_MS } ?: true
            if (snapshot.pending > 0 || stale) runCycle()
        }
    }

    /**
     * Uma alteração local acabou de entrar na Outbox.
     *
     * Não sincroniza na hora: agenda **um** trabalho único com restrição de rede. Um toque de
     * usuário não vira uma requisição HTTP imediata — é isso que evita uma requisição por campo
     * editado, e é o que faz o app funcionar igual no metrô.
     */
    fun onLocalMutation() {
        if (!repository.isConfigured) return
        scheduler.scheduleSoon()
    }

    /**
     * Um ciclo, para quem já está em uma corrotina — o trabalho em background.
     *
     * Devolve o desfecho para que o worker decida entre "terminou" e "tentar de novo depois", e
     * só isso: o worker não interpreta domínio.
     */
    suspend fun runOnce(): SyncOutcome = runCycle()

    private suspend fun runCycle(): SyncOutcome {
        // Antes de qualquer coisa, e **antes** de perguntar quem está logado: sem backend
        // configurado ou sem dataset adotado não há ciclo, e consultar a sessão inicializaria a
        // autenticação de um app que talvez nunca vá usá-la. O Spark é local-first também aqui.
        if (!repository.isConfigured) return SyncOutcome.NotConfigured
        if (repository.binding() == null) return SyncOutcome.NotEnabled

        // `tryLock` e não uma fila: enfileirar dez toques criaria dez ciclos em sequência —
        // resolveria a concorrência sem resolver o problema.
        if (!mutex.tryLock()) return SyncOutcome.AlreadyRunning

        return try {
            _activity.value = SyncActivity.Running
            val outcome = repository.syncNow(accounts.currentUid())
            _activity.value = SyncActivity.Finished(outcome, clock())
            outcome
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        /**
         * Quanto tempo uma sincronização precisa ter para que abrir o app dispare outra.
         *
         * Quinze minutos: o Spark não é tempo real, e a convergência que importa acontece entre
         * treinos, não entre segundos.
         */
        const val FOREGROUND_MIN_INTERVAL_MS = 15 * 60 * 1000L
    }
}

/** De onde o coordenador descobre a sessão atual, sem conhecer Firebase. */
fun interface SyncAccountProvider {

    /** O `uid` da sessão atual, ou `null` quando não há conta conectada. */
    suspend fun currentUid(): String?
}

/**
 * Quem agenda um ciclo para "quando der".
 *
 * É interface para que o coordenador seja testável sem `WorkManager` — e para que a decisão de
 * usar `WorkManager` fique em um arquivo só, na camada que pode conhecer `Context`.
 */
interface SyncScheduler {

    /** Agenda **um** ciclo, quando houver rede. Chamar dez vezes agenda um. */
    fun scheduleSoon()

    /** O padrão de um Spark sem agendador: nada é agendado, e o manual continua funcionando. */
    object Disabled : SyncScheduler {
        override fun scheduleSoon() = Unit
    }
}

/** O que o coordenador está fazendo. A tela traduz; ela não guarda uma segunda máquina de estados. */
sealed interface SyncActivity {

    /** Nenhum ciclo em andamento e nenhum resultado recente a mostrar. */
    data object Idle : SyncActivity

    /** Um ciclo está rodando. */
    data object Running : SyncActivity

    /** O último ciclo terminou assim, neste instante do relógio local. */
    data class Finished(val outcome: SyncOutcome, val at: Long) : SyncActivity
}
