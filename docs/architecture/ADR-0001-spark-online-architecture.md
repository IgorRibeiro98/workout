# ADR-0001 — Spark Online Architecture

- **Status:** aceito
- **Data:** 2026-09-06
- **Tarefa:** T16.0 — Fundação do Spark Backend
- **Substitui:** nada. Complementa `ARCHITECTURE.md`, que continua sendo a autoridade sobre o app Android.

## Contexto

Até a T15 o Spark era inteiramente local-first: Room e DataStore são as autoridades, e a única
fronteira online era o Firebase AI Logic do Coach IA (T14), que é opcional e não participa de
nenhum caminho crítico.

A partir da T16 o Spark precisa de estado de conta que atravesse dispositivos: backup, restore,
sync e, mais tarde, features sociais. Isso exige uma fronteira online **de verdade** — e exige que
ela não destrua a propriedade que torna o app utilizável hoje.

## Decisão

Adotar três autoridades distintas, com responsabilidades que não se sobrepõem.

```text
                        FIREBASE
                        └── Auth  ──────────────┐
                                                │ Firebase ID Token  [T16.1]
                                                ▼
┌─────────────────────────┐          ┌─────────────────────────────────┐
│ Spark Android           │          │ Spark Backend / VPS             │
│                         │          │                                 │
│ UI                      │          │ API HTTPS  (/v1)                │
│ Domain                  │  futuro  │ Auth boundary       [T16.1]     │
│ Room  ◄── autoridade    │◄────────►│ Gemini Gateway      [T16.2]     │
│ DataStore   operacional │   sync   │ Sync                [T16.3+]    │
│ Outbox        [T16.3]   │          │ Backup [T16.4] / Restore [T16.5]│
│                         │          │ Social              [T17]       │
│ AiCoachGateway          │          │ SQLite                          │
└─────────────────────────┘          └─────────────────────────────────┘
```

### Autoridades

| Autoridade | Responsabilidade | O que ela **não** é |
| --- | --- | --- |
| **Android / Room + DataStore** | Autoridade operacional local. Treino, execução, histórico, templates, catálogo, gamificação, preferências. | Não é réplica do servidor. |
| **Spark Backend** | Estado remoto da conta e convergência entre dispositivos. | Não é a autoridade de execução. Não decide se um treino pode começar. |
| **Firebase** | Identidade e autenticação (`Firebase Auth`). | Não é banco de dados do Spark. Nada de Firestore, RTDB, Storage ou Cloud Functions. |
| **Gemini** | Serviço probabilístico consumido pelo Coach IA. | Nunca é autoridade de domínio. Saída do modelo é entrada não confiável. |

### Direção de fluxo obrigatória

O fluxo correto, quando o sync existir:

```text
ação do usuário → domínio → Room → (outbox) → sync → Spark Backend
Spark Backend → sync → validação → Room → UI observa Room
```

O fluxo proibido, em qualquer fase da T16:

```text
UI → API → servidor → "se o servidor responder, o app funciona"
```

A UI observa Room. Sempre. Um dado que chega do servidor entra pelo sync, é validado e é escrito
no Room; a UI reage à mudança do Room, não à resposta HTTP.

### Invariantes que a T16 inteira precisa preservar

1. **Conta é opcional.** Sem conta, o núcleo funciona por completo: treino, execução, histórico,
   templates, catálogo e gamificação. Nenhuma fase da T16 pode introduzir requisito de login para
   iniciar ou concluir um treino.
2. **Indisponibilidade não quebra o app.** Sem internet, sem VPS, sem Firebase e sem Gemini, o
   Spark continua funcionando exatamente como hoje.
3. **Histórico concluído é imutável.** Uma `WorkoutSession` `COMPLETED` não é sobrescrevível por
   sincronização. Conteúdo histórico divergente para o mesmo `syncId` é **conflito de integridade**,
   nunca *last write wins*.
4. **Uma autoridade por coisa.** O backend não vira segunda fonte operacional de verdade.
5. **Ownership é decidido pelo servidor.** O `uid` vem do token verificado, nunca do payload.

## Política de custo

O Spark é um app privado para um grupo pequeno. A infraestrutura é deliberadamente mínima e de
custo fixo:

```text
1 VPS + Docker Compose + 1 aplicação backend + 1 SQLite + Caddy (TLS)
```

Ficam **fora** por decisão, não por falta de tempo: Kubernetes, Redis, Kafka, RabbitMQ, PostgreSQL
gerenciado, Cloud SQL, RDS, Cloud Functions, Cloud Run, load balancer gerenciado, fila gerenciada,
object storage obrigatório, service mesh e microservices.

