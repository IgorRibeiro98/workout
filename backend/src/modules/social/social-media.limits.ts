import type { RateLimitPolicy } from '../../common/rate-limiter';

/**
 * Os limites do conteúdo social (T17.9 §159).
 *
 * **Um lugar só.** A regra existe porque a alternativa é o que sempre acontece: `280` escrito num
 * validador, `280` repetido num teste, `300` num DTO e `1600` dentro de uma chamada do sharp — e
 * no dia em que um deles mudar, os outros ficam para trás em silêncio. Aqui eles são valores
 * nomeados, importados por quem valida, por quem processa e por quem testa.
 *
 * O que **não** mora aqui é o que depende do deploy: raiz do armazenamento, teto de upload e quota
 * por conta são configuração (`SOCIAL_MEDIA_*` em `env.schema.ts`), porque mudam com o disco da
 * VPS e não com o produto.
 */

// ------------------------------------------------------------------ texto (§7/§9/§76/§78)

/** Legenda: `0..280` caracteres depois de normalizar e aparar (§7). */
export const MAX_CAPTION_LENGTH = 280;

/** Comentário: `1..300` caracteres depois de aparar (§76). Vazio não é comentário. */
export const MIN_COMMENT_LENGTH = 1;
export const MAX_COMMENT_LENGTH = 300;

/**
 * Quantas quebras de linha um texto pode conter (§78).
 *
 * Bounded de propósito: sem teto, um comentário de 300 caracteres pode ser 299 quebras de linha —
 * tecnicamente dentro do limite e visualmente um comentário que ocupa a tela inteira de quem lê.
 * Quatro cobrem uma resposta em parágrafos curtos.
 */
export const MAX_TEXT_LINE_BREAKS = 4;

// ------------------------------------------------------------------ imagem (§13/§18/§19/§20)

/** O que o servidor aceita **decodificar**. O `Content-Type` do cliente não decide nada (§14). */
export const ACCEPTED_IMAGE_FORMATS = ['jpeg', 'png', 'webp'] as const;
export type AcceptedImageFormat = (typeof ACCEPTED_IMAGE_FORMATS)[number];

/**
 * Teto de pixels decodificados (§20).
 *
 * A bomba de descompressão é o ataque que o teto de bytes **não** pega: um PNG de 40 KB pode
 * declarar 60000×60000 e custar 14 GB de RAM ao ser decodificado. 20 MP cobre com folga qualquer
 * câmera de celular (um sensor de 48 MP entrega 12 MP no modo padrão) e recusa a bomba antes de
 * qualquer alocação — `sharp` aplica `limitInputPixels` no header, não depois de decodificar.
 */
export const MAX_DECODED_PIXELS = 20_000_000;

/** Nenhuma dimensão isolada pode ser absurda, mesmo com a contagem total dentro do teto. */
export const MAX_INPUT_EDGE_PX = 20_000;

/** A maior aresta da imagem publicada (§18). Ela nunca é ampliada — só reduzida. */
export const MAX_OUTPUT_EDGE_PX = 1600;

/** Teto do arquivo processado (§19). */
export const MAX_OUTPUT_BYTES = 1_500_000;

/**
 * As qualidades tentadas, em ordem, até o resultado caber em [MAX_OUTPUT_BYTES] (§19).
 *
 * Degraus, e não uma busca binária: a diferença visual entre 82 e 78 é imperceptível, e a busca
 * custaria mais re-encodes para chegar ao mesmo lugar. Se nem a última couber, o upload é
 * recusado — publicar uma imagem acima do teto seria o teto não existir.
 */
export const OUTPUT_QUALITY_STEPS = [82, 72, 62, 50] as const;

/** O formato único de saída (§18). Uma representação, um decodificador do outro lado. */
export const OUTPUT_MIME_TYPE = 'image/webp';
export const OUTPUT_EXTENSION = 'webp';

// ------------------------------------------------------------------ ciclo de vida (§38)

/** Quanto tempo uma mídia `PENDING` sobrevive sem ser anexada a um check-in (§38). */
export const MEDIA_PENDING_TTL_MS = 60 * 60 * 1000;

/** Quantas linhas cada varredura de limpeza processa (§39/§140). Bounded, sempre. */
export const MEDIA_CLEANUP_BATCH = 200;

// ------------------------------------------------------------------ leitura (§90)

/** Comentários por página, quando o cliente não pede um número (§90). */
export const COMMENTS_DEFAULT_LIMIT = 30;

/** O teto absoluto de comentários por página, mesmo quando o cliente pede mais (§90). */
export const COMMENTS_MAX_LIMIT = 100;

// ------------------------------------------------------------------ tetos (§155/§156/§157)

/**
 * Os limitadores desta fase, por `uid` autenticado — **nunca** por IP.
 *
 * Em rede móvel e atrás de NAT o IP é compartilhado por gente que não tem nada a ver com o abuso,
 * e o Caddy à frente faria todo mundo parecer o mesmo cliente. A regra é a mesma desde a T16.8.
 *
 * O upload é o mais restritivo porque é o único que gasta **disco** e CPU de decodificação: cada
 * requisição custa uma decodificação de até 20 MP. Comentário e reação custam uma linha.
 */
export const SOCIAL_CONTENT_RATE_LIMIT: {
  readonly upload: RateLimitPolicy;
  readonly comment: RateLimitPolicy;
  readonly reaction: RateLimitPolicy;
} = {
  // §157/§31 — 20 por hora. Um check-in aceita uma foto, e ninguém conclui 20 treinos por hora:
  // o teto existe para conter laço de cliente e uso do endpoint como armazenamento genérico.
  upload: { windowMs: 60 * 60 * 1000, maxRequestsPerWindow: 20 },
  // §155/§158 — 30 por 10 minutos. Uma conversa normal em vários posts cabe folgadamente; um
  // laço não.
  comment: { windowMs: 10 * 60 * 1000, maxRequestsPerWindow: 30 },
  // §156 — leve de propósito: trocar de reação é reversível e barato, e um teto apertado
  // transformaria indecisão em erro na tela.
  reaction: { windowMs: 60 * 1000, maxRequestsPerWindow: 60 },
};

/**
 * Quantos comentários uma conta pode deixar **no mesmo check-in** dentro da janela (§158).
 *
 * Separado do teto global porque descreve outra coisa: 30 comentários espalhados por dez
 * publicações é conversa; 30 no mesmo post é enxurrada na publicação de uma pessoa só.
 */
export const MAX_COMMENTS_PER_CHECKIN_PER_WINDOW = 10;
export const COMMENT_FLOOD_WINDOW_MS = 10 * 60 * 1000;
