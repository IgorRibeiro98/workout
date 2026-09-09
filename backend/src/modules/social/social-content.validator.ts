import { WorkoutCheckInErrors } from './workout-checkin.errors';
import {
  MAX_CAPTION_LENGTH,
  MAX_COMMENT_LENGTH,
  MAX_TEXT_LINE_BREAKS,
  MIN_COMMENT_LENGTH,
} from './social-media.limits';

/**
 * A sanitização de texto social (T17.9 §9/§10/§77/§78/§79/§80).
 *
 * ## O princípio: isto é **texto**, e nada mais
 *
 * Uma legenda e um comentário são sequências de caracteres que uma pessoa escreveu para outra
 * pessoa ler. Eles não são HTML, não são Markdown, não são JavaScript e não são rich text (§9).
 * O servidor guarda e devolve exatamente o que sanitizou aqui, e o Android desenha com um `Text`
 * de Compose — que renderiza `String`, não marcação. Não existe `WebView`, `HtmlCompat.fromHtml`
 * nem `AnnotatedString` construída a partir do conteúdo em nenhum lugar do caminho.
 *
 * Por isso **não** há escaping aqui. Escapar seria a defesa de quem injeta o texto em um
 * documento HTML; injetar em HTML é o que este produto não faz. Escapar antes de armazenar
 * produziria `&amp;` visível para quem escreveu `&` — corromper o texto da pessoa para se
 * defender de um risco que não existe neste caminho.
 *
 * ## O que é recusado, e por quê
 *
 * - **caracteres de controle** (§9/§78): C0 e C1, exceto a quebra de linha. Um `NUL` no meio de
 *   uma string quebra consumidores que ainda tratam texto como C-string; o resto simplesmente não
 *   tem representação visual — é conteúdo que existe para enganar quem lê o texto cru;
 * - **formatação bidirecional e caracteres invisíveis** (§78): os override/embedding de Unicode
 *   (`U+202A`–`U+202E`, `U+2066`–`U+2069`) reordenam visualmente o que está escrito, e o
 *   zero-width (`U+200B`–`U+200D`, `U+FEFF`) esconde conteúdo dentro de um texto aparentemente
 *   inocente. Os dois servem para fazer um comentário parecer uma coisa e ser outra;
 * - **texto vazio depois de aparar**: uma legenda em branco não é uma legenda (a ausência tem uma
 *   representação só, `null`), e um comentário em branco não é um comentário.
 *
 * ## O que é normalizado
 *
 * - **NFC** (§9): "é" pode ser um code point ou dois. Sem normalizar, dois textos idênticos na
 *   tela teriam comprimentos diferentes e o limite de 280 dependeria do teclado de quem escreveu;
 * - **`CR LF` e `CR` viram `LF`**, e sequências de quebras colapsam. O teto de quebras (§78) vem
 *   depois: um comentário não pode ocupar a tela inteira de quem lê só por ter 299 delas;
 * - **espaço nas pontas** some (§9).
 *
 * ## O que **não** é interpretado (§10/§79/§80)
 *
 * `https://...` continua sendo caracteres — não vira link, não gera preview, não é clicável.
 * `@igor` é texto, não menção. `#treino` é texto, não hashtag. Nenhuma das três é detectada aqui,
 * e nenhuma tela do Spark as detecta: implementar detecção significaria decidir o que fazer com o
 * que foi detectado, e é aí que aparecem preview de link (que faria o servidor buscar uma URL que
 * um estranho escolheu) e notificação de menção (que a T17.9 não tem).
 */

/**
 * Os code points recusados em qualquer texto social.
 *
 * Escrito com escapes `\u` de propósito: um arquivo-fonte que contivesse os próprios caracteres de
 * controle que ele existe para recusar seria ilegível em diff, em revisão e em terminal — e a
 * primeira ferramenta que os normalizasse silenciosamente quebraria a regra sem que ninguém visse.
 */
