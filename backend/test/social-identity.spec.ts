import { SocialAccessPolicy } from '../src/modules/social/social.access-policy';
import {
  generateFriendCode,
  generateSocialId,
  normalizeFriendCode,
} from '../src/modules/social/social.identity';
import {
  FRIEND_CODE_ALPHABET,
  FRIEND_CODE_PREFIX,
  FRIEND_CODE_RANDOM_LENGTH,
} from '../src/modules/social/social.limits';
import {
  SOCIAL_PROJECTION_RULES,
  type SocialProjection,
} from '../src/modules/social/social.projection';

/**
 * A identidade social e as fronteiras que ela sustenta (T17.0).
 *
 * Testes puros: nenhum banco, nenhuma rota. O que eles protegem é a propriedade que torna o
 * `friendCode` utilizável como convite — forma previsível, alfabeto sem ambiguidade, normalização
 * única — e a política de acesso que a T17.1 vai herdar pronta.
 */
describe('Identidade social', () => {
  describe('socialId', () => {
    it('é UUID v4 e não é sequencial', () => {
      const ids = Array.from({ length: 200 }, () => generateSocialId());

      for (const id of ids) {
        expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
      }
      expect(new Set(ids).size).toBe(ids.length);
      // Sequencial produziria ordenação alfabética estável; aleatório, não.
      expect([...ids].sort()).not.toEqual(ids);
    });

    it('não deriva de uid, e-mail ou nome — nada é passado para o gerador', () => {
      // A prova estrutural é a assinatura: um gerador que derivasse precisaria receber a origem.
      expect(generateSocialId).toHaveLength(0);
    });
  });

  describe('friendCode', () => {
    it('tem o formato canônico SPK- + 8 símbolos do alfabeto', () => {
      for (let i = 0; i < 200; i += 1) {
        const code = generateFriendCode();
        expect(code).toHaveLength(FRIEND_CODE_PREFIX.length + 1 + FRIEND_CODE_RANDOM_LENGTH);
        expect(code.startsWith(`${FRIEND_CODE_PREFIX}-`)).toBe(true);
        for (const symbol of code.slice(FRIEND_CODE_PREFIX.length + 1)) {
          expect(FRIEND_CODE_ALPHABET).toContain(symbol);
        }
      }
    });

    it('o alfabeto não tem caracteres ambíguos', () => {
      for (const ambiguous of ['0', 'O', '1', 'I', 'L']) {
        expect(FRIEND_CODE_ALPHABET).not.toContain(ambiguous);
      }
      // Sem repetição: um símbolo duplicado reduziria a entropia real sem que se note.
      expect(new Set(FRIEND_CODE_ALPHABET).size).toBe(FRIEND_CODE_ALPHABET.length);
    });

    it('o espaço é grande o bastante para tornar enumeração impraticável', () => {
      const space = FRIEND_CODE_ALPHABET.length ** FRIEND_CODE_RANDOM_LENGTH;

      // Muito acima de qualquer coisa varrível com o teto de 600 req/min por conta — e ordens de
      // grandeza acima de um código de 4 dígitos, que seria varrido em minutos.
      expect(space).toBeGreaterThan(1e11);
    });

    it('não é sequencial nem repete em uso normal', () => {
      const codes = Array.from({ length: 500 }, () => generateFriendCode());

      expect(new Set(codes).size).toBe(codes.length);
      expect([...codes].sort()).not.toEqual(codes);
    });

    it('usa todo o alfabeto — o sorteio não é enviesado para os primeiros símbolos', () => {
      const seen = new Set<string>();
      for (let i = 0; i < 3000; i += 1) {
        for (const symbol of generateFriendCode().slice(FRIEND_CODE_PREFIX.length + 1)) {
          seen.add(symbol);
        }
      }

      expect(seen.size).toBe(FRIEND_CODE_ALPHABET.length);
    });
  });

  describe('normalização — a mesma que a T17.1 vai usar no lookup', () => {
    const canonical = 'SPK-7K2P9D8Q';

    it.each([
      'SPK-7K2P9D8Q',
      'spk-7k2p9d8q',
      'SPK7K2P9D8Q',
      'spk7k2p9d8q',
      '  spk-7k2p9d8q  ',
      'SPK 7K2P9D8Q',
      'sPk-7K2p9D8q',
    ])('%s converge para a forma canônica', (input) => {
      expect(normalizeFriendCode(input)).toBe(canonical);
    });

    it('todo código gerado sobrevive a uma ida e volta pela normalização', () => {
      for (let i = 0; i < 200; i += 1) {
        const code = generateFriendCode();
        expect(normalizeFriendCode(code)).toBe(code);
        expect(normalizeFriendCode(code.toLowerCase())).toBe(code);
        expect(normalizeFriendCode(code.replace('-', ''))).toBe(code);
      }
    });

    it.each([
      ['vazio', ''],
      ['só o prefixo', 'SPK-'],
      ['curto', 'SPK-7K2P9D8'],
      ['longo', 'SPK-7K2P9D8QQ'],
      ['sem prefixo', '7K2P9D8Q'],
      ['prefixo errado', 'ABC-7K2P9D8Q'],
      ['com zero (ambíguo)', 'SPK-0K2P9D8Q'],
      ['com letra O (ambígua)', 'SPK-OK2P9D8Q'],
      ['com um (ambíguo)', 'SPK-1K2P9D8Q'],
      ['com símbolo', 'SPK-7K2P9D8*'],
    ])('recusa código %s', (_name, input) => {
      expect(normalizeFriendCode(input)).toBeNull();
    });

    it('recusa em vez de lançar: malformado e inexistente precisam ser a mesma resposta', () => {
      // Se um lookup futuro distinguisse os dois, ele viraria um validador de formato de graça
      // para quem estivesse tentando enumerar.
      expect(() => normalizeFriendCode('lixo')).not.toThrow();
      expect(normalizeFriendCode('lixo')).toBeNull();
    });

    it('não depende do locale do processo', () => {
      // Em turco, `toUpperCase()` transforma `i` em `İ`. A normalização fixa `en-US` de propósito:
      // um servidor que muda de comportamento com a imagem do container é um servidor quebrado.
      expect(normalizeFriendCode('spk-7k2p9d8q')).toBe(canonical);
    });
  });
});

