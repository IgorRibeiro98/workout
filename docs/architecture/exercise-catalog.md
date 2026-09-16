# Catálogo de exercícios: modelo, CUSTOM, taxonomia visual e CRUD

> **Runtime verificado em 2026-09-16 (T19.7).** Este documento descreve o que existe no código.
> Quando o repositório divergir dele, o código corrigido mais recente vence — e este arquivo deve
> ser atualizado.

## 1. O que um `Exercise` é

A única linha de exercício do Spark é `ExerciseEntity` (Room, tabela `exercises`,
`app/src/main/java/com/example/data/local/Entities.kt`). Ela guarda **o que o exercício é**:

| Campo | Origem | Observação |
| --- | --- | --- |
| `name` | catálogo / usuário | **único campo obrigatório** (não anulável). Não é identidade. |
| `primaryMuscle` | catálogo v1 (33 valores) / usuário | decide a **cor** (§3). |
| `equipment` | catálogo v1 (24 valores) / usuário | decide o **ícone** (§3). |
| `description` | usuário (`CUSTOM`) | texto livre; no canônico, `shortDescription` vem do manifesto premium. |
| `secondaryMuscles`, `movementPattern`, `substitutionGroup`, `nameEn`, `aliases` | catálogo v1 | metadata canônica. |
| `category` (6), `exerciseType` (composto/isolador), `bodyRegion`, `difficulty`, `trainingGoals` | manifesto premium v2 | só nas 354 entradas premium; nulas nas outras 52 e em todo `CUSTOM`. |
| `isBodyweight`, `rirEnabled` | usuário / sync | o catálogo em `assets` não declara `isBodyweight` (ver `ResolvedExercise.isBodyweight`). |
| `active` | catálogo / usuário | `false` tira do catálogo e do seletor sem apagar a linha. |
| `canonicalId` | manifesto | identidade global do exercício **canônico**. |
| `syncId` | gerado localmente | identidade global do exercício **`CUSTOM`** (nulo no canônico — T16.3). |
| `isUserCreated` | — | o marcador de `CUSTOM`. `ExerciseResolver` força `false` quando há `canonicalId`. |

**Identidade:** `canonicalId` (canônico) ou `syncId` (`CUSTOM`). Nome, ícone, cor, rótulo de
categoria e posição na lista nunca são identidade. Mudar ícone ou cor não muda id nenhum.

O que a UI mostra é `ResolvedExercise` (`ExerciseResolver.resolve(entity, override)`): a linha do
catálogo mais o override do usuário (`exercise_user_overrides`: `displayName`, `notes`,
`customPhotoUri`, `defaultRestSeconds`) e a mídia resolvida.

## 2. O que um `Exercise` **não** é

`WorkoutTemplateExerciseEntity` (`workout_template_exercises`) é **como aquele exercício será
feito naquele treino**: `templateId`, `exerciseId`, `sortOrder`, `targetSets`, `minReps`,
`maxReps`, `restDurationSeconds`, `plannedWeight`, `machineLabel`, `notes`. Esses campos nunca
migram para `exercises`, e o CRUD de exercício não os expõe: o formulário de `CUSTOM` diz onde
eles ficam (o editor de treino, `TemplateDetailsScreen`).

```text
Exercise                         WorkoutTemplateExercise
Supino reto · Peitoral · Barra   4 séries · 8–12 reps · 90 s · 60 kg · "banco 3"
```

**Mistura pré-existente, documentada e mantida:** `ExerciseUserOverrideEntity.defaultRestSeconds`
("descanso padrão" na personalização) não é atributo do exercício nem configuração de um treino.
É um fallback por usuário, consumido por `WorkoutEngine.resolveRestRecommendation` **só quando a
execução não tem** `restDurationSecondsSnapshot` (o treino planejado sempre vence). A T19.7 não o
moveu: faz parte do backup (T16.4) e da personalização desde antes; o formulário passou a dizer
exatamente quando ele vale.

