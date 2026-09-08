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
 *
 * ```text
 * Challenge + participante + fonte canônica  ──▶  score  ──▶  rank · goalReached
 * ```
 *
 * ## Ele é read-only, e isso é uma invariante (§81/§211)
 *
 * Calcular pontuação **não** escreve nada: nem `WorkoutSession`, nem XP, nem conquista, nem
 * missão, nem Outbox, nem `sync_entities`, nem `sync_changes`, nem uma coluna de placar. A classe
 * inteira não tem acesso a nada que escreva — a única dependência é a fonte, cujos dois métodos
 * são `SELECT COUNT(*)`.
 *
 * Isso importa porque ler um placar é a operação mais frequente do módulo: se ela gerasse evento
 * de domínio, abrir a tela do desafio daria XP, e atualizar a tela daria de novo.
 *
 * ## Pontuação na leitura, e não um contador (§82/§83)
 *
 * Não existe `challenge_progress.current_score`. O motivo é o Spark ser local-first no treino: uma
 * sessão feita durante o desafio pode chegar ao servidor **depois** do fim (§71). Um contador
 * incremental teria de ser corrigido retroativamente por um caminho que ninguém escreveu, e os
 * modos de falhar são conhecidos: *drift*, incremento duplo num reenvio, e o placar que não
 * converge. Contar na leitura é sempre o número certo para o que o servidor sabe agora.
 *
 * Um cache, se a escala um dia exigir, nasce **derivado**, reconstruível e versionado pelo cursor
 * da fonte (§84) — nunca como autoridade.
 *
 * ## Determinismo (§210)
 *
 * A mesma entrada canônica produz a mesma saída, sempre. Não há relógio aqui: a janela chega
 * pronta, e a pontuação não depende de quando a pergunta foi feita. É o que permite a um teste
 * afirmar um placar exato.
 */
@Injectable()
export class ChallengeScoringService {
  constructor(
    @Inject(CHALLENGE_PROGRESS_SOURCE) private readonly source: ChallengeProgressSource,
  ) {}

  /**
   * A pontuação de **um** participante.
   *
   * O `ownerUid` chega de `challenge_participants`, resolvido server-side (§198/§200). Ele nunca
   * vem de uma query string, de um corpo ou de um cabeçalho — não existe, e não pode existir,
   * `GET /score?uid=...` (§199).
   */
  scoreOf(challenge: ScorableChallenge, ownerUid: string): number {
    switch (challenge.type) {
      case 'WORKOUTS_COMPLETED':
        return this.source.countCompletedWorkouts(
          ownerUid,
          challenge.startsAt,
          challenge.endsAtExclusive,
        );
      case 'ACTIVE_DAYS':
        // Por datas de calendário, e não pela janela em instantes: o dia é a unidade, e ele é
        // definido pelo fuso do desafio. A fonte converte cada dia com o mesmo
        // `localMidnightToInstant` que produziu `startsAt`.
        return this.source.countActiveDays(
          ownerUid,
          challenge.startDate,
          challenge.endDate,
          challenge.timeZoneId,
        );
    }
  }

  /**
   * O placar completo, ordenado e classificado.
   *
   * ## Ordenação e empate (§88/§89)
   *
   * `score` decrescente. Empate **permanece** empate, em *competition ranking*:
   *
   * ```text
   * Igor  8  ──▶ 1
   * João  8  ──▶ 1
   * Ana   6  ──▶ 3      (e não 2: duas pessoas ocupam a primeira posição)
   * ```
   *
   * Não há desempate. Por ordem de chegada ao servidor seria punir quem sincronizou depois (§90);
   * por `createdAt` da sessão seria inventar um critério que ninguém combinou (§91). Duas pessoas
   * que fizeram 8 treinos fizeram a mesma coisa, e o placar diz isso.
   *
   * O desempate **de exibição** — quando duas linhas têm o mesmo `rank` — é o `displayName` e
   * depois o `socialId`: estável, determinístico e sem significado competitivo. Sem ele, a ordem
   * de duas linhas empatadas dependeria da ordem de leitura do banco, e a tela trocaria as pessoas
   * de lugar entre dois refreshes.
   *
   * ## `goalReached` é separado de "vencedor" (§93)
   *
   * `score >= target` para cada um, independentemente da posição. Vários podem bater a meta; o
   * líder é quem tem o maior `score`. As duas perguntas são diferentes, e juntá-las faria "todo
   * mundo atingiu a meta" e "todo mundo venceu" virarem a mesma frase.
   */
  leaderboard(
    challenge: ScorableChallenge,
    participants: readonly ScorableParticipant[],
  ): readonly ScoredParticipant[] {
    const scored = participants.map((participant) => {
      const score = this.scoreOf(challenge, participant.ownerUid);
      return {
        ...participant,
        score,
        // Pode ultrapassar o `target` (§86): 15 de 12 é `score = 15`, e a barra é que se limita a
        // 100% na tela (§87). Truncar o número aqui apagaria um fato para caber num desenho.
        goalReached: score >= challenge.target,
        rank: 0,
      };
    });

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
