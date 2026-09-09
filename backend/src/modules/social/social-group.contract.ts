/**
 * O contrato dos Squads privados e do feed de grupo (T17.11).
 *
 * O espelho Kotlin é `com.example.data.social.SocialGroupContract`.
 *
 * ```text
 * WorkoutSession
 *       ↓ sync T16
 * WorkoutCheckIn (T17.8/T17.9)   ← publicação que já existe
 *       ↓ ação explícita do autor
 * GroupCheckInShare              ← uma aresta, não um post
 *       ↓
 * Feed privado do Squad
 * ```
 *
 * ## Um Squad é privado, e a privacidade é estrutural (§5)
 *
 * Não existe `GET /public/groups`, não existe `GET /groups/search`, não existe link de convite,
 * não existe QR de Squad e não existe código de entrada (§4/§5). Um usuário conhece um Squad
 * porque é membro dele **ou** porque recebeu um convite — e não há terceira forma. Conhecer o
 * `groupId` não concede nada: quem não é membro recebe `404` em toda superfície do grupo (§59/§60).
 *
 * ## O Squad não cria um segundo modelo de publicação (§50)
 *
 * Não existe `SocialGroupPost`, não existe post de texto, não existe enquete e não existe segundo
 * Feed. O que entra no feed de um Squad é um `WorkoutCheckIn` que **já** está publicado e que o
 * **autor** escolheu trazer para lá (§51/§56). Nada é automático (§52).
 *
 * ## Participar de um Squad não é ser amigo (§109/§110)
 *
 * Membership e Friendship são consentimentos diferentes, e a T17.11 mantém os dois separados nas
 * duas direções: entrar num Squad não cria amizade, e desfazer a amizade não remove ninguém do
 * Squad (§31). O que muda com o `unfriend` é o que a amizade autorizava — Feed de amigos e perfil
 * —, e o que continua é exatamente o que foi compartilhado **naquele** grupo (§32).
 *
 * ## Bloqueio continua soberano (§33)
 *
 * Bloqueio é mais forte que participação: um par bloqueado dentro do mesmo Squad não vê o conteúdo
 * um do outro, não navega ao perfil um do outro e não ganha nenhuma interação nova — mas as
 * participações **não** são destruídas, porque destruí-las contaria a um terceiro que houve um
 * bloqueio (§152).
 */

import type { WorkoutCheckInDto } from './workout-checkin.contract';

// --------------------------------------------------------------------------------- tipos

/** O ciclo de vida do Squad (§46/§48). `DELETED` é terminal. */
export const SOCIAL_GROUP_STATUSES = ['ACTIVE', 'DELETED'] as const;
export type SocialGroupStatus = (typeof SOCIAL_GROUP_STATUSES)[number];

/**
 * O papel dentro do Squad (§13).
 *
 * Dois, e só dois. `ADMIN`/`MODERATOR` ficam fora porque cada um exigiria decidir o que pode
 * moderar — e moderação de conteúdo é justamente o que §129 mantém fora desta fase: quem tem
 * problema com uma publicação usa Bloqueio e Denúncia, que já existem no domínio.
 */
export const SOCIAL_GROUP_ROLES = ['OWNER', 'MEMBER'] as const;
export type SocialGroupRole = (typeof SOCIAL_GROUP_ROLES)[number];

/**
 * O estado de um convite (§21, revisto na T17.13.1 §26/§27).
 *
 * Os cinco são **gravados** desde a migration 0022:
 *
 * ```text
 * PENDING + now >= expires_at   ──▶ EXPIRED   (gravado antes de toda operação sensível a PENDING)
 * ```
 *
 * A T17.11 derivava `EXPIRED` na leitura, e a intenção era boa: não depender de um processo ter
 * passado por ali antes do toque. O que ela não alcançava era o **banco**. O índice
 * `idx_social_group_invitations_pending` é único e parcial em `WHERE status = 'PENDING'`, e um
 * índice não consulta o relógio: o convite vencido continuava ocupando a vaga única do par (Squad,
 * destinatário) e contando na quota. Ninguém conseguia aceitá-lo, e ninguém conseguia reconvidar.
 *
 * A comparação com `expiresAt` continua no caminho de aceite, como rede de segurança — nunca como
 * a única defesa.
 */
