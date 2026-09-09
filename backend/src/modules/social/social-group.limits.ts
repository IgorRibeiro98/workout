import type { RateLimitPolicy } from '../../common/rate-limiter';

/**
 * Os limites dos Squads (T17.11 §11/§18/§68/§125/§126).
 *
 * Um arquivo, como `social.limits.ts`, `challenge.limits.ts` e `workout-checkin.limits.ts`:
 * "quantas pessoas cabem" e "quantos Squads uma conta pode criar" precisam ser decisões
 * localizáveis, e não números repetidos entre validador, serviço, teste e cliente (§11).
 *
 * Todos são aplicados **no servidor**. A UI os respeita para não oferecer o que será recusado, mas
 * quem decide é aqui — um app desatualizado não amplia nenhum deles.
 */

/**
 * O nome do Squad (§8).
 *
 * 3 é o mínimo que ainda nomeia um grupo; 40 cabe em um cartão de lista sem virar três linhas — o
 * mesmo teto de `SOCIAL_DISPLAY_NAME`, e pela mesma razão: os dois aparecem lado a lado na tela de
 * quem recebe um convite.
 *
 * Unicode é permitido — "Os Monstros 💪", acentos, alfabeto não latino — e o que é rejeitado é
 * **controle**: quebra de linha, tab e marcas bidirecionais transformam uma linha de UI em várias
 * e são o vetor mais barato de falsificar layout numa tela que outras pessoas leem.
 *
 * A contagem é em **code points**: `"💪"` é um caractere para quem digita e dois para
 * `String.length`. Contar unidades UTF-16 faria o limite significar coisas diferentes dependendo
 * do teclado de quem escreveu.
 */
export const SOCIAL_GROUP_NAME = {
  minLength: 3,
  maxLength: 40,
} as const;

/**
 * Quantas pessoas cabem em um Squad, **incluindo o dono** (§11/§122).
 *
 * 20 porque o produto é um grupo de treino — a galera da academia, a família, o time —, e não uma
 * comunidade. O número também é o que mantém toda consulta desta fase bounded por construção
 * (§122): a lista de membros, a projeção do feed e a checagem de bloqueio por par nunca varrem
 * mais do que isto.
 */
export const SOCIAL_GROUP_MAX_MEMBERS = 20;

/**
 * Quantos Squads **ativos** uma conta pode ter criado (§18/§126).
 *
 * 5 porque criar um Squad dispara convites para pessoas que não pediram nada, e é a operação com
 * mais consequência social desta fase. Cinco grupos simultâneos já é mais do que qualquer pessoa
 * acompanha; um bug em laço estoura isso em segundos, que é o que este número existe para conter.
 *
 * O limite conta Squads `ACTIVE`: excluir um libera a vaga (§46).
 */
export const SOCIAL_GROUP_MAX_OWNED = 5;

/**
 * Em quantos Squads **ativos** uma conta pode participar (§18/§126).
 *
 * Maior que o de posse porque participar é consentir com um convite, e não criar consequência para
 * terceiros: a assimetria é deliberada. 20 é o mesmo número do tamanho de um Squad — não por
 * simetria estética, mas porque os dois respondem à mesma pergunta de escala: quantas relações de
 * grupo uma pessoa acompanha de verdade.
 */
export const SOCIAL_GROUP_MAX_MEMBERSHIPS = 20;

/**
 * Em quantos Squads o **mesmo** check-in pode ser compartilhado (§68).
 *
 * 5 para evitar que uma publicação seja empurrada para todos os grupos de uma vez — que é spam com
 * outro nome — e para manter bounded a consulta que decide visibilidade de mídia: ela precisa
 * saber se existe **algum** Squad compartilhado entre viewer e autor onde o check-in está.
 */
export const SOCIAL_GROUP_MAX_SHARES_PER_CHECKIN = 5;

/**
 * Quantos convites `PENDING` um Squad pode ter ao mesmo tempo (§126).
 *
 * Ele existe para que o teto de membros não seja contornável pela antessala: sem isto, o dono de
 * um Squad cheio poderia manter 500 convites abertos e o primeiro aceite de cada rodada entraria.
 * O número é o próprio teto de membros — se todos aceitarem, o Squad enche e nada mais entra.
 */
export const SOCIAL_GROUP_MAX_PENDING_INVITATIONS = SOCIAL_GROUP_MAX_MEMBERS;

/**
 * Por quanto tempo um convite fica de pé (§19).
 *
 * 14 dias: tempo suficiente para quem abriu o app uma vez na semana, e curto o bastante para que
 * um convite esquecido não vire uma porta aberta para dentro de um grupo que mudou de composição.
 * Depois disso ele é lido como `EXPIRED` — derivado, e não gravado (§21).
 */
