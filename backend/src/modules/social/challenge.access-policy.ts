import { Injectable } from '@nestjs/common';
import type {
  ChallengeInvitationStatus,
  ChallengeRole,
  ChallengeParticipantStatus,
  ChallengeStatus,
} from './challenge.contract';
import { CHALLENGE_PARTICIPANTS } from './challenge.limits';

/** O recorte de um desafio que a política precisa: a janela, o cancelamento e quantos participam. */
export interface ChallengeLifecycleView {
  readonly lifecycle: 'OPEN' | 'CANCELLED';
  readonly startsAt: number;
  readonly endsAtExclusive: number;
  /** Participantes `JOINED` agora. */
  readonly participantCount: number;
}

/** Quem está olhando, já resolvido a partir de `challenge_participants`. */
export interface ChallengeViewer {
  readonly role: ChallengeRole;
  readonly status: ChallengeParticipantStatus;
}

/**
 * O **único** lugar que responde "em que estado está" e "quem pode o quê" nos desafios (T17.3).
 *
 * Ela existe pelo mesmo motivo que `SocialAccessPolicy` e `FriendshipAccessPolicy`: a alternativa
 * é `if (now < challenge.startsAt)` espalhado por seis handlers, cada cópia envelhecendo no seu
 * ritmo. A sexta cópia — a esquecida — é a que deixa alguém aceitar um desafio que já começou.
 *
 * ## Ela deriva o ciclo de vida; ela não o persiste
 *
 * O banco guarda `lifecycle` (`OPEN`/`CANCELLED`), que é a única parte que alguém escreve. Os
 * cinco estados que a UI mostra saem daqui, do relógio do servidor (§27). Um cron que virasse
 * `UPCOMING` em `ACTIVE` teria um modo de falhar silencioso: não rodou, o desafio nunca começa.
 */
@Injectable()
export class ChallengeAccessPolicy {
  /**
   * O status derivado (§26).
   *
   * ```text
   * cancelado                                      ──▶ CANCELLED
   * ainda não começou                              ──▶ UPCOMING
   * já começou, e há menos de 2 participantes      ──▶ VOID
   * já começou, e a janela não fechou              ──▶ ACTIVE
   * a janela fechou                                ──▶ ENDED
   * ```
   *
   * ## `VOID` (§28/§29)
   *
   * "A janela começou e não há competição." Cobre as duas formas de isso ser verdade: ninguém
   * aceitou antes de começar, e todo mundo saiu. Um desafio com um participante não deve fingir
   * competição — ele não tem contra quem, e mostrar um placar de uma linha seria uma disputa
   * inventada.
   *
   * Ele é **monotônico**, e isso não é acidente: entrar exige aceitar, aceitar exige
   * `now < startsAt` (§54), e `VOID` só é avaliado depois disso. Uma vez `VOID`, sempre `VOID` — o
   * estado não pisca entre duas leituras.
   *
   * ## `ENDED` não significa resultado final (§73/§252)
   *
   * Significa que a **janela de elegibilidade** fechou: nenhum treino novo pode passar a contar.
   * Os treinos que já aconteceram dentro dela ainda podem chegar por sync e mudar o placar (§71),
   * e a resposta diz isso (`resultMayStillChange`) em vez de prometer o que não pode garantir.
   */
  statusOf(challenge: ChallengeLifecycleView, nowMs: number): ChallengeStatus {
    if (challenge.lifecycle === 'CANCELLED') {
      return 'CANCELLED';
    }
    if (nowMs < challenge.startsAt) {
      return 'UPCOMING';
    }
    if (challenge.participantCount < CHALLENGE_PARTICIPANTS.minimumToCompete) {
      return 'VOID';
    }
    if (nowMs < challenge.endsAtExclusive) {
      return 'ACTIVE';
    }
    return 'ENDED';
  }

