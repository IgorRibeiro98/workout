# Contrato de identidade global do Spark

- **Tarefa:** T16.0 (documentação) — implementação em **T16.1** e **T16.3**.
- **Status:** **nada aqui está implementado.** A T16.0 não adicionou `syncId`, `deviceId` ou
  `ownerUid` a nenhuma entidade Room, a nenhum DAO e a nenhuma tabela do servidor. Verificado no
  código: `syncId`, `deviceId`, `ownerUid` e `mutationId` têm **zero ocorrências** em `app/src`.

Este documento define o contrato que a T16.3 deverá seguir, para que a migração das entidades seja
uma decisão já tomada em vez de improvisada.

---

## Os quatro identificadores

### `localId` — identidade dentro do aparelho

O que o Room já usa hoje: `@PrimaryKey(autoGenerate = true) val id: Long`.

Continua existindo e continua sendo a chave de todos os relacionamentos locais (`templateId`,
`sessionId`, `exerciseSessionId`, ...). A T16.3 **não** troca as chaves estrangeiras do Room por
identificadores globais — isso reescreveria o schema inteiro sem ganho.

`localId` **nunca** é identidade cross-device: dois aparelhos geram `1`, `2`, `3` para coisas
completamente diferentes.

### `syncId` — identidade global estável

Identificador global de uma entidade pessoal, estável para sempre.

- **Formato:** UUID aleatório (v4), como `String`.
- **Gerado por:** o dispositivo, **offline**, no momento em que a entidade é criada. Não depende de
  rede, de servidor nem de conta — um treino criado em modo avião já nasce com `syncId`.
- **Onde vive:** uma coluna nova nas tabelas do Grupo A da
  [matriz de dados](./data-classification-matrix.md), com índice único.
- **Imutável:** editar um template não muda seu `syncId`. É isso que permite ao servidor reconhecer
  "esta é a mesma entidade" em vez de criar uma cópia.

Proibido usar como identidade cross-device, porque nenhum destes é estável nem único entre
aparelhos:

- `displayName` ou qualquer nome exibido;
- nome do exercício;
- timestamp isolado (`createdAt`, `startedAt`);
- `id` autoincrement do Room.

**Backfill (T16.3):** entidades que já existem no aparelho recebem `syncId` em uma migração Room
que gera um UUID por linha. É uma migração aditiva — nenhuma coluna existente muda de significado e
nenhum dado é destruído.

### `canonicalExerciseId` — identidade de conteúdo, que já existe

O catálogo canônico **já tem** identidade estável: `ExerciseEntity.canonicalId`, vinda do manifesto
versionado, com `slug` e `contentVersion`.

**Essa identidade não muda e não é substituída.** Um exercício canônico é o mesmo exercício em todo
aparelho porque veio do mesmo manifesto — ele não precisa de `syncId` e não vai ganhar um.

O `syncId` existe para **entidades pessoais criadas pelo usuário**. Ele não reinventa identidade de
catálogo.

Consequência prática para o sync: quando uma entidade pessoal referencia um exercício, ela
referencia:

- `canonicalId`, quando o exercício é do catálogo;
- o `syncId` do exercício pessoal, quando `isUserCreated = 1`.

Nunca o `localId` do exercício — ele não significa nada no outro aparelho.

### `deviceId` — identidade da instalação

Identificador da instalação do Spark, necessário para o multi-device (T16.6) saber de onde veio uma
mudança e para o cursor de sync ser por dispositivo.

- **Formato:** UUID aleatório gerado pelo próprio Spark na primeira execução, guardado no DataStore.
- **Escopo:** a instalação. Desinstalar e reinstalar gera outro — e isso é correto, é outra cópia
  local.

**Proibido** derivar `deviceId` de IMEI, número de série, MAC, Android ID ou qualquer *fingerprint*
de hardware. São identificadores persistentes de aparelho: usá-los transformaria um detalhe de sync
em rastreamento, e vários são inacessíveis ou instáveis nas versões atuais do Android.

`deviceId` não é identidade de usuário e não autoriza nada.

---

## Ownership

Todo dado pessoal remoto tem um dono autenticado, representado por `ownerUid` (o `uid` do Firebase
Auth).

### A regra crítica

> O servidor **nunca** confia em `ownerUid` recebido no payload.

O caminho é sempre:

```text
Authorization: Bearer <Firebase ID Token>
        ↓
backend verifica a assinatura do token   [T16.1]
        ↓
uid confiável extraído do token verificado
        ↓
AuthenticatedPrincipal { uid }
        ↓
ownership de tudo que a requisição escreve ou lê
```

Se o corpo da requisição trouxer um `ownerUid`, ele é **ignorado**, não validado — aceitar um
`ownerUid` "conferindo se bate com o token" já seria um caminho a mais para errar. O único `uid` que
existe no servidor é o que veio do token.

Consequências, a partir da T16.1:

- toda leitura é filtrada por `ownerUid` do principal;
- toda escrita grava o `ownerUid` do principal;
- uma requisição sem token válido não acessa dado pessoal nenhum.

### Sem autenticação própria

O Spark **não** terá:

- usuário/senha próprios;
- JWT emitido pelo Spark;
- refresh token próprio.

A identidade é do Firebase Auth. O backend é um *verificador* de token, não um emissor. Reduz
superfície de ataque e não há nada aqui que justifique manter um sistema de credenciais.

---

## Conta continua opcional

Nada neste contrato torna a conta obrigatória.

| Sem conta | Com conta |
| --- | --- |
| Room funciona | tudo da coluna anterior |
| treino, execução, histórico funcionam | + backup |
| templates e catálogo funcionam | + restore |
| gamificação funciona | + sync multi-device |
| `syncId` é gerado normalmente e fica guardado localmente | + features online |

O `syncId` é gerado mesmo sem conta. Isso é deliberado: quando o usuário eventualmente criar uma
conta, os dados que já existem no aparelho **já têm identidade global** e podem ser enviados sem
precisar reconciliar nada.

---

## Cronograma

| Conceito | Documentado | Implementado |
| --- | --- | --- |
| `localId` | — (já existe) | já existe |
| `canonicalExerciseId` | — (já existe) | já existe |
| `AuthenticatedPrincipal` / `ownerUid` | T16.0 | **T16.1** |
| `syncId` nas entidades Room | T16.0 | **T16.3** |
| `deviceId` | T16.0 | **T16.3** |
| Registro de dispositivos no servidor | T16.0 | **T16.6** |