export const SOCIAL_GROUP_INVITATION_TTL_MS = 14 * 24 * 60 * 60 * 1000;

/**
 * A janela e o tamanho de página do feed do Squad (§86/§87).
 *
 * Os mesmos números do Feed de amigos (`FEED_DEFAULT_LIMIT`/`FEED_MAX_LIMIT`/`FEED_WINDOW_MS`), e
 * de propósito: é a mesma publicação lida por outra audiência, e dois recortes diferentes fariam o
 * mesmo check-in aparecer em um lugar e sumir no outro sem que nada tivesse acontecido.
 *
 * A janela é medida sobre a data do **compartilhamento** (§84/§85), não sobre a da publicação.
 * Sem infinite feed, sem polling, sem WebSocket (§87/§88/§89).
 */
export const SOCIAL_GROUP_FEED = {
  defaultLimit: 20,
  maxLimit: 50,
  windowMs: 30 * 24 * 60 * 60 * 1000,
} as const;

/**
 * Os tetos próprios dos Squads, por conta autenticada (§125).
 *
 * Quatro, e separados porque contêm ameaças diferentes:
 *
 * - **create** é a operação mais cara em consequência: cada Squad novo é um contexto social que
 *   passa a existir para outras pessoas. O teto **real** de criação é de domínio
 *   ([SOCIAL_GROUP_MAX_OWNED], 5 Squads ativos), e este número precisa ficar acima dele de
 *   propósito: se os dois fossem iguais, quem criasse o quinto Squad e tentasse o sexto receberia
 *   `429` em vez de `GROUP_OWNED_LIMIT_REACHED` — uma mensagem que não explica nada e que some
 *   sozinha depois de um minuto, ensinando a pessoa a tentar de novo em vez de a entender o
 *   limite. 10/min deixa a regra de domínio falar e ainda para um laço em segundos;
 * - **invite** alcança terceiros que não pediram nada, e é o que gera push (§90). 30/min cobre
 *   "convidei o time inteiro" com folga — o Squad tem 20 vagas, e algumas recusas no meio do
 *   caminho não podem travar o dono — sem permitir enxurrada;
 * - **membership** é aceitar, recusar, cancelar, sair, remover e transferir. Todas idempotentes e
 *   sem alcance para quem não está no grupo: o risco é o toque repetido, não o abuso;
 * - **share** é trazer um check-in **próprio** para um grupo. O teto de domínio já é forte (5
 *   Squads por check-in, §68), e este contém o laço de cliente.
 *
 * A chave é sempre o `uid` autenticado, **nunca** o IP: em rede móvel e atrás de NAT o IP é
 * compartilhado por gente que não tem nada a ver com o abuso, e o Caddy à frente faria todo mundo
 * parecer o mesmo cliente.
 *
 * Em memória, e sem Redis, como todo limitador deste servidor: um processo, uma VPS (ADR-0001).
 * Reiniciar zera as janelas, e isso é aceitável — o teto contém laço, não cobra cota.
 */
export const SOCIAL_GROUP_RATE_LIMIT: {
  readonly create: RateLimitPolicy;
  readonly invite: RateLimitPolicy;
  readonly membership: RateLimitPolicy;
  readonly share: RateLimitPolicy;
} = {
  create: { windowMs: 60_000, maxRequestsPerWindow: 10 },
  invite: { windowMs: 60_000, maxRequestsPerWindow: 30 },
  membership: { windowMs: 60_000, maxRequestsPerWindow: 30 },
  share: { windowMs: 60_000, maxRequestsPerWindow: 20 },
};

/**
 * Teto do corpo de uma requisição de Squad, em bytes UTF-8.
 *
 * O maior corpo possível é uma criação com nome e `clientRequestId` — menos de 200 bytes. 2 KiB dá
 * folga e recusa por tamanho **antes** de qualquer validação de conteúdo, como
 * `MAX_SOCIAL_REQUEST_BODY_BYTES` faz para as rotas da T17.0.
 */
export const MAX_SOCIAL_GROUP_REQUEST_BODY_BYTES = 2 * 1024;

/**
 * Comprimento máximo de um identificador opaco aceito no corpo ou na rota.
 *
 * UUID tem 36; 100 dá folga para outro formato de identificador sem virar um campo de texto livre
 * por acidente. O mesmo número de `MAX_CHECKIN_IDENTIFIER_LENGTH`, pela mesma razão.
 */
export const MAX_SOCIAL_GROUP_IDENTIFIER_LENGTH = 100;

/** Paginação da lista de Squads e da lista de convites. Mesma forma de `SOCIAL_LIST_PAGE`. */
export const SOCIAL_GROUP_LIST_PAGE = {
  defaultLimit: 30,
  maxLimit: 100,
} as const;
