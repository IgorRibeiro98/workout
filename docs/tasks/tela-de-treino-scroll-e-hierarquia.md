# Tela de treino: scroll, hierarquia de ações e esforço em palavras

**Status:** especificado · **Área:** `presentation/execution` · **Data:** 2026-09-02

## Problema

Com a fonte do sistema aumentada (ou "tamanho da tela" grande no Xiaomi/Samsung),
botões da tela de treino desaparecem. O caso reportado é o **CONCLUIR SÉRIE** sumindo
na parte de baixo.

Causa raiz, confirmada no código — são **dois** problemas distintos:

1. `FocusedActiveSetView` era um `Column(fillMaxSize, verticalArrangement = SpaceBetween)`
   **sem scroll**. O conteúdo que não cabe é simplesmente cortado.
2. Vários controles têm altura fixa em `dp` com texto em `sp`:
   `WorkoutActionButton` é `.height(60.dp)` com texto `18.sp`, e a roleta usa
   `itemHeight = 44.dp` com o número selecionado em `30.sp`. Altura em `dp` não cresce
   com a fonte, então esses componentes **não empurram nada — eles cortam o texto por
   dentro**. Adicionar scroll não resolve este segundo caso.

Problemas adjacentes encontrados no mesmo fluxo:

- A "Dica do Treinador" renderiza **JSON cru** (`{"mistake":...,"reason":...}`):
  `parseJsonListFirst` faz `array.getString(0)` num array de objetos do manifesto v2.
- `ExerciseMistakesCard` lê a chave `"why"`, mas o v2 grava `"reason"` — o "Motivo"
  nunca aparece.
- `"EXERCÍCIO 1 DE 6"` e `"⚡ Ordem adaptada"` aparecem duas vezes (topBar + header),
  com **a mesma ação** (`WorkoutSheet.ExercisesList`).
- Músculo/equipamento/dificuldade aparecem duas vezes (chips sob o nome + card de mídia).
- Existiam dois `RirSelector`; o de `components/workout/execution/` recebia `Float?`
  enquanto `rir` é `Int?` — nunca foi chamado.

## Decisões

Cada linha foi decidida explicitamente. A coluna "por quê" registra o motivo, para que
uma futura mudança saiba o que está desfazendo.

| # | Decisão | Por quê |
|---|---|---|
| 1 | Conteúdo rola; controles da série ficam **ancorados** abaixo | O CTA é acionado 3–5× por exercício; ele nunca pode depender da posição do scroll |
| 2 | Zona ancorada = roletas + esforço + CTA | Tudo que se toca a cada série mora junto e sempre visível |
| 3 | **Manter a roleta**, sem botões `+`/`−` | Decisão do produto; a roleta fica na zona ancorada, onde nunca disputa gesto com o scroll |
| 4 | Dica do Treinador é a informação secundária | Ocupava o miolo da tela com conteúdo que o sheet mostra melhor |
| 5 | Preferência **global** de mostrar/ocultar a Dica | Quem lê coaching quer sempre; quem não lê nunca quer. É preferência, não estado de tela |
| 6 | Alvo: `fontScale` 1.0–2.0, telas ≥ 320dp | Sem alvo verificável a regressão volta na próxima feature |
| 7 | Corrigir o JSON cru no mesmo lote | Recolher o card sem corrigir só esconde o bug; ele reaparece expandido |
| 8 | Esforço em palavras, **não** nova escala | `rir` continua `Int?`; histórico antigo continua comparável; sem migração no Room |
| 9 | `RirFormatter.formatEffort()` propagado a tudo | Sem isso o app oferece "Intenso" e devolve "RIR 2" |
| 10 | Ordem invertida: `🔥 Falha · Muito intenso · Intenso · Moderado · Leve` | Falha **é** o extremo (RIR 0); invertida, a escala segue monotônica |
| 11 | Linha de esforço rolável na horizontal | 5 palavras não cabem lado a lado em fontScale 2.0 |
| 12 | 5ª célula parcialmente visível + fade + auto-scroll | Sem affordance, ninguém descobre o gesto e o histórico ganha viés (ninguém marca "Leve") |
| 13 | `HorizontalPager` no lugar de `detectHorizontalDragGestures` | `pointerInput` cru **não** negocia com scrollers filhos; a linha de esforço disputaria o gesto e trocaria de exercício no meio da série |
| 14 | Remover chips e pill duplicados; ⓘ e ↗ para a topBar | A topBar não disputa altura com o conteúdo em fonte grande |
| 15 | Badge "SÉRIE 1 DE 3" absorve "Ver todas as séries" | Mesma informação em dois lugares; devolve ~48dp à âncora |

