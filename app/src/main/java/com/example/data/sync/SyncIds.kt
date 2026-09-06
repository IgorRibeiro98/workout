package com.example.data.sync

import java.util.UUID

/**
 * Geração de identificadores globais do Spark (T16.3).
 *
 * Existe para que "gerar um id" seja **um** lugar auditável, e não uma chamada solta a
 * `UUID.randomUUID()` espalhada por repositórios. Também é o ponto de injeção que deixa testes
 * de Outbox determinísticos sem tornar a produção previsível.
 */
fun interface IdGenerator {

    /** Um identificador novo, globalmente único, gerado localmente e sem depender de rede. */
    fun newId(): String
}

/**
 * O gerador de produção: UUID aleatório (v4) da biblioteca padrão.
 *
 * `UUID.randomUUID()` usa `SecureRandom`. Não é uma escolha de segurança — `syncId` não é segredo
 * (ver `docs/architecture/identity-contract.md`) — é a implementação madura já disponível, em vez
 * de um gerador caseiro.
 */
object RandomUuidIdGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Identidade global de entidade pessoal.
 *
 * O `syncId` responde **"qual entidade é esta?"** e nada além disso. Ele não diz de quem ela é
 * (isso é `ownerUid`, e só passa a existir com adoção explícita na T16.4), não diz de qual
 * aparelho veio (`deviceId`) e não identifica uma alteração (`clientMutationId`).
 *
 * Propriedades exigidas, garantidas pelo conjunto migração + coluna `NOT NULL` + índice `UNIQUE`:
 *
 * - único;
 * - não nulo depois da migração;
 * - imutável — editar a entidade nunca troca seu `syncId`;
 * - gerado localmente, offline, no momento da criação;
 * - independente de conta e de dispositivo.
 */
object SyncIds {

    /** Um `syncId` novo. Usado como valor padrão das entidades sincronizáveis do Room. */
    fun random(): String = RandomUuidIdGenerator.newId()

    /**
     * A mesma geração, em SQL, para o *backfill* da migração 30 → 31.
     *
     * `randomblob()` e `random()` são funções **não determinísticas** no SQLite: elas são
     * reavaliadas por linha, então um `UPDATE` de tabela inteira produz um valor distinto para
     * cada registro — que é exatamente o necessário aqui.
     *
     * O formato é o de um UUID v4 (`4` na versão, `8|9|a|b` na variante) para que um id gerado na
     * migração seja indistinguível de um gerado por [random]. `random() & 3` evita `abs()` sobre o
     * menor inteiro de 64 bits, que o SQLite trata como erro de overflow.
     *
     * A unicidade não é assumida por probabilidade: o índice `UNIQUE` é criado **depois** do
     * backfill, então uma colisão faria a migração falhar em vez de corromper o banco.
     */
    const val SQLITE_RANDOM_UUID: String =
        "lower(hex(randomblob(4))) || '-' || " +
            "lower(hex(randomblob(2))) || '-4' || " +
            "substr(lower(hex(randomblob(2))), 2) || '-' || " +
            "substr('89ab', (random() & 3) + 1, 1) || " +
            "substr(lower(hex(randomblob(2))), 2) || '-' || " +
            "lower(hex(randomblob(6)))"
}