## 3. Taxonomia visual (T19.7A)

Uma regra, um lugar: `ExerciseVisualResolver` (`domain/engine/ExerciseVisualResolver.kt`).

```text
ExerciseEntity / ResolvedExercise
        │
        ▼
ExerciseVisualResolver.resolve(primaryMuscle, equipment, isBodyweight)
        │
        ├── icon  = EquipmentFamily(equipment)        ← "com que equipamento"
        └── color = MuscleGroup(primaryMuscle).color  ← "que músculo"
```

### Ícone = família de equipamento (`EquipmentFamily`)

| Família | Ícone Material | Valores do catálogo que caem nela |
| --- | --- | --- |
| `FREE_WEIGHT` "Peso livre" | `FitnessCenter` | Barra, Halteres, Kettlebell, Barra EZ, Trap bar, Anilhas, `Barra/Máquina`, `Banco/Halteres` |
| `MACHINE` "Máquina" | `AirlineSeatReclineNormal` | Máquina, Smith, `Máquina/Banco` |
| `CABLE` "Cabo ou elástico" | `Cable` | Cabo, Elástico, `Cabo/Elástico` |
| `BODYWEIGHT` "Peso corporal" | `Accessibility` | Peso corporal, `Peso corporal/Peso`, Barra fixa, Livre, Bola suíça, Roda abdominal, Banco, Banco 45°, Cadeira romana |
| `UNKNOWN` "Equipamento não informado" | `Category` (outlined) | equipamento vazio ou texto que nenhuma regra reconhece — **o fallback neutro** |

Regras de leitura (`resolveEquipmentFamily`): texto normalizado sem acento e sem caixa; valores
combinados com `/` são lidos na ordem escrita e o **primeiro termo que nomeia uma carga** decide;
apoios (banco, bola, roda, cadeira romana) só decidem quando não há carga — e então é peso
corporal. Dentro de um mesmo termo, a carga vence o apoio ("supino máquina no banco" é máquina).
`ExerciseEntity.isBodyweight = true` só desempata quando o texto não diz nada. Determinística: o
mesmo texto dá sempre a mesma família (`ExerciseVisualResolverTest` cobre todos os 24 valores
do catálogo).

### Cor = grupo muscular (`MuscleGroup`, via `MuscleVisualResolver`)

`MuscleVisualResolver.resolveGroup(primaryMuscle)` já existia e continua sendo a única regra de
cor — Peitoral azul, Costas esmeralda, Ombros âmbar, Quadríceps lima, Posterior coral, Glúteos
laranja, Panturrilhas roxo, Bíceps céu, Tríceps índigo, Core teal, Antebraço violeta, Trapézio
rosa, Cardio amarelo. `FULL_BODY` (cinza ardósia) é o **fallback neutro** para músculo ausente
ou não reconhecido. O grupo muscular **não tem ícone** desde a T19.7: um chip só de músculo (Hoje,
Histórico) usa um ponto da cor.

### Onde a regra se aplica

