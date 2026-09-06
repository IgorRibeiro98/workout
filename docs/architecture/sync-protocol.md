# Protocolo de sincronização do Spark — contrato futuro

- **Tarefa:** T16.0 (documentação) — implementação em **T16.3** a **T16.7**.
- **Status:** **nada aqui está implementado.** Não existe endpoint de sync, não existe outbox no
  Android, não existe tabela de mudanças no servidor. `/v1` está vazio na T16.0, e há teste
  automatizado que garante que `/v1/sync/push` e `/v1/sync/pull` respondem 404.

Este documento existe para que as decisões difíceis do sync estejam tomadas antes de a primeira
linha de sync ser escrita.

---

## Por que não "quem tem o timestamp maior vence"

A saída óbvia — comparar `updatedAt` e deixar o mais recente vencer — falha por um motivo simples:
**relógios de dispositivos divergem**. Fuso trocado, relógio manual, bateria, NTP atrasado.

Com *last write wins* baseado em relógio do cliente, um aparelho com o relógio adiantado uma hora
sobrescreve silenciosamente tudo que os outros fizerem naquela hora. O usuário não recebe erro
nenhum: só perde dado.

O protocolo do Spark usa, em vez disso, **versão por entidade** e **sequência controlada pelo
servidor**. Timestamps continuam existindo como metadado informativo, nunca como árbitro.

---

## Peças do protocolo

| Peça | O que é | Quem gera |
| --- | --- | --- |
| `syncId` | identidade global da entidade (UUID) | dispositivo, offline |
| `clientMutationId` | identidade de **uma tentativa de mutação** (UUID) | dispositivo, por mutação |
| `revision` | versão da entidade no servidor, inteiro crescente | servidor |
| `baseRevision` | a `revision` sobre a qual o cliente construiu a mudança | dispositivo |
| `changeSeq` | sequência global e monotônica de mudanças da conta | servidor |
| `cursor` | posição do cliente na sequência do servidor | servidor |
| `deviceId` | qual instalação originou a mudança | dispositivo |
| `deletedAt` | marcação de tombstone | servidor |

---

## Push (T16.3 / T16.6)

```text
ação do usuário
      ↓
domínio → Room            ← a escrita local acontece primeiro, e é o que a UI observa
      ↓
Outbox (Room)             ← fila durável de mutações pendentes
      ↓
POST /v1/sync/push
```

Cada item enviado carrega:

```text
clientMutationId   UUID da tentativa
entityType         "workout_template" | "workout_session" | ...
syncId             identidade global da entidade
baseRevision       revision conhecida pelo cliente (0 = criação)
operation          CREATE | UPDATE | DELETE
deviceId           origem
payload            conteúdo da entidade
```

O servidor precisa conseguir distinguir quatro situações:

| Situação | Como o servidor detecta | Resposta |
| --- | --- | --- |
| **Reenvio** | `clientMutationId` já registrado | devolve o resultado original, sem aplicar de novo |
| **Duplicidade** | mesmo `syncId` já criado | trata como update, não cria segunda entidade |
| **Versão stale** | `baseRevision` < `revision` atual | rejeita com a `revision` atual |
| **Conflito** | stale + conteúdo incompatível | conflito explícito para resolução (T16.7) |

O `Outbox` só remove uma mutação depois da confirmação do servidor. Uma resposta perdida deixa a
mutação na fila, e o reenvio é seguro — é exatamente o que a idempotência garante.

---

## Idempotência

> A mesma mutação reenviada pelo cliente precisa ser reconhecida como a mesma mutação.

Cenário que **precisa** funcionar:

```text
cliente envia   →   servidor grava   →   resposta se perde   →   cliente reenvia
```

O resultado obrigatório é **um** registro. O resultado proibido:

- duas sessões de treino;
- duas medições corporais;
- duas recompensas;
- dois exercícios personalizados.

O servidor mantém um registro de `clientMutationId` já aplicados (por conta) e, ao reencontrar um,
devolve o resultado original em vez de reprocessar.

Isso não é novidade no projeto: a gamificação já resolve o mesmo problema localmente com
`dedupeKey` único em `gamification_events` e `eventId` único em `xp_transactions`. O protocolo
remoto segue o mesmo princípio.