const FORBIDDEN_CODE_POINTS = new RegExp(
  [
    '[',
    '\\u0000-\\u0008', // C0 antes do TAB
    '\\u000B-\\u000C', // VT e FF (LF fica de fora: ele é tratado como quebra de linha)
    '\\u000E-\\u001F', // resto do C0
    '\\u007F-\\u009F', // DEL e C1
    '\\u200B-\\u200D', // zero-width space/non-joiner/joiner
    '\\u202A-\\u202E', // embedding e override bidirecional
    '\\u2066-\\u2069', // isolate bidirecional
    '\\uFEFF', // zero-width no-break space (BOM no meio do texto)
    ']',
  ].join(''),
);

/**
 * TAB é recusado à parte, porque ele **parece** inofensivo e não é: em um texto de uma linha ele
 * produz alinhamento imprevisível em cada plataforma que renderiza. Vira espaço.
 */
function collapseTabs(value: string): string {
  return value.replace(/\t/g, ' ');
}

/** Normaliza quebras de linha e colapsa sequências, preservando parágrafos simples. */
function normalizeLineBreaks(value: string): string {
  return value.replace(/\r\n?/g, '\n').replace(/\n{3,}/g, '\n\n');
}

function countLineBreaks(value: string): number {
  let count = 0;
  for (const char of value) {
    if (char === '\n') count += 1;
  }
  return count;
}

/**
 * O comprimento que o usuário enxerga.
 *
 * `Array.from` conta **code points**, e não unidades UTF-16: um emoji fora do BMP ocupa dois
 * `char` em JavaScript, e `"\u{1F4AA}".length === 2`. Contar por `.length` faria o limite de 280
 * valer 140 emojis — um limite diferente do que a tela promete, e diferente do que o Android
 * conta ao desabilitar o botão.
 */
export function visibleLength(value: string): number {
  return Array.from(value).length;
}

/** O núcleo compartilhado: normaliza, recusa o que não é texto, e devolve a forma canônica. */
function sanitize(raw: string, field: string): string {
  const normalized = collapseTabs(normalizeLineBreaks(raw.normalize('NFC'))).trim();

  if (FORBIDDEN_CODE_POINTS.test(normalized)) {
    throw WorkoutCheckInErrors.invalidContent(
      `${field} contém caracteres que não são texto visível`,
    );
  }
  if (countLineBreaks(normalized) > MAX_TEXT_LINE_BREAKS) {
    throw WorkoutCheckInErrors.invalidContent(`${field} tem quebras de linha demais`);
  }
  return normalized;
}

/**
 * A legenda de um check-in (§7).
 *
 * `undefined` e `null` significam a mesma coisa — sem legenda —, e uma string que sobra vazia
 * depois de aparar também: `"  "` não é uma legenda, e guardá-la como `''` daria duas
 * representações para o mesmo estado. `null` é a única.
 */
export function parseCaption(value: unknown): string | null {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value !== 'string') {
    throw WorkoutCheckInErrors.invalidContent('caption precisa ser texto');
  }
  const sanitized = sanitize(value, 'caption');
  if (sanitized.length === 0) {
    return null;
  }
  if (visibleLength(sanitized) > MAX_CAPTION_LENGTH) {
    throw WorkoutCheckInErrors.invalidContent(
      `caption é maior que ${MAX_CAPTION_LENGTH} caracteres`,
    );
  }
  return sanitized;
}

/**
 * O corpo de um comentário (§76).
 *
 * Diferente da legenda em uma coisa: aqui o vazio é **erro**, e não ausência. Um comentário existe
 * porque alguém quis dizer algo; um comentário em branco só poderia ter vindo de um cliente com
 * defeito ou de um toque acidental, e criá-lo colocaria uma linha vazia na publicação de outra
 * pessoa.
 */
export function parseCommentBody(value: unknown): string {
  if (typeof value !== 'string') {
    throw WorkoutCheckInErrors.invalidContent('body precisa ser texto');
  }
  const sanitized = sanitize(value, 'body');
  const length = visibleLength(sanitized);
  if (length < MIN_COMMENT_LENGTH) {
    throw WorkoutCheckInErrors.invalidContent('o comentário não pode ser vazio');
  }
  if (length > MAX_COMMENT_LENGTH) {
    throw WorkoutCheckInErrors.invalidContent(
      `o comentário é maior que ${MAX_COMMENT_LENGTH} caracteres`,
    );
  }
  return sanitized;
}
