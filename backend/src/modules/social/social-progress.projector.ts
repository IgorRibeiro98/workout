import { Inject, Injectable } from '@nestjs/common';
import type {
  SocialFieldAvailability,
  SocialProgressAvailabilityDto,
  SocialSharedProgressDto,
} from './social-profile.contract';
import type { StoredProgressSettings } from './social-progress.repository';
import {
  SOCIAL_PROGRESS_SOURCE,
  type SocialProgressProjection,
  type SocialProgressSource,
  type SocialProgressValue,
} from './social-progress.source';

export type { SocialProgressProjection } from './social-progress.source';

/**
 * A projeção de progresso do domínio privado para o domínio social (T17.2 §7, T19.2).
 *
 * ```text
 * ownerUid + parâmetros do dono ──▶ SocialProgressSource ──▶ SocialProgressProjection ──▶ (privacidade) ──▶ DTO
 * ```
 *
 * ## Ele projeta; ele não calcula
 *
 * Não há uma linha de regra de domínio aqui: nenhuma curva de XP, nenhuma contagem de semanas
 * consecutivas, nenhuma avaliação de conquista. Desde a T19.2 essas regras **existem** no servidor
 * — em `social-consistency.ts` e `social-gamification.ts`, presas ao Android por fixture — e quem
 * as aplica é a fonte. O projetor só monta o contexto (fuso, parâmetros declarados, relógio do
 * servidor) e devolve o que a fonte respondeu, com o mesmo nome que o produto usa. Se algum dia um
 * `if (streak > 4)` aparecer neste arquivo, a regra terá ganhado uma segunda cópia.
 *
 * ## Ele não conhece o visitante
 *
 * `project` recebe o **alvo**, e mais nada (§8). Quem pode ver é decisão da `SocialAccessPolicy`,
 * tomada **antes** (§117); o que aparece é decisão do [SocialProgressPrivacyFilter], tomada
 * depois. Um projetor que recebesse o viewer acabaria, com o tempo, decidindo acesso — e a decisão
 * de acesso estaria em dois lugares.
 *
 * ## Ownership
 *
 * O `ownerUid` que chega aqui é sempre o do perfil social alvo, resolvido no servidor a partir do
 * `socialId` (§118). Ele nunca vem do corpo, da URL ou de um cabeçalho — e a fonte o coloca na
 * cláusula `WHERE` de toda consulta, em vez de filtrar depois de ler.
 */
@Injectable()
export class SocialProgressProjector {
  constructor(@Inject(SOCIAL_PROGRESS_SOURCE) private readonly source: SocialProgressSource) {}

  /**
   * O que o servidor consegue afirmar sobre o progresso deste dono, agora.
   *
   * @param settings as preferências gravadas do dono — daqui saem só o fuso e os parâmetros de
   * consistência; os interruptores de privacidade **não** entram na projeção.
   * @param nowMs relógio do **servidor**. O relógio do aparelho não decide qual é a semana
   * corrente de ninguém: dois visitantes com relógios diferentes veriam semanas diferentes do
   * mesmo perfil.
   */
  async project(
    ownerUid: string,
    settings: Pick<StoredProgressSettings, 'weekTimeZone' | 'consistency'>,
    nowMs: number,
  ): Promise<SocialProgressProjection> {
    return await this.source.project(ownerUid, {
      weekTimeZone: settings.weekTimeZone,
      consistency: settings.consistency,
      nowMs,
    });
  }
}

/**
 * O último passo antes do DTO: o que o dono escolheu compartilhar (T17.2 §8/§13).
 *
 * ```text
 * projeção (o que o servidor sabe)  ×  preferência (o que o dono permitiu)  =  DTO do amigo
 * ```
 *
 * ## Duas condições, e as duas precisam ser verdade
 *
 * Um campo só aparece quando o dono **ligou** o interruptor e o servidor **tem** o valor. Ligar
 * sem dado não publica um zero (§4/§74); ter dado sem ligar não publica nada (§3). A ordem entre
 * as duas verificações não importa para o resultado — mas a existência das duas importa muito.
 *
 * ## Escondido e indisponível produzem exatamente a mesma resposta
 *
 * Campo ausente, nos dois casos (§37). É por isso que este filtro devolve um objeto montado por
 * omissão, e não um objeto com `null`s: um `level: null` no JSON contaria ao visitante que o campo
 * existe e foi suprimido, e comparar duas respostas revelaria a configuração de privacidade de
 * alguém. O que o amigo recebe não carrega nenhum vestígio da decisão do dono (§36).
 *
 * A privacidade é aplicada **aqui**, no servidor, e não no Compose. Um cliente modificado que
 * pedisse o mesmo endpoint receberia exatamente este objeto: o campo não foi escondido na tela —
 * ele não foi escrito na resposta.
 */
@Injectable()
export class SocialProgressPrivacyFilter {
  /** O progresso que este dono publica agora. Objeto vazio quando nada é publicável. */
  apply(
    projection: SocialProgressProjection,
    settings: StoredProgressSettings,
  ): SocialSharedProgressDto {
    const shared: {
      level?: number;
      consistencyStreak?: number;
      weeklyWorkoutCount?: number;
      highlightedAchievementIds?: readonly string[];
    } = {};

    if (settings.shareLevel && projection.level.kind === 'AVAILABLE') {
      shared.level = projection.level.value;
    }
    if (settings.shareConsistencyStreak && projection.consistencyStreak.kind === 'AVAILABLE') {
      shared.consistencyStreak = projection.consistencyStreak.value;
    }
    if (settings.shareWeeklyWorkoutCount && projection.weeklyWorkoutCount.kind === 'AVAILABLE') {
      shared.weeklyWorkoutCount = projection.weeklyWorkoutCount.value;
    }
    if (
      settings.shareHighlightedAchievements &&
      projection.highlightedAchievementIds.kind === 'AVAILABLE'
    ) {
      shared.highlightedAchievementIds = projection.highlightedAchievementIds.value;
    }

    return shared;
  }

  /**
   * A disponibilidade de cada campo, como o **dono** a vê (§38/§73).
   *
   * Ela não passa por privacidade de propósito: a pergunta é "o servidor conseguiria mostrar
   * isto?", e a resposta não muda por o interruptor estar ligado ou desligado. É essa separação
   * que permite à tela dizer "Nível — ligado, ainda não disponível" em vez de esconder o problema
   * atrás de um interruptor.
   *
   * Esta informação é **só do dono**. Ela nunca entra na resposta que um amigo recebe.
   */
  availabilityOf(projection: SocialProgressProjection): SocialProgressAvailabilityDto {
    return {
      level: availabilityOf(projection.level),
      consistencyStreak: availabilityOf(projection.consistencyStreak),
      weeklyWorkoutCount: availabilityOf(projection.weeklyWorkoutCount),
      highlightedAchievements: availabilityOf(projection.highlightedAchievementIds),
    };
  }
}

function availabilityOf(value: SocialProgressValue<unknown>): SocialFieldAvailability {
  switch (value.kind) {
    case 'AVAILABLE':
      return 'AVAILABLE';
    case 'UNAVAILABLE':
      return 'UNAVAILABLE';
    case 'UNSUPPORTED':
      return 'UNSUPPORTED';
  }
}