export const SOCIAL_GROUP_INVITATION_STATUSES = [
  'PENDING',
  'ACCEPTED',
  'DECLINED',
  'CANCELLED',
  'EXPIRED',
] as const;
export type SocialGroupInvitationStatus = (typeof SOCIAL_GROUP_INVITATION_STATUSES)[number];

// --------------------------------------------------------------------------------- entrada

/**
 * `POST /v1/social/groups` — o corpo inteiro (§16/§17).
 *
 * Duas coisas, e nada além. **Nunca** `ownerUid`, `memberUids`, `status`, `memberCount` ou
 * `publicId` (§83): o dono sai do token verificado, a identidade é gerada aqui, e a composição do
 * grupo nasce com uma pessoa só. O validador recusa esses campos **por nome**.
 */
export interface CreateSocialGroupRequest {
  readonly name: string;
  /** Idempotência da intenção do usuário (§146). Dois toques em "Criar Squad" produzem um Squad. */
  readonly clientRequestId: string;
}

/**
 * `POST /v1/social/groups/{groupId}/invitations` — o corpo inteiro (§22/§23/§24).
 *
 * O alvo é um `socialId` — a identidade **pública** do domínio social —, e o servidor revalida que
 * ele é um amigo direto ativo do dono no instante do envio (§25). Não existe convite por
 * `friendCode`, por `displayName` nem por e-mail (§24): o `friendCode` é o mecanismo que cria uma
 * amizade, e não um atalho para pular a amizade.
 */
export interface CreateGroupInvitationRequest {
  readonly socialId: string;
  readonly clientRequestId: string;
}

/**
 * `POST /v1/social/groups/{groupId}/transfer-ownership` — o corpo inteiro (§40/§41).
 *
 * O alvo é o `membershipId`, e não o `socialId`: a transferência precisa ser feita a partir de um
 * membro que o dono **vê na lista**, e a lista já expõe `membershipId` para todo mundo e
 * `socialId` só para quem não está em bloqueio (§34/§36). Usar o `membershipId` é o que impede
 * transferir por acidente para uma identidade que a tela nunca mostrou (§41).
 */
export interface TransferGroupOwnershipRequest {
  readonly membershipId: string;
}

// --------------------------------------------------------------------------------- saída

/**
 * Um Squad na lista do próprio usuário (§132/§133).
 *
 * `memberCount` inclui o dono (§11) e **inclui** membros que o viewer bloqueou (§35): ele é uma
 * contagem, e não identidade individual. Esconder a pessoa da lista e ainda assim contá-la é o que
 * mantém "7 membros" verdadeiro para todo mundo sem revelar quem é quem.
 */
export interface SocialGroupSummaryDto {
  readonly groupId: string;
  readonly name: string;
  readonly memberCount: number;
  /** O papel **deste** viewer neste Squad. */
  readonly role: SocialGroupRole;
  readonly createdAt: number;
}

/** `GET /v1/social/groups`. Bounded, como toda lista deste servidor. */
export interface SocialGroupListDto {
  readonly items: readonly SocialGroupSummaryDto[];
}

/**
 * Um membro, como a lista o publica (§34/§36/§110/§136).
 *
 * ## A entrada opaca
 *
 * Quando o viewer tem um bloqueio com aquele membro — em qualquer direção —, `socialId` e
 * `displayName` vêm `null` e `available` vem `false`. A tela desenha "Participante indisponível".
 *
 * `membershipId` continua presente **sempre**, e é isso que permite ao dono manter a integridade
 * administrativa do grupo (§36): ele consegue remover alguém que o bloqueou sem que a tela receba
 * a identidade daquela pessoa. Um identificador opaco válido só dentro do contexto autorizado do
 * grupo não é identidade social (§37).
 *
 * ## O que a lista **não** carrega (§110)
 *
 * Nem nível, nem streak, nem conquista, nem treino, nem progresso. Participar de um Squad não
 * concede perfil de amigo: o que a T17.2 protege continua sujeito às regras dela.
 */
export interface SocialGroupMemberDto {
  readonly membershipId: string;
  readonly role: SocialGroupRole;
  readonly joinedAt: number;
  /** `false` quando existe bloqueio entre o viewer e este membro, em qualquer direção (§34). */
  readonly available: boolean;
  /** `null` quando `available` é `false` (§34). */
  readonly socialId: string | null;
  /** `null` quando `available` é `false` (§34). */
  readonly displayName: string | null;
  readonly isCurrentUser: boolean;
}

