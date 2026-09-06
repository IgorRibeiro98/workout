package com.example.data.sync

/**
 * A forma persistida de [CloudSyncScope].
 *
 * `DISABLED` é o padrão e não precisa estar gravado: **ausência de vínculo já significa nuvem
 * desligada**, e é assim que todo aparelho nasce. Quem escreve `PREPARING` é a ativação explícita
 * de backup, e quem escreve `ENABLED` é o primeiro backup confirmado pelo servidor (T16.4).
 *
 * ## Onde este estado mora
 *
 * Na T16.3 ele era uma preferência (`CLOUD_SYNC_STATE`/`CLOUD_SYNC_OWNER_UID` no DataStore) —
 * nunca gravada, porque a adoção não existia. Desde a T16.4 ele mora no Room, em
 * `cloud_data_binding`: o que ele diz é sobre **este banco**, e é essa mudança de lugar que
 * permite vincular o dataset, capturar o snapshot, ler o corte da Outbox e criar a tentativa de
 * backup na mesma transação. Ver `com.example.data.backup.CloudDataBindingEntity`.
 *
 * Login continua não escrevendo aqui.
 */
enum class CloudSyncState {
    DISABLED,
    PREPARING,
    ENABLED
}
