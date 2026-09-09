import type { RateLimitPolicy } from '../../common/rate-limiter';

/**
 * Os limites dos check-ins de treino (T17.8).
 *
 * ## O teto do create existe, e é folgado de propósito (§117)
 *
 * A proteção real contra spam **não** é o rate limit: é a regra de domínio. Uma sessão canônica
 * concluída produz no máximo um check-in (§29), e a `UNIQUE (author_uid, source_session_sync_id)`
 * é quem garante isso. Alguém que quisesse encher o feed dos amigos precisaria de sessões de
 * treino canônicas sincronizadas de verdade, uma por publicação.
 *
 * O teto aqui contém a outra coisa: um **laço no cliente**. Uma tela que republica em `LaunchedEffect`
 * geraria centenas de requisições que o banco recusaria uma a uma — barato para o cliente, caro
 * para o servidor. 30 por hora é ordens de grandeza acima de qualquer uso real (ninguém conclui
 * 30 treinos por hora) e ainda assim para o laço.
 *
 * A chave é o `uid` autenticado, **nunca** o IP: em rede móvel e atrás de NAT o IP é compartilhado
 * por gente que não tem nada a ver com o abuso, e o Caddy à frente faria todo mundo parecer o
 * mesmo cliente.
 *
 * ## Por que a leitura do feed não tem teto próprio
 *
 * `GET /v1/social/feed` não enumera nada: ele devolve o que os amigos **atuais** publicaram, e a
 * lista de amigos é derivada do servidor — não há parâmetro que a amplie (§71). Não existe o que
 * varrer, e o teto geral de 600/min do `BearerAuthGuard` basta. Um limitador a mais sem uma ameaça
 * a conter seria um número para manter sem razão.
 */
export const WORKOUT_CHECKIN_RATE_LIMIT: { readonly create: RateLimitPolicy } = {
  create: {
    windowMs: 60 * 60 * 1000,
    maxRequestsPerWindow: 30,
  },
};

/**
 * Teto de tamanho do corpo do `POST`, em bytes UTF-8.
 *
 * O corpo tem duas strings curtas. O teto global do processo é o do backup (4 MiB), e aceitar
 * 4 MiB para escrever dois identificadores não teria razão nenhuma.
 */
export const MAX_CHECKIN_REQUEST_BODY_BYTES = 2 * 1024;

/**
 * Comprimento máximo de um identificador aceito no corpo.
 *
 * `sessionSyncId` é um UUID (36) e `clientRequestId` também deveria ser; 100 dá folga para outro
 * formato de identificador opaco sem virar um campo de texto livre por acidente.
 */
export const MAX_CHECKIN_IDENTIFIER_LENGTH = 100;
