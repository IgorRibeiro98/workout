import { Inject, Injectable } from '@nestjs/common';
import type {
  SocialFieldAvailability,
  SocialProgressAvailabilityDto,
  SocialSharedProgressDto,
} from './social-profile.contract';
import type { StoredProgressSettings } from './social-progress.repository';
import {
  SOCIAL_PROGRESS_SOURCE,
  type SocialProgressSource,
  type SocialProgressValue,
} from './social-progress.source';

/**
 * O progresso de **um** dono, como o servidor consegue afirmá-lo — antes de qualquer privacidade.
 *
 * Cada campo é um [SocialProgressValue], nunca um número solto: é o tipo que carrega a diferença
 * entre "três treinos", "ainda não sei" e "esta versão não sabe". Um `number | null` colapsaria os
 * dois últimos, e o primeiro consumidor escreveria `?? 0`.
 */
export interface SocialProgressProjection {
  readonly level: SocialProgressValue<number>;
  readonly consistencyStreak: SocialProgressValue<number>;
  readonly weeklyWorkoutCount: SocialProgressValue<number>;
  readonly highlightedAchievementIds: SocialProgressValue<readonly string[]>;
}

/**
 * A projeção de progresso do domínio privado para o domínio social (T17.2 §7).
 *
 * ```text
 * ownerUid ──▶ SocialProgressSource ──▶ SocialProgressProjection ──▶ (privacidade) ──▶ DTO
 * ```
 *
 * ## Ele projeta; ele não calcula
 *
 * Não há uma linha de regra de domínio aqui: nenhuma curva de XP, nenhuma contagem de semanas
 * consecutivas, nenhuma avaliação de conquista. Tudo o que ele faz é perguntar à fonte e devolver
 * o que ela respondeu, com o mesmo nome que o produto usa. Se algum dia um `if (streak > 4)`
 * aparecer neste arquivo, o Social terá virado autoridade de progresso — que é exatamente o que a
 * T17.2 existe para impedir.
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
   * @param nowMs relógio do **servidor**. O relógio do aparelho não decide qual é a semana
   * corrente de ninguém: dois visitantes com relógios diferentes veriam semanas diferentes do
   * mesmo perfil.
   */
  project(ownerUid: string, weekTimeZone: string | null, nowMs: number): SocialProgressProjection {
    return {
      level: this.source.getLevel(ownerUid),
      consistencyStreak: this.source.getConsistencyStreak(ownerUid),
      weeklyWorkoutCount: this.source.getWeeklyWorkoutCount(ownerUid, weekTimeZone, nowMs),
      highlightedAchievementIds: this.source.getEarnedAchievementIds(ownerUid),
    };
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
