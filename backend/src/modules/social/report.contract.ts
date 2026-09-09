/**
 * Contrato do Módulo de Denúncias Sociais (T17.6, expandido na T17.9 §101–§110).
 *
 * Denúncia minimalista para auditoria e revisão operacional no backend/VPS.
 * - Sem texto livre.
 * - Sem banimento automático (§108).
 * - Exige contexto social legítimo (amigos, solicitação pendente ou desafio compartilhado).
 * - Usuário denunciado não recebe notificação, e denunciar não gera push (§109).
 *
 * ## O que a T17.9 acrescentou
 *
 * A T17.6 tinha um alvo só: uma **conta**. Agora existe conteúdo gerado por usuário, e denunciar
 * "a pessoa" quando o problema é um post específico perde a informação que a revisão precisa. Os
 * alvos passam a ser três (§101):
 *
 * ```text
 * USER      → uma conta            (regressão da T17.6, inalterada)
 * CHECKIN   → uma publicação       (a legenda e a foto pertencem a ela, §102)
 * COMMENT   → um comentário
 * ```
 *
 * **Não existe `MEDIA`** (§102). A foto é parte do check-in; um alvo próprio criaria duas linhas
 * para o mesmo problema e duas filas de revisão para a mesma decisão.
 *
 * ## O alvo é resolvido no servidor (§103)
 *
 * O cliente envia `targetType` e `targetId`. O **autor** é derivado aqui, do banco. Não existe — e
 * não pode existir — um `reportedUid` vindo do aparelho: aceitá-lo deixaria qualquer pessoa
 * registrar uma denúncia contra a conta que quisesse, apontando para um conteúdo que nem é dela.
 * §195 chama isso de bloqueante, e a validação recusa o campo **por nome**.
 */

export const REPORTS_ROUTE_PREFIX = 'social/reports';

export const REPORT_REASONS = ['SPAM', 'HARASSMENT', 'INAPPROPRIATE_BEHAVIOR', 'OTHER'] as const;

export type ReportReason = (typeof REPORT_REASONS)[number];

/** Os alvos aceitos (§101/§102). Enum fechado, como o de reações. */
export const REPORT_TARGET_TYPES = ['USER', 'CHECKIN', 'COMMENT'] as const;

export type ReportTargetType = (typeof REPORT_TARGET_TYPES)[number];

/**
 * O corpo de `POST /v1/social/reports`.
 *
 * Duas formas aceitas, e a compatibilidade é deliberada:
 *
 * - `{ targetType, targetId, reason }` — a forma da T17.9, que cobre os três alvos;
 * - `{ reportedSocialId, reason }` — a forma da T17.6, que continua funcionando. Um APK já
 *   instalado não pode parar de denunciar porque o servidor subiu; §175 pede a regressão explícita
 *   de `REPORT USER`, e ela é este campo.
 *
 * `reportedUid` **não** é aceito em nenhuma das duas (§103): o autor sai do banco.
 */
export interface CreateReportRequestDto {
  /** T17.6 — a forma antiga, equivalente a `targetType: 'USER'` com o `socialId` como alvo. */
  readonly reportedSocialId?: string;
  readonly targetType?: ReportTargetType;
  /** `socialId` para `USER`; `checkInId` para `CHECKIN`; `commentId` para `COMMENT`. */
  readonly targetId?: string;
  readonly reason: ReportReason;
}

export interface CreateReportResponseDto {
  readonly result: 'REPORT_RECEIVED';
  readonly reportId: string;
}