/** `GET /v1/social/groups/{groupId}/members` (§136). */
export interface SocialGroupMembersDto {
  readonly items: readonly SocialGroupMemberDto[];
}

/** `GET /v1/social/groups/{groupId}` — o cabeçalho da tela de detalhe (§135). */
export interface SocialGroupDetailDto {
  readonly groupId: string;
  readonly name: string;
  readonly memberCount: number;
  readonly role: SocialGroupRole;
  readonly createdAt: number;
  /** Quantos convites ainda estão de pé. Só o dono recebe — só ele convida (§22). */
  readonly pendingInvitationCount: number | null;
}

/**
 * Um convite recebido, como a tela de convites o mostra (§138/§139).
 *
 * A prévia é **mínima e necessária**: o nome do Squad e quantas pessoas já estão lá são o que
 * permite decidir, e o nome de quem convidou é o que dá contexto ("foi o João"). A lista de
 * membros **não** vem antes do aceite (§139) — ela é conteúdo do grupo, e quem ainda não entrou
 * não é audiência dele.
 *
 * `inviterDisplayName` vem `null` se houver bloqueio entre o viewer e quem convidou — o que na
 * prática não acontece, porque o bloqueio cancela o convite (§105); o campo é nulável para que a
 * corrida entre as duas escritas não produza identidade vazada.
 */
export interface SocialGroupInvitationDto {
  readonly invitationId: string;
  readonly groupId: string;
  readonly groupName: string;
  readonly memberCount: number;
  readonly inviterSocialId: string | null;
  readonly inviterDisplayName: string | null;
  readonly status: SocialGroupInvitationStatus;
  readonly createdAt: number;
  readonly expiresAt: number;
}

/** `GET /v1/social/groups/invitations`. */
export interface SocialGroupInvitationListDto {
  readonly items: readonly SocialGroupInvitationDto[];
}

/**
 * Um item do feed do Squad (§69/§85/§143).
 *
 * Ele **contém** o `WorkoutCheckInDto` da T17.8/T17.9 em vez de redeclarar os campos: é a mesma
 * publicação, e duplicar a forma aqui seria o primeiro passo para os dois contratos divergirem no
 * próximo campo novo.
 *
 * `sharedToGroupAt` é o único campo que este envelope acrescenta, e ele representa a **ação social
 * de compartilhar** — nunca o horário do treino (§85). O treino continua sem instante público.
 */
export interface SocialGroupFeedItemDto {
  readonly checkIn: WorkoutCheckInDto;
  readonly sharedToGroupAt: number;
}

/** `GET /v1/social/groups/{groupId}/feed` (§86). Bounded por desenho: sem cursor histórico. */
export interface SocialGroupFeedDto {
  readonly items: readonly SocialGroupFeedItemDto[];
}

/**
 * `POST /v1/social/groups/{groupId}/checkins/{checkInId}` — a resposta.
 *
 * Devolve o item **como o feed do grupo o mostraria**, e não um `204`: a tela que compartilhou
 * precisa poder inserir o card sem recarregar a lista inteira, e devolvê-lo pela mesma projeção
 * garante que ele seja idêntico ao que a próxima leitura mostraria.
 */
export interface SocialGroupShareDto {
  readonly item: SocialGroupFeedItemDto;
  /** Em quantos Squads este check-in está agora. O teto é de domínio (§68). */
  readonly sharedGroupCount: number;
}

/**
 * Os Squads em que um check-in **próprio** já está (§141).
 *
 * Serve à tela de escolha: ela mostra as participações ativas e marca as que já receberam esta
 * publicação, para que o usuário não tente compartilhar duas vezes no mesmo grupo.
 */
export interface CheckInGroupSharesDto {
  readonly groupIds: readonly string[];
}

// --------------------------------------------------------------------------------- erros