Catálogo (`ExercisesScreen`), editor de treino e seletor de exercícios (`TemplateDetailsScreen`),
detalhes (`ExerciseHeroCard`, `ExerciseDetailsScreen` e suas alternativas), execução
(`ExerciseMediaCompact`, troca de exercício em `ExecutionScreen`), pré-visualização da T19.6
(`ExercisePreviewSheet`, que reusa o `ExerciseHeroCard`). A legenda em palavras
(`ExerciseSemanticsRow`: "[ícone] Peso livre · [ponto] Peitoral · Catálogo Spark / Criado por
você") aparece nos detalhes e, ao vivo, nos formulários de `CUSTOM`.

Nada disso é persistido: **não existe coluna `icon` nem `color`**, e o catálogo não foi
reimportado para mudar aparência.

## 4. `CUSTOM` e o CRUD (T19.7C)

| | Canônico (`canonicalId`) | `CUSTOM` (`isUserCreated`, `syncId`) |
| --- | --- | --- |
| Origem | manifesto versionado em `assets/catalog` | criado pelo usuário, neste aparelho |
| Rótulo | "Catálogo Spark" | "Criado por você" |
| Editar a linha | **não** — `updateCustomExercise` devolve `false` sem escrever | sim: nome (obrigatório), músculo, equipamento, descrição — `WorkoutRepository.updateCustomExercise` |
| Personalizar por cima | sim: override (`displayName`, `notes`, `defaultRestSeconds`, foto) | foto continua; nome/descrição vivem na linha — ao editar, `displayName`/`notes` de um override antigo são limpos para a edição "pegar" |
| Excluir | **não** — `deleteExercise` devolve `NotCustom` | sim, com a regra abaixo |
| Import/update do catálogo | atualiza por `canonicalId` e `contentVersion` | **nunca tocado**: o importador procura por `canonicalId`, que um `CUSTOM` não tem, e respeita `isUserCreated` |
| Sync | não viaja (é conteúdo do APK) | agregado `CUSTOM_EXERCISE` (T16.3): name, primaryMuscle, equipment, description, isBodyweight, rirEnabled, active |

Validação: **só o nome é obrigatório** — a mesma regra na entidade (`name` não anulável), no
repositório (`CustomExerciseFields.requireName`), no ViewModel (`canSave`) e no botão do formulário.
Músculo, equipamento e descrição em branco viram `null`, nunca `""`.

### Excluir um `CUSTOM` (`WorkoutRepository.deleteExercise`)

```text
não é CUSTOM                       → NotCustom          (nada muda, nada vai para a Outbox)
usado em algum treino              → UsedByTemplates(n) (recusado; FK RESTRICT — mesmo guard do sync, T16.7)
tem histórico (exercise_sessions)  → Archived           (active = false; a linha fica; UPSERT do agregado)
sem referência nenhuma             → Deleted            (apagado; tombstone DELETE)
```

Por que arquivar em vez de apagar: `exercise_sessions` não tem chave estrangeira para
`exercises` (o histórico carrega `exerciseNameSnapshot`), mas `personal_records` cascateia — um
hard delete apagaria recordes de treinos já feitos. `active = false` some do catálogo e do seletor
(`getActiveExercises`), mas a tela de detalhes, o histórico e o Evolução continuam resolvendo o
exercício pelo id. Como `active` viaja no agregado, o outro aparelho arquiva também.

Não há "desarquivar" na UI; isso é uma decisão a tomar (ver §6).

### Double tap

Salvar fecha o formulário e o desarma na hora (`submitted`): o segundo toque não chega ao
repositório. Conta trocada durante um formulário aberto: o rascunho é estado de composição do
sheet e morre com ele — nada dele é persistido antes do toque em salvar.

## 5. Invariantes que a T19.7 preservou

- ids canônicos estáveis; `CUSTOM` mantém o `syncId`;
- import/update do catálogo continua idempotente e não toca em `CUSTOM`;
- `WorkoutSession` `COMPLETED` é imutável; editar/arquivar exercício não reescreve histórico;
- `WorkoutTemplateExercise` continua dono de séries/reps/descanso/carga;
- catálogo e taxonomia visual funcionam sem backend (tudo local);
- nenhuma migration de Room — a `AppDatabase` continua na mesma versão.

## 6. Pendências registradas

- Desarquivar um `CUSTOM` arquivado (não existe UI; a linha continua no Room com `active = 0`).
- `category`/`exerciseType`/`bodyRegion` do manifesto premium cobrem 354 das 406 entradas e
  nenhum `CUSTOM`; por isso não sustentam a taxonomia hoje. Se um dia cobrirem tudo, podem virar
  uma terceira dimensão — sem substituir as duas atuais.
- `ExerciseUserOverride.defaultRestSeconds` (§2) segue como fallback documentado.
