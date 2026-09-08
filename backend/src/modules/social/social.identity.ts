import { randomInt, randomUUID } from 'node:crypto';
import {
  FRIEND_CODE_ALPHABET,
  FRIEND_CODE_PREFIX,
  FRIEND_CODE_RANDOM_LENGTH,
  FRIEND_CODE_SEPARATOR,
} from './social.limits';

/**
 * Os geradores da identidade social (T17.0).
 *
 * Um lugar só. `socialId` e `friendCode` nascem **no servidor**, de fonte criptográfica, e nenhum
 * dos dois deriva do Firebase UID, do e-mail ou do nome — nem por hash. Derivar permitiria a
 * qualquer pessoa confirmar um palpite ("o e-mail X tem o código Y?"), que é exatamente a
 * informação que estes identificadores existem para não carregar.
 */

/**
 * A identidade social estável de um perfil.
 *
 * `randomUUID()` do Node é UUID v4 sobre CSPRNG. Ele é **opaco**: não é sequencial, não ordena, e
 * não diz nada sobre quando o perfil foi criado nem sobre quantos existem — um id sequencial
 * vazaria as duas coisas de graça.
 *
 * Conhecer um `socialId` não concede permissão nenhuma. Toda autorização continua saindo do
 * Firebase ID Token verificado (`AuthenticatedPrincipal.uid`).
 */
export function generateSocialId(): string {
  return randomUUID();
}

/**
 * Um `friendCode` novo, na forma canônica `SPK-XXXXXXXX`.
 *
 * `randomInt` é o CSPRNG do Node com **rejeição de amostra** embutida: ele não faz
 * `random() % 31`, que enviesaria os primeiros símbolos do alfabeto. O viés não quebraria a
 * unicidade, mas reduziria a entropia real do código — e a entropia é a única coisa que sustenta
 * a resistência a enumeração.
 */
export function generateFriendCode(): string {
  let code = '';
  for (let i = 0; i < FRIEND_CODE_RANDOM_LENGTH; i += 1) {
    code += FRIEND_CODE_ALPHABET[randomInt(FRIEND_CODE_ALPHABET.length)];
  }
  return `${FRIEND_CODE_PREFIX}${FRIEND_CODE_SEPARATOR}${code}`;
}

/**
 * A **única** normalização de `friendCode` do Spark.
 *
 * A T17.0 só gera códigos; a T17.1 vai procurá-los. As duas passam por aqui — se cada lado
 * tivesse a sua, um código gravado de um jeito deixaria de ser encontrado pelo outro, e o defeito
 * apareceria como "o convite do meu amigo não funciona".
 *
 * O que ela aceita, e todas convergem para `SPK-7K2P9D8Q`:
 *
 * ```text
 * spk-7k2p9d8q   →  SPK-7K2P9D8Q
 * SPK7K2P9D8Q    →  SPK-7K2P9D8Q
 * SPK-7K2P9D8Q   →  SPK-7K2P9D8Q
 *  spk 7k2p9d8q  →  SPK-7K2P9D8Q
 * ```
 *
 * O que ela recusa (`null`): comprimento diferente, prefixo ausente ou errado, e qualquer símbolo
 * fora do alfabeto — inclusive os ambíguos (`0`, `O`, `1`, `I`, `L`), que não são "corrigidos"
 * para um vizinho porque adivinhar qual vizinho seria inventar o código de outra pessoa.
 *
 * `null` em vez de exceção: para o lookup da T17.1, um código malformado e um código inexistente
 * precisam ser **a mesma resposta** — distinguir os dois transformaria a rota num validador
 * gratuito de formato para quem estiver tentando enumerar.
 */
export function normalizeFriendCode(raw: string): string | null {
  if (typeof raw !== 'string') {
    return null;
  }

  // Maiúsculas em `en-US`, e não `toUpperCase()` dependente de locale: em turco, `i` vira `İ`, e
  // um servidor cuja normalização depende do locale do processo é um servidor que muda de
  // comportamento quando o container muda de imagem.
  const compact = raw.toLocaleUpperCase('en-US').replace(/[\s-]/g, '');

  if (compact.length !== FRIEND_CODE_PREFIX.length + FRIEND_CODE_RANDOM_LENGTH) {
    return null;
  }
  if (!compact.startsWith(FRIEND_CODE_PREFIX)) {
    return null;
  }

  const random = compact.slice(FRIEND_CODE_PREFIX.length);
  for (const symbol of random) {
    if (!FRIEND_CODE_ALPHABET.includes(symbol)) {
      return null;
    }
  }

  return `${FRIEND_CODE_PREFIX}${FRIEND_CODE_SEPARATOR}${random}`;
}

/**
 * O identificador **público** de um pedido de amizade (T17.1).
 *
 * UUID v4 sobre CSPRNG, como o `socialId`, e pelo mesmo motivo: ele é exposto ao aparelho para
 * aceitar/recusar/cancelar, e um `rowid` sequencial no lugar contaria de graça quantos pedidos
 * existem no servidor e convidaria a tentar o vizinho. Adivinhar não daria acesso — a autorização
 * é por participante, verificada em `FriendshipAccessPolicy` — mas um identificador que convida a
 * tentar é um identificador mal escolhido.
 *
 * Função própria, e não `generateSocialId()` reusado: os dois são `randomUUID()` hoje, e são
 * coisas diferentes. Compartilhar a função faria uma mudança em um deles alcançar o outro sem que
 * ninguém decidisse isso.
 */
export function generateFriendRequestId(): string {
  return randomUUID();
}