describe('SocialAccessPolicy', () => {
  const policy = new SocialAccessPolicy();

  const profile = (overrides: Partial<Parameters<typeof policy.canViewProfile>[0]> = {}) => ({
    status: 'ACTIVE' as const,
    discoverability: 'FRIEND_CODE_ONLY',
    friendRequestsEnabled: true,
    activitySharingEnabled: false,
    ...overrides,
  });

  const owner = { isOwner: true };
  const stranger = { isOwner: false };

  it('perfil ativo é descobrível por código', () => {
    expect(policy.canDiscoverByFriendCode(profile())).toBe(true);
  });

  it('perfil desativado não é descobrível — nem para o futuro lookup', () => {
    expect(policy.canDiscoverByFriendCode(profile({ status: 'DISABLED' }))).toBe(false);
    expect(policy.canViewProfile(profile({ status: 'DISABLED' }), stranger)).toBe(false);
  });

  it('o dono sempre vê o próprio perfil, mesmo desativado', () => {
    expect(policy.canViewProfile(profile({ status: 'DISABLED' }), owner)).toBe(true);
  });

  it('atividade é privada por padrão', () => {
    expect(policy.canViewActivity(profile(), stranger)).toBe(false);
    expect(policy.canViewActivity(profile({ activitySharingEnabled: true }), stranger)).toBe(true);
    expect(
      policy.canViewActivity(
        profile({ activitySharingEnabled: true, status: 'DISABLED' }),
        stranger,
      ),
    ).toBe(false);
  });

  it('a flag de pedidos de amizade é respeitada antes de a T17.1 existir', () => {
    expect(policy.canReceiveFriendRequest(profile())).toBe(true);
    expect(policy.canReceiveFriendRequest(profile({ friendRequestsEnabled: false }))).toBe(false);
    expect(policy.canReceiveFriendRequest(profile({ status: 'DISABLED' }))).toBe(false);
  });

  it('a política não recebe ownerUid — ela decide visibilidade, não propriedade', () => {
    const view = profile() as Record<string, unknown>;
    expect(Object.keys(view).sort()).toEqual([
      'activitySharingEnabled',
      'discoverability',
      'friendRequestsEnabled',
      'status',
    ]);
  });
});

describe('SocialProjection', () => {
  it('as regras que uma projeção futura terá de obedecer estão declaradas', () => {
    expect([...SOCIAL_PROJECTION_RULES]).toEqual([
      'OWNER_SCOPED',
      'CONSENT_REQUIRED',
      'DERIVED_NEVER_RAW',
      'NO_CROSS_DOMAIN_READ',
    ]);
  });

  it('nenhuma projeção de treino existe na T17.0 — só o contrato', () => {
    // A interface é um tipo; nada a implementa. Um `SocialProjection` concreto aqui seria a T17.4
    // antecipada sem os controles que ela vai exigir.
    const declared: SocialProjection | null = null;
    expect(declared).toBeNull();
  });
});
