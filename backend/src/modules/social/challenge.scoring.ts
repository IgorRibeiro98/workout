import { Inject, Injectable } from '@nestjs/common';
import type { ChallengeRole, ChallengeType } from './challenge.contract';
import {
  CHALLENGE_PROGRESS_SOURCE,
  type ChallengeProgressSource,
} from './challenge-progress.source';

/** As regras de um desafio, do ponto de vista de quem pontua. */
export interface ScorableChallenge {
  readonly type: ChallengeType;
  readonly target: number;
  readonly startDate: string;
  readonly endDate: string;
  readonly timeZoneId: string;
  readonly startsAt: number;
  readonly endsAtExclusive: number;
}

/**
 * Um participante, antes de ter pontuação.
 *
 * `ownerUid` entra porque é ele que a fonte canônica usa na cláusula `WHERE` (§200) — e ele para
 * de existir na projeção para DTO, no serviço. `role` viaja junto só para a tela distinguir quem
 * criou; ele não participa de pontuação nem de ordenação.
 */
export interface ScorableParticipant {
  readonly ownerUid: string;
  readonly socialId: string;
  readonly displayName: string;
  readonly role: ChallengeRole;
}

/** Um participante pontuado e classificado. */
export interface ScoredParticipant extends ScorableParticipant {
  readonly score: number;
  readonly goalReached: boolean;
  readonly rank: number;
}

/**
 * A pontuação de um desafio (T17.3 §80–§84).
 */
@Injectable()
export class ChallengeScoringService {
  constructor(
    @Inject(CHALLENGE_PROGRESS_SOURCE) private readonly source: ChallengeProgressSource,
  ) {}

  /**
   * A pontuação de **um** participante.
   */
  async scoreOf(challenge: ScorableChallenge, ownerUid: string): Promise<number> {
    switch (challenge.type) {
      case 'WORKOUTS_COMPLETED':
        return await this.source.countCompletedWorkouts(
          ownerUid,
          challenge.startsAt,
          challenge.endsAtExclusive,
        );
      case 'ACTIVE_DAYS':
        return await this.source.countActiveDays(
          ownerUid,
          challenge.startDate,
          challenge.endDate,
          challenge.timeZoneId,
        );
    }
  }

  /**
   * O placar completo, ordenado e classificado.
   */
  async leaderboard(
    challenge: ScorableChallenge,
    participants: readonly ScorableParticipant[],
  ): Promise<readonly ScoredParticipant[]> {
    const scored = await Promise.all(
      participants.map(async (participant) => {
        const score = await this.scoreOf(challenge, participant.ownerUid);
        return {
          ...participant,
          score,
          // Pode ultrapassar o `target` (§86): 15 de 12 é `score = 15`, e a barra é que se limita a
          // 100% na tela (§87). Truncar o número aqui apagaria um fato para caber num desenho.
          goalReached: score >= challenge.target,
          rank: 0,
        };
      }),
    );

    scored.sort(
      (a, b) =>
        b.score - a.score ||
        a.displayName.localeCompare(b.displayName) ||
        a.socialId.localeCompare(b.socialId),
    );

    // *Competition ranking*: a posição de uma linha é quantas linhas a superam, mais um. Empates
    // dividem a posição e o próximo distinto pula — `1, 1, 3`.
    let rank = 0;
    let previousScore: number | null = null;
    return scored.map((participant, index) => {
      if (previousScore === null || participant.score !== previousScore) {
        rank = index + 1;
        previousScore = participant.score;
      }
      return { ...participant, rank };
    });
  }
}