export const SOCIAL_GROUP_ERRORS = {
  /** Corpo fora do contrato — inclusive um campo server-side (`ownerUid`, `memberCount`, ...). */
  INVALID_GROUP_REQUEST: 'INVALID_GROUP_REQUEST',
  /** O nome não passou na validação de forma (§8). */
  INVALID_GROUP_NAME: 'INVALID_GROUP_NAME',
  /** A conta não tem perfil social ativo (§16). */
  SOCIAL_NOT_ENABLED: 'SOCIAL_NOT_ENABLED',
  /**
   * Anti-enumeração (§59/§60): o Squad não existe, foi excluído, ou quem perguntou não é membro
   * ativo dele. Uma resposta só para os três — distinguir transformaria a rota em um oráculo de
   * existência, e conhecer um `groupId` passaria a valer alguma coisa.
   */
  GROUP_NOT_FOUND: 'GROUP_NOT_FOUND',
  /** A operação exige ser o dono (§22/§42/§46). */
  GROUP_FORBIDDEN: 'GROUP_FORBIDDEN',
  /** A conta atingiu o teto de Squads criados (§18). */
  GROUP_OWNED_LIMIT_REACHED: 'GROUP_OWNED_LIMIT_REACHED',
  /** A conta atingiu o teto de participações (§18). */
  GROUP_MEMBERSHIP_LIMIT_REACHED: 'GROUP_MEMBERSHIP_LIMIT_REACHED',
  /** O Squad está cheio (§11/§29). */
  GROUP_FULL: 'GROUP_FULL',
  /** O alvo do convite não é amigo direto ativo, ou há bloqueio (§23/§25). */
  GROUP_INVITE_NOT_ALLOWED: 'GROUP_INVITE_NOT_ALLOWED',
  /** O alvo já participa do Squad (§27). Idempotente do ponto de vista do resultado. */
  GROUP_ALREADY_MEMBER: 'GROUP_ALREADY_MEMBER',
  /** O Squad já tem convites pendentes demais (§126). */
  GROUP_INVITE_LIMIT_REACHED: 'GROUP_INVITE_LIMIT_REACHED',
  /**
   * O convite não existe, não é deste usuário, já foi respondido, expirou, ou a amizade/bloqueio
   * mudaram desde o envio (§29/§30). Uma resposta só, pelo mesmo motivo de sempre.
   */
  INVITATION_NOT_AVAILABLE: 'INVITATION_NOT_AVAILABLE',
  /** O dono não pode simplesmente sair nem remover a si mesmo (§39/§43). */
  GROUP_OWNER_ACTION_REQUIRED: 'GROUP_OWNER_ACTION_REQUIRED',
  /** O membro alvo não existe neste Squad (§42). */
  GROUP_MEMBER_NOT_FOUND: 'GROUP_MEMBER_NOT_FOUND',
  /** O check-in não existe, não é deste usuário, ou não está publicado (§51/§56). */
  CHECKIN_NOT_FOUND: 'CHECKIN_NOT_FOUND',
  /** O check-in já está em Squads demais (§68). */
  GROUP_SHARE_LIMIT_REACHED: 'GROUP_SHARE_LIMIT_REACHED',
  /**
   * O mesmo `clientRequestId` voltou com um payload diferente (T17.13.1 §33–§37).
   *
   * Idempotência é "a mesma intenção produz o mesmo resultado", e não "esta chave devolve o que
   * quer que tenha sido criado com ela". Um retry que muda o nome do Squad, ou que muda quem é
   * convidado, **não** é a mesma intenção: devolver o resultado antigo faria o cliente acreditar
   * que criou um Squad com o nome novo, ou que convidou C quando quem foi convidado foi B.
   */
  IDEMPOTENCY_CONFLICT: 'IDEMPOTENCY_CONFLICT',
  RATE_LIMITED: 'RATE_LIMITED',
  SOCIAL_UNAVAILABLE: 'SOCIAL_UNAVAILABLE',
} as const;

export type SocialGroupErrorCode = (typeof SOCIAL_GROUP_ERRORS)[keyof typeof SOCIAL_GROUP_ERRORS];

/**
 * O código que a desativação do Social devolve quando ela não pode prosseguir (§98/§99).
 *
 * Ele mora em `social.errors.ts` (é uma resposta da rota de perfil, não das rotas de Squad), e
 * está declarado aqui para que o nome tenha **um** dono e o Android não o escreva à mão.
 *
 * A política é explícita de propósito: desativar o Social enquanto se é dono de um Squad com
 * outras pessoas exigiria escolher um substituto, e escolher por ordenação arbitrária é entregar
 * um grupo de gente real a alguém que não pediu (§98). O servidor devolve **quantos** Squads
 * precisam de ação, e nunca quem está neles (§99).
 */
export const GROUP_OWNERSHIP_REQUIRES_ACTION = 'GROUP_OWNERSHIP_REQUIRES_ACTION';
