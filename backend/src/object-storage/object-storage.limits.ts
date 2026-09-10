/**
 * Os limites do Object Storage (T18.1). Um lugar só, pelo mesmo motivo de `social-media.limits.ts`
 * e `backup.limits.ts`: um número repetido em dois coletores diverge na primeira alteração.
 */

/**
 * O período de carência de um objeto órfão.
 *
 * Um objeto sem linha correspondente no PostgreSQL tem duas explicações: o processo morreu entre
 * gravar o objeto e o commit da metadata (ou a retenção/exclusão não conseguiu apagá-lo), **ou**
 * a operação que o criou ainda está em andamento — o upload gravou o objeto e a transação ainda
 * não commitou. As duas são indistinguíveis numa listagem, e a segunda é uma foto prestes a ser
 * publicada ou um backup prestes a ser confirmado.
 *
 * A carência é o que separa as duas: só objeto **mais antigo que isto** pode ser recolhido. Uma
 * requisição inteira dura no máximo `HTTP_REQUEST_TIMEOUT_MS` (2 min por default), então
 * qualquer valor acima de alguns minutos já seria seguro; 24 h cobre isso com folga, absorve
 * qualquer diferença de relógio entre o servidor e o provider, e custa nada — órfão é raro, e um
 * dia a mais de um objeto órfão no bucket é irrelevante.
 */
export const OBJECT_STORAGE_ORPHAN_GRACE_MS = 24 * 60 * 60 * 1000;

/**
 * Quantos objetos cada página de listagem traz.
 *
 * Bounded por página e não por varredura inteira: o coletor guarda o cursor entre varreduras e
 * percorre o prefixo em janela deslizante, para que um bucket com milhares de objetos nunca seja
 * carregado em memória de uma vez — e para que uma varredura nunca segure o event loop.
 */
export const OBJECT_STORAGE_LIST_PAGE_SIZE = 500;

/**
 * Quantas páginas uma varredura de órfãos examina antes de parar e devolver o cursor.
 *
 * Com [OBJECT_STORAGE_LIST_PAGE_SIZE], são no máximo 2 000 objetos por varredura — e no GCS
 * quatro operações de listagem. O restante fica para a próxima varredura, a partir do cursor.
 */
export const OBJECT_STORAGE_ORPHAN_SCAN_PAGES = 4;

/** O prefixo reservado ao smoke operacional. Nunca carrega dado real. */
export const OBJECT_STORAGE_SMOKE_PREFIX = '_smoke/';