  /**
   * O status derivado de um convite (§36/§55).
   *
   * ```text
   * gravado ACCEPTED / DECLINED         ──▶ ele mesmo (terminal)
   * gravado PENDING + desafio cancelado ──▶ CANCELLED
   * gravado PENDING + já começou        ──▶ EXPIRED     (a transição lazy)
   * gravado PENDING                     ──▶ PENDING
   * ```
   *
   * Derivar é o que torna "aceitar depois do início" **impossível** em vez de "improvável": não
   * depende de um processo ter passado por ali antes do toque do usuário.
   */
  invitationStatusOf(
    stored: 'PENDING' | 'ACCEPTED' | 'DECLINED',
    challenge: ChallengeLifecycleView,
    nowMs: number,
  ): ChallengeInvitationStatus {
    if (stored !== 'PENDING') {
      return stored;
    }
    if (challenge.lifecycle === 'CANCELLED') {
      return 'CANCELLED';
    }
    if (nowMs >= challenge.startsAt) {
      return 'EXPIRED';
    }
    return 'PENDING';
  }

  /**
   * Um convite pode ser aceito **agora**?
   *
   * Só enquanto o desafio está `UPCOMING` (§54). Depois disso, entrar seria *late join* — que é
   * bloqueante (§56) — e obrigaria a decidir se os treinos anteriores do recém-chegado contam.
   * Qualquer das duas respostas seria injusta com alguém, então a situação não existe.
   */
  canAcceptInvitation(challenge: ChallengeLifecycleView, nowMs: number): boolean {
    return challenge.lifecycle === 'OPEN' && nowMs < challenge.startsAt;
  }

  /**
   * Quem pode cancelar (§67–§69).
   *
   * Só o criador, e só enquanto o desafio não é terminal. Cancelar um `UPCOMING` e cancelar um
   * `ACTIVE` são os dois permitidos; cancelar o que já acabou não tem efeito nenhum — o resultado
   * já existe, e apagá-lo depois seria reescrever um fato.
   */
  canCancel(challenge: ChallengeLifecycleView, viewer: ChallengeViewer, nowMs: number): boolean {
    if (viewer.role !== 'CREATOR') {
      return false;
    }
    const status = this.statusOf(challenge, nowMs);
    return status === 'UPCOMING' || status === 'ACTIVE' || status === 'VOID';
  }

  /**
   * Quem pode sair (§61/§62).
   *
   * Membro `JOINED`, e não o criador: sair deixaria um desafio sem dono, com participantes
   * competindo por regras que ninguém mais pode encerrar. A saída do criador é cancelar, e ela é
   * honesta com os outros — o desafio acaba para todos, sem resultado.
   *
   * Sair é permitido **durante** o desafio (§64), e não só antes: é controle da pessoa sobre o
   * próprio progresso, e o efeito é imediato — ela some do placar competitivo (§96).
   */
  canLeave(challenge: ChallengeLifecycleView, viewer: ChallengeViewer, nowMs: number): boolean {
    if (viewer.role === 'CREATOR' || viewer.status !== 'JOINED') {
      return false;
    }
    const status = this.statusOf(challenge, nowMs);
    return status === 'UPCOMING' || status === 'ACTIVE' || status === 'VOID';
  }

  /**
   * O resultado ainda pode mudar? (§74/§179)
   *
   * `true` depois que a janela fecha. O servidor **não sabe** se todos os participantes já
   * sincronizaram tudo o que fizeram no período — e não tem como saber, porque um aparelho pode
   * estar offline há semanas. Afirmar um resultado final seria afirmar o que ele não pode
   * verificar (§75/§253), então a resposta carrega a verdade e a tela a diz.
   *
   * Um desafio cancelado não tem resultado (§94), e um `VOID` também não (§95): nos dois casos não
   * há o que convergir.
   */
  resultMayStillChange(challenge: ChallengeLifecycleView, nowMs: number): boolean {
    return this.statusOf(challenge, nowMs) === 'ENDED';
  }
}