Se uma necessidade futura parecer exigir qualquer um desses itens, a regra é **documentar e parar**,
não introduzir.

### Monólito modular

O backend nasce como monólito modular: `auth`, `ai`, `sync`, `backup` e `social` são módulos dentro
do mesmo processo e do mesmo banco. Não existem `auth-service`, `sync-service` etc. como processos
separados — não há necessidade operacional que justifique o custo.

## Estado: implementado x planejado

### IMPLEMENTADO EM T16.0

- Fundação do backend (NestJS, monólito modular) em `backend/`.
- Configuração validada com fail-fast no startup.
- SQLite com arquivo persistente, `journal_mode=WAL`, `foreign_keys=ON`, `busy_timeout` centralizado.
- Migrations versionadas, transacionais e idempotentes.
- `GET /health/live` e `GET /health/ready`.
- Request ID, envelope de erro único, logging estruturado sem segredo.
- Graceful shutdown (SIGTERM/SIGINT).
- Dockerfile multi-stage não-root, Docker Compose com volume persistente, `Caddyfile.example`.
- Versionamento de API configurado: toda API de produto futura nasce sob `/v1`.
- Contratos arquiteturais documentados (este ADR, matriz de dados, identidade, protocolo de sync).

### IMPLEMENTADO EM T16.1

- Conta **opcional** no Android: Firebase Authentication + Sign in with Google via Credential
  Manager, atrás de `AuthGateway` / `FirebaseAuthGateway`.
- Estado de autenticação explícito e derivado do Firebase (`SignedOut`, `SigningIn`, `SignedIn`,
  `SigningOut`, `Error`); sessão existente é restaurada sozinha, sem abrir seletor de contas.
- Área de Conta Spark dentro do Perfil, com login por ação explícita e logout que limpa também o
  estado de credencial do Credential Manager.
- `AuthTokenProvider` + `SparkAuthInterceptor` + `SparkBackendClient`: Firebase ID Token obtido sob
  demanda, enviado como `Authorization: Bearer`, nunca persistido e nunca registrado em log.
- Backend: Firebase Admin SDK, `AuthTokenVerifier` / `FirebaseAuthTokenVerifier`, `BearerAuthGuard`,
  `AuthenticatedPrincipal` e `GET /v1/auth/me`.
- Credencial do Admin SDK por caminho externo (`GOOGLE_APPLICATION_CREDENTIALS`), fora do Git e
  fora da imagem. Sem ela, rota autenticada responde 503 — nunca 200 sem verificação.
- Testes offline dos dois lados: nenhum exige Firebase real, conta Google real, service account
  ou rede.

O que a T16.1 **não** fez, deliberadamente: nenhum dado pessoal é enviado ou baixado, entrar/sair
não tocam em Room ou DataStore, trocar de conta não reassocia nada, e não existe tabela de usuários
no servidor.

### IMPLEMENTADO EM T16.4

- **Adoção explícita** do conjunto de dados local por uma Conta Spark: `cloud_data_binding` no Room
  (`version = 32`). Login, sozinho, continua não adotando nada; trocar de conta não transfere.
- **Snapshot completo e autocontido** do estado pessoal, com registry fechado de nove agregados,
  identidades portáteis e `entitySchemaVersion` por agregado.
- **Tentativa durável e imutável** (`backup_attempts`): `clientBackupId` estável, payload congelado,
  corte da Outbox capturado na mesma transação.
- **`POST /v1/backups`** e **`GET /v1/backups/latest`**, com ownership derivado do token, validação
  integral antes de qualquer escrita, transação única para snapshot + itens, idempotência por
  `(ownerUid, clientBackupId)`, tetos de tamanho e retenção configurável.
- **Contrato compartilhado** em `contracts/backup/v1/`: README, JSON de fixtures e a forma canônica
  cujo SHA-256 os dois lados reproduzem — com teste em Kotlin e em TypeScript fixando os mesmos
  hashes.

O que a T16.4 **não** fez, deliberadamente: restore, download do conteúdo do backup, sync
incremental, pull, convergência multi-device, conflito, tombstone remoto, backup automático em
background e backup off-site da VPS.

### IMPLEMENTADO — T16.5 (restore seguro)

- **Leitura no servidor**: `GET /v1/backups` (metadata dos backups retidos daquela conta, na ordem
  do servidor), `GET /v1/backups/{id}` e `GET /v1/backups/{id}/content` (o snapshot canônico,
  verbatim). Ownership derivado do token; backup de outra conta é indistinguível de inexistente.
  As três são **read-only**: baixar não marca, não consome e não apaga o snapshot.
