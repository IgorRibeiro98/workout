/**
 * Os limites do desafio (T17.3).
 *
 * Um arquivo, como `social.limits.ts`: "quantos dias pode durar" e "quantas pessoas cabem"
 * precisam ser decisões localizáveis, e não números repetidos entre validador, serviço, teste e
 * cliente.
 *
 * Todos são aplicados **no servidor** (§166). A UI os respeita para não oferecer o que será
 * recusado, mas quem decide é aqui — um app desatualizado não amplia nenhum deles.
 */

/**
 * O nome do desafio (§22).
 *
 * 3 é o mínimo que ainda nomeia alguma coisa; 60 cabe em um cartão de lista sem virar três linhas.
 * Unicode é permitido — "12 treinos em setembro", acentos, emoji — e o que é rejeitado é
 * **controle**: quebra de linha, tab e caracteres de controle transformam uma linha de UI em
 * várias e são o vetor mais barato de falsificar layout numa tela que outras pessoas leem.
 *
 * A contagem é em **code points**, como `SOCIAL_DISPLAY_NAME`: `"🏋"` é um caractere para quem
 * digita e dois para `String.length`.
 */
export const CHALLENGE_NAME = {
  minLength: 3,
  maxLength: 60,
} as const;

/**
 * A duração, em dias de calendário inclusivos (§17).
 *
 * 1 permite "um dia de desafio" — que é um formato real entre amigos ("hoje todo mundo treina").
 * 90 é o teto: acima disso o desafio deixa de ser um acordo e vira um compromisso que ninguém
 * lembra de ter feito, e a janela de pontuação passa a cobrir mudanças de rotina inteiras.
 *
 * O teto também limita o custo da consulta de `ACTIVE_DAYS`, que monta uma faixa por dia
 * (`challenge-progress.source.ts`): 90 é pequeno para o SQLite e grande para o produto.
 */
export const CHALLENGE_DURATION_DAYS = {
  min: 1,
  max: 90,
} as const;

/**
 * A meta de `WORKOUTS_COMPLETED` (§20).
 *
 * 200 é folgado de propósito: em 90 dias, dois treinos por dia daria 180. O teto não existe para
 * julgar a meta de ninguém — existe para que um número absurdo (`2^53`) seja recusado antes de
 * virar uma barra de progresso sem fim e um `target` que nenhuma consulta alcança.
 *
 * A meta de `ACTIVE_DAYS` **não** está aqui: ela é limitada pela própria duração do desafio
 * (§21), porque pedir 40 dias ativos em 30 dias é uma meta impossível, e não uma meta ambiciosa.
 */
export const CHALLENGE_TARGET = {
  min: 1,
  maxWorkoutsCompleted: 200,
} as const;

/**
 * Quantas pessoas cabem, **incluindo o criador** (§30).
 *
 * 10 porque o produto é amigos e família (§30), não competição pública. O número decide também
 * quantos convites uma criação pode disparar de uma vez — e é por isso que ele é o teto do
 * `invitedSocialIds`, e não uma regra separada que poderia divergir dele.
 */
export const CHALLENGE_PARTICIPANTS = {
  /** O criador entra sozinho; o mínimo para **competir** é outro número (ver abaixo). */
  max: 10,
  /**
   * O mínimo para o desafio ser social (§29).
   *
   * Abaixo disso ele não finge competição: o status derivado é `VOID` (§28). Não é um erro de
   * criação — na criação ainda dá tempo de alguém aceitar —, é o que a janela encontra quando
   * começa.
   */
  minimumToCompete: 2,
} as const;

/**
 * Quantos desafios abertos (`OPEN`, ainda não encerrados) uma conta pode ter criado (§111).
 *
 * O objetivo é impedir laço e abuso, não racionar o produto: 20 desafios simultâneos já é mais do
 * que qualquer pessoa acompanha, e um bug em laço estoura isso em segundos — que é o que este
 * número existe para conter.
 */
export const CHALLENGE_MAX_OPEN_PER_CREATOR = 20;

/**
 * O teto do corpo de uma requisição de desafio.
 *
 * O maior corpo possível é uma criação com nome, tipo, meta, datas, fuso e nove `socialId` —
 * menos de 700 bytes. 8 KiB dá folga e recusa por tamanho **antes** de qualquer validação de
 * conteúdo, como `MAX_SOCIAL_REQUEST_BODY_BYTES` faz para as rotas da T17.0.
 */
export const MAX_CHALLENGE_REQUEST_BODY_BYTES = 8 * 1024;

/**
 * O teto do identificador IANA do desafio.
 *
 * Mesmo raciocínio de `MAX_SOCIAL_WEEK_TIME_ZONE_LENGTH`: recusa entrada absurda antes de ela
 * virar um `Intl.DateTimeFormat`, que é onde a validação de verdade acontece — contra o runtime, e
 * não contra uma lista mantida à mão.
 */
export const MAX_CHALLENGE_TIME_ZONE_LENGTH = 64;

/**
 * O teto próprio da **criação**, por conta autenticada (§108/§109).
 *
 * O teto geral do `BearerAuthGuard` (600/min) não serve: 600 criações por minuto são 600 desafios
 * e até 5.400 convites disparados para amigos que não pediram nada. Criar é a operação mais cara
 * do módulo em consequência social, e é a que merece o menor número.
 *
 * 5 por minuto cobre qualquer uso legítimo — criar um desafio envolve escolher tipo, meta, datas e
 * amigos — e contém o laço em segundos.
 *
 * As respostas (aceitar, recusar, sair, cancelar) têm um teto mais alto e compartilhado: elas são
 * idempotentes e não geram notificação para terceiros, então o risco é o toque repetido, não o
 * abuso. A chave é sempre o `uid` autenticado, **nunca** o IP (§110).
 */
export const CHALLENGE_RATE_LIMIT = {
  create: { windowMs: 60_000, maxRequestsPerWindow: 5 },
  respond: { windowMs: 60_000, maxRequestsPerWindow: 30 },
} as const;

/** Paginação das listas de desafio e de convite (§105/§106). Mesma forma de `SOCIAL_LIST_PAGE`. */
export const CHALLENGE_LIST_PAGE = {
  defaultLimit: 30,
  maxLimit: 100,
} as const;