---

## Pull (T16.6)

```text
GET /v1/sync/pull?cursor=<opaco>
      ↓
{ changes: [...], nextCursor: "<opaco>", hasMore: true|false }
      ↓
validação                 ← payload do servidor é entrada não confiável
      ↓
Room                      ← escrita local
      ↓
UI observa Room           ← a UI nunca lê a resposta HTTP diretamente
```

O `cursor` é derivado de `changeSeq`, que é **estado controlado pelo servidor** — nunca do relógio
do aparelho. Ele é opaco para o cliente: o Android guarda e devolve, sem interpretar.

Propriedades exigidas:

- **monotônico** — mudanças aparecem em ordem estável;
- **retomável** — perder conexão no meio não obriga a recomeçar;
- **completo** — nenhuma mudança entre dois cursores é pulada;
- **por conta** — nunca entrega dado de outro `ownerUid`.

---

## Histórico concluído

Esta regra é bloqueante e não pode ser enfraquecida por nenhuma fase da T16.

Uma `WorkoutSession` com status `COMPLETED` — e os `exercise_sessions` e `set_logs` que pertencem a
ela — registra **o que de fato aconteceu**. Não é um documento colaborativo.

```text
mesmo syncId + conteúdo histórico incompatível
      ↓
CONFLITO DE INTEGRIDADE
```

E **não**:

```text
mesmo syncId + conteúdo divergente → last write wins → o histórico do usuário é reescrito
```

Na prática, a partir da T16.6:

- uma sessão concluída chega ao servidor uma vez e vira imutável;
- um push que tente alterar o conteúdo de uma sessão concluída é rejeitado, não aplicado;
- divergência é reportada como conflito de integridade e exige decisão explícita, nunca resolução
  automática;
- a exceção legítima é o **tombstone**: o usuário pode apagar a própria sessão. Apagar não é
  reescrever.

Isso é a mesma invariante que o Coach IA já respeita hoje (`PROJECT_RULES` §13: "Sessão concluída é
imutável"). O sync não pode ser a porta dos fundos que ela não tem.

---

## Deletes e tombstones (T16.7)

Delete físico imediato não funciona em multi-device:

```text
aparelho A deleta o item
aparelho B está offline
servidor remove fisicamente
aparelho B reconecta com a cópia antiga
      ↓
o item ressuscita
```

O protocolo precisa suportar **tombstone**: a exclusão é uma mudança versionada como qualquer
outra, com `deletedAt`, e entra na sequência do servidor. O aparelho B recebe "isto foi apagado" em
vez de reenviar "isto existe".

Um tombstone participa de `revision` e de `changeSeq` normalmente; um push de UPDATE sobre uma
entidade com tombstone é conflito, não recriação silenciosa.

A política de retenção — por quanto tempo um tombstone é guardado antes da limpeza definitiva —
fica para a **T16.7**. Ela depende de uma decisão que ainda não foi tomada: quanto tempo um
dispositivo pode ficar offline e ainda convergir corretamente.

---

## Conflitos (T16.7)

| Tipo de entidade | Política prevista |
| --- | --- |
| Template, programa, exercício pessoal, customização | última `revision` vence, com histórico preservado no servidor |
| Sessão concluída e seus filhos | **imutável** — divergência é conflito de integridade, sem resolução automática |
| Medida corporal | mesma data com conteúdo divergente = conflito explícito |
| Tombstone vs. update | conflito — o delete não é desfeito silenciosamente |

Nenhuma dessas políticas está implementada.

---

## O que a T16.0 deixou pronto para isso

Nada do protocolo. O que existe é a **fundação** que permite implementá-lo sem retrabalho:

- versionamento de API configurado — um `@Controller('sync')` futuro responde em `/v1/sync`;
- migrations versionadas e transacionais, para o schema remoto nascer por fase;
- SQLite com `foreign_keys=ON`, para que as tabelas de sync possam recusar órfãos de verdade;
- envelope de erro e request ID, para que um conflito seja diagnosticável;
- fronteira de autenticação desenhada, para que ownership não seja retrofit.
