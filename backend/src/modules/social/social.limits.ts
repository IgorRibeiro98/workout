/**
 * Os limites e o formato do domínio social (T17.0).
 *
 * Um arquivo, como `sync.limits.ts` e `backup.limits.ts`: "o que é um nome social válido" e "de
 * que tamanho é um código de amigo" precisam ser decisões localizáveis, e não números repetidos
 * entre validador, gerador, teste e cliente.
 */

/**
 * O nome social.
 *
 * 2 é o mínimo que ainda é um nome; 40 cabe em uma linha de tela sem elipse na maioria dos
 * aparelhos. Unicode é permitido — "João", "Jonathas", nomes com acento, nomes não latinos — e o
 * que é rejeitado é **controle**, não alfabeto: quebra de linha, tab e caracteres de controle
 * transformam uma linha de UI em três e são o vetor mais barato de falsificar layout.
 *
 * A contagem é em **code points**, e não em unidades UTF-16: `"👋"` é um caractere para quem
 * digita, e dois para `String.length`. Contar unidades faria o limite significar coisas
 * diferentes dependendo do alfabeto.
 */
export const SOCIAL_DISPLAY_NAME = {
  minLength: 2,
  maxLength: 40,
} as const;

/**
 * O alfabeto do `friendCode`.
 *
 * Sem `0`, `O`, `1`, `I` e `L`: um código é lido em voz alta, digitado de uma foto e copiado de
 * um bilhete, e cada par ambíguo vira um convite que não funciona. 31 símbolos.
 *
 * Nenhum mapeamento de confusão é feito na normalização (`O` não vira `0`): como nenhum dos dois
 * pertence ao alfabeto, um código com eles é simplesmente inválido — o que é uma resposta melhor
 * do que adivinhar qual das duas leituras a pessoa quis.
 */
export const FRIEND_CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

/** O prefixo fixo. Ele não carrega entropia: existe para o código ser reconhecível como do Spark. */
export const FRIEND_CODE_PREFIX = 'SPK';

/** Separador da forma canônica: `SPK-7K2P9D8Q`. A normalização aceita a entrada sem ele. */
export const FRIEND_CODE_SEPARATOR = '-';

/**
 * Símbolos aleatórios do código.
 *
 * 31^8 ≈ 8,5 × 10^11. Com o teto geral de 600 requisições por minuto por conta autenticada, varrer
 * 1% desse espaço levaria mais de 200 mil anos — e é isso, somado ao rate limit, que torna
 * enumeração massiva impraticável. Um código de 4 dígitos (10^4) seria varrido em minutos.
 *
 * O código **não** é credencial: mesmo que alguém adivinhe um, o que ele ganha na T17.1 é a
 * capacidade de mandar um pedido de amizade, que o dono aceita ou recusa. A entropia existe para
 * que ninguém consiga colher todos os perfis do servidor, não para proteger uma sessão.
 */
export const FRIEND_CODE_RANDOM_LENGTH = 8;

/** O comprimento da forma canônica completa: `SPK` + `-` + 8. */
export const FRIEND_CODE_CANONICAL_LENGTH =
  FRIEND_CODE_PREFIX.length + FRIEND_CODE_SEPARATOR.length + FRIEND_CODE_RANDOM_LENGTH;

/**
 * Tentativas de gerar um `friendCode` livre antes de desistir.
 *
 * A colisão é decidida pela `UNIQUE` do banco, nunca por um `SELECT` anterior ao `INSERT` — entre
 * a consulta e a escrita cabe outra ativação. Com o espaço acima, a probabilidade de cinco
 * colisões seguidas é indistinguível de zero para qualquer volume que o Spark venha a ter; o
 * limite existe para que a falha impossível seja **um erro explícito** em vez de um laço infinito
 * segurando uma transação.
 *
 * Esgotar as tentativas responde `503 SOCIAL_UNAVAILABLE`: nunca `500` aleatório, e nunca um
 * perfil criado sem código.
 */
export const FRIEND_CODE_MAX_GENERATION_ATTEMPTS = 5;

/**
 * Teto do corpo de uma requisição social.
 *
 * As três rotas de escrita carregam um nome e três booleanos. O teto global do processo é o do
 * backup (4 MiB), que é a maior coisa que o Spark envia; aceitar 4 MiB para escrever um nome não
 * teria nenhuma razão, e o corpo é rejeitado por tamanho antes de qualquer validação de conteúdo.
 */
export const MAX_SOCIAL_REQUEST_BODY_BYTES = 4 * 1024;

/**
 * Os tetos próprios do grafo social, por conta autenticada (T17.1 §11–§14).
 *
 * O teto geral do `BearerAuthGuard` (600/min) existe para conter um cliente em laço. Ele não serve
 * aqui: **600 tentativas de código por minuto é uma varredura**, e o lookup é justamente a rota
 * que alguém tentaria varrer. Mesmo com 31^8 códigos tornando a colheita completa impraticável, um
 * teto próprio é o que faz o custo de tentar crescer com o número de tentativas.
 *
 * A chave é o `uid` autenticado, **nunca** o IP (§14): em rede móvel e atrás de NAT o IP é
 * compartilhado por gente que não tem nada a ver com o abuso, e o Caddy à frente faria todo mundo
 * parecer o mesmo cliente.
 *
 * Os valores são calibrados para uso real, não para uso teórico: adicionar um amigo é digitar um
 * código, conferir quem apareceu e enviar. Vinte lookups por minuto cobre erro de digitação, foto
 * borrada, QR lido de novo e a pessoa tentando o código do grupo inteiro na mesa do vestiário; 15
 * envios por minuto cobre qualquer sessão legítima de "adicionei todo mundo do treino". Um bug em
 * laço estoura os dois em segundos, que é o que eles existem para conter.
 *
 * Em memória, e sem Redis, como todo limitador deste servidor: um processo, uma VPS (ADR-0001).
 * Reiniciar zera as janelas, e isso é aceitável — o teto contém laço, não cobra cota.
 */
export const SOCIAL_FRIEND_RATE_LIMIT = {
  lookup: { windowMs: 60_000, maxRequestsPerWindow: 20 },
  sendRequest: { windowMs: 60_000, maxRequestsPerWindow: 15 },
} as const;

/**
 * Paginação das listas do grafo (§37/§103).
 *
 * Mesmo um app pequeno não devolve lista infinita: o dia em que alguém tiver 4 mil pedidos — por
 * bug ou por abuso — a resposta não pode ser um documento de 4 mil itens montado em memória.
 *
 * `defaultLimit` é o que a tela pede quando não pede nada; `maxLimit` é o teto que o servidor
 * aplica **silenciosamente** em vez de recusar, porque um cliente pedindo 500 quer a lista, não um
 * erro. Um `limit` fora de forma (texto, negativo, zero) é recusado — isso é defeito de cliente.
 */
export const SOCIAL_LIST_PAGE = {
  defaultLimit: 50,
  maxLimit: 100,
} as const;

/**
 * Teto do identificador IANA de fuso horário (T17.2).
 *
 * O mais longo em uso hoje tem menos de 40 caracteres (`America/Argentina/ComodRivadavia`, 32).
 * 64 dá folga para qualquer nome futuro e recusa entrada absurda **antes** de ela virar um
 * `Intl.DateTimeFormat` — que é onde a validação de verdade acontece, contra o próprio runtime, e
 * não contra uma lista mantida à mão que envelheceria a cada revisão do banco de fusos.
 */
export const MAX_SOCIAL_WEEK_TIME_ZONE_LENGTH = 64;