### Explicitamente rejeitado

- **Reduzir `fontSize` quando `fontScale > 1.5`** — seria o app decidindo ignorar a
  preferência de acessibilidade do sistema, que é exatamente o problema original.
- **Roleta dentro da área rolável** — o `NumberWheelPicker` é um `LazyColumn`; um dedo
  nela consumiria o gesto de scroll e **mudaria a carga registrada em silêncio**.
  Contaminar o histórico é o pior desfecho possível num app de treino.
- **Remover o ⓘ confiando na Dica como porta de entrada** — com a preferência da
  decisão 5 desligada, o quick info ficaria sem nenhum acesso na tela de treino.
- **Estado da Dica por exercício** — dívida de dados para uma preferência de UI.
- **"Aquecimento" como rótulo de RIR 4+** — aquecimento é *tipo de série*, não esforço.
  Uma série de aquecimento pode ser pesada e uma série válida pode ser leve. Usa-se
  **"Leve"**.

## Especificação

### Área rolável (`verticalScroll`, `weight(1f)`)

1. Nome do exercício (`maxLines = 3`)
2. Card de mídia — passa a ser o **único** lugar com músculo/equipamento/dificuldade
3. Badge `SÉRIE n DE m (concluídas/total)`, clicável → lista de séries
4. `Último: 20kg×12 · …`, clicável → último treino
5. Linha da Dica do Treinador (se a preferência estiver ligada) → abre o quick-info sheet

### Zona ancorada (irmão sem `weight`, sempre visível)

6. Roleta Carga + Roleta Reps
7. Esforço: `🔥 Falha · Muito intenso · Intenso · Moderado · Leve`
8. `CONCLUIR SÉRIE`

### TopAppBar

`←` · `PEITO E COSTAS / Exercício n de m ▾` · `ⓘ` · `↗` · `⋮`

O `⋮` já continha Sincronizar, Ver todas as séries e Último treino — nada a adicionar,
apenas remover os atalhos inline.

### Regra adaptativa

`fontScale ≥ 1.5` **ou** altura útil `< 500dp` → o seletor de esforço **sai da âncora**
e vai para o fim da área rolável. Ficam ancorados apenas roletas + CTA.

Motivo: a âncora custa uma fatia fixa da tela. Passando desses limiares, a fatia
engoliria a área rolável inteira. O esforço é preenchido **uma vez por série**, não
tocado constantemente — é o candidato certo a ceder.

### Transversal

- `.height(…)` → `.heightIn(min = …)` em CTA, botões de resumo/descanso, quick-info e
  input direto.
- `NumberWheelPicker.itemHeight` derivado de `LocalDensity.fontScale`
  (`WheelPickerDefaults.itemHeightFor`).
- `RirFormatter.formatEffort(rir, short)` como fonte única, usado também pelo histórico
  da tela de treino e pelo `ExerciseDetailsScreen`.
- `parseJsonListFirst` aceita array de strings (v1) e de objetos (v2).
- Preferência `show_coach_tip` no `SettingsManager` (DataStore), default `true`,
  toggle na seção "Multimídia & Demonstrações" dos Ajustes.
- Arquivos removidos: `components/workout/execution/RirSelector.kt` (órfão),
  `components/workout/execution/ExerciseExecutionHeader.kt` (sobrou só o nome, inlined).

## Definição de pronto

- `CONCLUIR SÉRIE` visível e clicável em `fontScale` 1.0 **e** 2.0, em tela de 320dp.
- O botão **cresce** com a fonte em vez de cortar o rótulo.
- Nenhum texto de conteúdo premium renderiza JSON.
- Seletor de esforço mostra palavras; histórico mostra as mesmas palavras.
- Teste Robolectric/Roborazzi cobrindo o primeiro e o segundo item
  (`testOptions.unitTests.isIncludeAndroidResources` já está ligado).

## Riscos e trade-offs aceitos

- **Card de mídia mantido** (decisão do produto). Em `fontScale` 2.0 numa tela de 320dp
  a área rolável fica próxima de zero: a Dica existe, mas exige scroll. Se o número
  medido incomodar, o card de mídia é o primeiro candidato a sair, em commit separado.
- **`"Exercício n de m"` continua duplicado** entre topBar e conteúdo (decisão do
  produto). As duas abrem a mesma lista.
- **Falha na 1ª posição** é o alvo mais fácil do polegar e é o valor mais extremo.
  Sem diálogo de confirmação (campo por série, seria insuportável); a diferenciação é
  o vermelho que o componente já aplica.
- **Páginas adjacentes do pager** mostram só o nome do exercício: séries, histórico e
  conteúdo premium são carregados apenas para o exercício em jogo.