- **`backup_snapshots.payload`** (migration `0004`): o servidor passou a guardar o documento exato
  que `payload_hash` resume, porque remontá-lo a partir das colunas seria uma segunda
  canonicalização — capaz de divergir da primeira no ponto em que a divergência aparece como
  "backup corrompido" no aparelho de um usuário.
- **No Android**: download em streaming para arquivo privado, SHA-256 conferido contra a metadata,
  validação integral (versão, schema, semântica, referências), `RestorePlan`, preview com contagens
  reais, confirmação explícita (dupla quando há dado local), snapshot de segurança local,
  substituição transacional do dataset, vínculo com a conta no mesmo commit, Outbox zerada no
  commit, fase das preferências e recuperação determinística na abertura do app.
- **O contrato é o mesmo do backup.** Não existe um formato de restore ao lado do formato de
  backup: o documento validado é exatamente o que `BackupSnapshotBuilder` produziu.

O que a T16.5 **não** fez, deliberadamente: merge, sync incremental, push/pull, cursor,
convergência multi-device ao vivo, conflito, tombstone remoto, rebind de dataset entre contas,
mídia e backup automático.

### PLANEJADO — ainda **não** existe

- **T16.6** — Sync incremental multi-device.
- **T16.7** — Conflitos, deletes e consistência offline.
- **T16.8** — Hardening, segurança, backup do servidor e observabilidade.
- **T17** — Amigos, convites, desafios e social.

Nada acima está implementado. Sob `/v1` existem hoje `auth`, `ai` e `backups` — não existe
endpoint de sync, e há teste que garante isso.

## Backup dos dados do usuário ≠ backup do servidor

Duas coisas diferentes, e confundi-las daria uma falsa sensação de segurança:

```text
T16.4  protege contra perder o APARELHO
       o dado do usuário está no Spark Backend

T16.8  protege contra perder a VPS
       o SQLite do servidor está em outro lugar        ← NÃO EXISTE
```

Hoje, um `docker compose down -v` na VPS destrói os backups de todo mundo. Isso é uma pendência
registrada da fase de hardening, não um detalhe operacional esquecido — e a UI do app não promete
o contrário.

## Coach IA — migração prevista, não executada

O `FirebaseAiCoachGateway` (T14) **permanece funcional e inalterado**. Nem a T16.0 nem a T16.1
removeram, migraram ou tocaram em Firebase AI Logic, App Check ou configuração do Gemini. A T16.1
introduziu identidade de **usuário** (Firebase Auth), que é responsabilidade diferente do App Check
— este atesta o app, aquela identifica quem está usando.

A migração prevista para a **T16.2**:

```text
AiCoachGateway                     ← a interface não muda
├── FirebaseAiCoachGateway         ← atual, em uso
└── SparkBackendAiCoachGateway     ← T16.2
```

Quando a implementação HTTP estiver validada, `SparkBackendAiCoachGateway` vira o default; o
`FirebaseAiCoachGateway` só é removido depois disso, em uma tarefa própria. Não há big bang.

O motivo de a migração valer a pena: hoje a chave do Gemini e a política de uso vivem no cliente.
Com o backend no caminho, a cota, o rate limit e o prompt passam a ser controlados no servidor —
que é também onde o `uid` autenticado existe.

## Consequências

**Positivas**

- O app continua utilizável por completo sem backend, e isso é testável.
- Custo previsível e operação simples: um `docker compose up -d`.
- A fronteira de autenticação existe como desenho antes de existir como código, então a T16.1 não
  precisa reabrir decisões de arquitetura.
- O schema remoto nasce livre do formato do Room, que é otimizado para outra coisa.

**Negativas / custos aceitos**

- SQLite em uma VPS não escala horizontalmente. Aceito: o público é um grupo pequeno, e a alternativa
  contradiz a política de custo. Se isso mudar, é uma decisão nova e documentada.
- Dois esquemas (Room e servidor) evoluem em paralelo e exigem tradução explícita no sync. Esse é o
  preço de não espelhar o Room — e é menor que o preço de acoplar o schema remoto à execução local.
- Depender do Firebase Auth mantém uma dependência de terceiro na identidade. Aceito: implementar
  autenticação própria seria pior em segurança e em custo.

## Referências

- [`data-classification-matrix.md`](./data-classification-matrix.md) — o que sincroniza e o que não.
- [`identity-contract.md`](./identity-contract.md) — `localId`, `syncId`, `deviceId`, ownership.
- [`sync-protocol.md`](./sync-protocol.md) — push/pull, cursor, idempotência, conflitos, tombstones.
- [`../../backend/README.md`](../../backend/README.md) — como rodar o backend.
