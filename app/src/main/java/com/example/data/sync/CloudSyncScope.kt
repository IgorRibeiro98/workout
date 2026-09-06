package com.example.data.sync

/**
 * A quem pertencem, na nuvem, os dados deste aparelho (T16.3).
 *
 * ## O problema que este tipo resolve
 *
 * ```text
 * usuário usa o Spark sem conta  →  cria treinos, histórico, medidas
 *                                →  faz login pela primeira vez
 *                                →  de quem são esses dados?
 * ```
 *
 * A resposta fácil — "do `uid` que acabou de entrar" — é errada e perigosa. O aparelho pode ser
 * emprestado, a conta pode ser de outra pessoa, e a T16.1 deliberadamente deixou login, logout e
 * troca de conta sem tocar em dado local. Se o simples fato de existir um Firebase UID adotasse o
 * banco, a Conta B herdaria em silêncio o histórico da Conta A.
 *
 * Por isso o estado padrão é [Disabled]: dado local é **`LOCAL_UNOWNED`** — existe, tem identidade
 * global (`syncId`) e não tem dono remoto. Entrar na conta não muda isso. A adoção é um ato
 * explícito do usuário, e acontece na T16.4.
 *
 * ```text
 * T16.3                                  T16.4
 * Disabled                               Preparing(uid) → Enabled(uid)
 * dado local = LOCAL_UNOWNED             usuário ativa backup, confirma o que será associado,
 * nenhuma mutação remota é registrada    snapshot inicial sobe, ownership remoto nasce
 * ```
 */
sealed interface CloudSyncScope {

    /**
     * Nuvem desligada. **É o padrão e continua sendo o padrão ao final da T16.3.**
     *
     * Tudo funciona: criar, editar, executar, concluir, apagar. Nada é registrado para envio,
     * porque não há conta que possa enviá-lo — e uma fila sem dono é justamente a ambiguidade que
     * este desenho evita.
     */
    data object Disabled : CloudSyncScope

    /**
     * Adoção em andamento para [ownerUid]: o snapshot inicial da T16.4 está sendo produzido.
     *
     * Mutações **são** registradas neste estado. A conta já foi escolhida explicitamente, então
     * não há ambiguidade de dono; e descartar o que o usuário fizer enquanto o primeiro backup
     * roda seria perder alteração real. Se a adoção for abortada, a T16.4 descarta as entradas
     * desse `uid` — o inverso (perder mudanças) não teria conserto.
     */
    data class Preparing(val ownerUid: String) : CloudSyncScope

    /** Nuvem ativa para [ownerUid]. Alterações locais passam a ser registradas na Outbox. */
    data class Enabled(val ownerUid: String) : CloudSyncScope

    /**
     * A conta que poderá enviar as mutações registradas agora, ou `null` quando nenhuma pode.
     *
     * Nenhuma entrada da Outbox nasce sem este valor.
     */
    val recordingOwnerUid: String?
        get() = when (this) {
            is Disabled -> null
            is Preparing -> ownerUid
            is Enabled -> ownerUid
        }
}

/**
 * De onde o estado da nuvem é lido.
 *
 * É uma fronteira e não um acesso direto ao `SettingsManager` para que a decisão "isto vira
 * mutação remota?" seja injetável em teste sem montar DataStore — e para que nenhum repositório
 * precise conhecer onde a preferência mora.
 */
fun interface CloudSyncScopeProvider {

    suspend fun current(): CloudSyncScope

    companion object {

        /** O padrão do Spark hoje: nuvem desligada, dado local sem dono. */
        val Disabled: CloudSyncScopeProvider = CloudSyncScopeProvider { CloudSyncScope.Disabled }
    }
}
