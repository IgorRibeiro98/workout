import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import { VIEWER_SCOPE_CTE } from './workout-checkin.access-policy';

/**
 * Um check-in como ele mora no banco (T17.8 §35).
 *
 * `sourceSessionSyncId` é referência **interna**: ela existe para a `UNIQUE` que garante um
 * check-in por sessão e nunca sai em DTO, notificação ou log (§13/§51).
 */
export interface StoredWorkoutCheckIn {
  readonly id: string;
  readonly authorUid: string;
  readonly sourceSessionSyncId: string;
  readonly clientRequestId: string;
  readonly status: 'PUBLISHED' | 'DELETED';
  /** A legenda, quando existe (T17.9 §7). `null` em toda publicação da T17.8 (§6/§60). */
  readonly caption: string | null;
  readonly createdAt: number;
  readonly deletedAt: number | null;
}

/** Uma linha do feed, já com a identidade pública do autor resolvida pelo `JOIN` (§79). */
export interface FeedRow {
  readonly checkInId: string;
  readonly authorUid: string;
  readonly authorSocialId: string;
  readonly authorDisplayName: string;
  readonly caption: string | null;
  readonly publishedAt: number;
}

const SELECT_COLUMNS = `id,
       author_uid,
       source_session_sync_id,
       client_request_id,
       status,
       caption,
       created_at,
       deleted_at`;

interface CheckInRow {
  readonly id: string;
  readonly author_uid: string;
  readonly source_session_sync_id: string;
  readonly client_request_id: string;
  readonly status: 'PUBLISHED' | 'DELETED';
  readonly caption: string | null;
  readonly created_at: number;
  readonly deleted_at: number | null;
}

function toDomain(row: CheckInRow): StoredWorkoutCheckIn {
  return {
    id: row.id,
    authorUid: row.author_uid,
    sourceSessionSyncId: row.source_session_sync_id,
    clientRequestId: row.client_request_id,
    status: row.status,
    caption: row.caption,
    createdAt: row.created_at,
    deletedAt: row.deleted_at,
  };
}

@Injectable()
export class WorkoutCheckInRepository {
  constructor(private readonly sqlite: SqliteService) {}

  /**
   * Insere o check-in.
   *
   * Sem `INSERT OR IGNORE` e sem `ON CONFLICT DO NOTHING`: as duas `UNIQUE` da tabela são
   * **invariantes**, e uma corrida entre duas requisições simultâneas da mesma conta precisa
   * falhar aqui em vez de virar uma segunda publicação. Quem traduz a violação em resposta é o
   * serviço, que relê e devolve o check-in que a primeira criou.
   */
  create(item: StoredWorkoutCheckIn): void {
    this.sqlite.connection
      .prepare(
        `INSERT INTO social_workout_checkins (
           id, author_uid, source_session_sync_id, client_request_id, status, caption,
           created_at, deleted_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        item.id,
        item.authorUid,
        item.sourceSessionSyncId,
        item.clientRequestId,
        item.status,
        item.caption,
        item.createdAt,
        item.deletedAt,
      );
  }

  /** A garantia de §29: um treino, no máximo um check-in — vivo ou já excluído. */
  findByAuthorAndSession(authorUid: string, sessionSyncId: string): StoredWorkoutCheckIn | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT ${SELECT_COLUMNS}
           FROM social_workout_checkins
          WHERE author_uid = ? AND source_session_sync_id = ?
          LIMIT 1`,
      )
      .get(authorUid, sessionSyncId) as CheckInRow | undefined;

    return row ? toDomain(row) : null;
  }

  /** A garantia de §31/§32: a mesma intenção do usuário produz o mesmo check-in. */
  findByAuthorAndClientRequest(
    authorUid: string,
    clientRequestId: string,
  ): StoredWorkoutCheckIn | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT ${SELECT_COLUMNS}
           FROM social_workout_checkins
          WHERE author_uid = ? AND client_request_id = ?
          LIMIT 1`,
      )
      .get(authorUid, clientRequestId) as CheckInRow | undefined;

    return row ? toDomain(row) : null;
  }

  findById(checkInId: string): StoredWorkoutCheckIn | null {
    const row = this.sqlite.connection
      .prepare(`SELECT ${SELECT_COLUMNS} FROM social_workout_checkins WHERE id = ? LIMIT 1`)
      .get(checkInId) as CheckInRow | undefined;

    return row ? toDomain(row) : null;
  }

  /**
   * Exclusão pelo autor (§64/§66).
   *
   * Escrita **condicional** — `author_uid = ? AND status = 'PUBLISHED'` —, e não uma flag em
   * memória: dois toques rápidos produzem uma transição e um no-op, e a decisão sobrevive ao
   * processo morrer. O `changes` é a resposta; o serviço não precisa reler para saber o que
   * aconteceu.
   *
   * Soft delete, e não `DELETE`: a linha é o que mantém a `UNIQUE (author_uid,
   * source_session_sync_id)` valendo depois da exclusão, e é ela que faz "um treino, no máximo um
   * check-in" continuar verdade (§29).
   */
  softDelete(checkInId: string, authorUid: string, now: number): boolean {
    const result = this.sqlite.connection
      .prepare(
        `UPDATE social_workout_checkins
            SET status = 'DELETED', deleted_at = ?
          WHERE id = ? AND author_uid = ? AND status = 'PUBLISHED'`,
      )
      .run(now, checkInId, authorUid);

    return result.changes > 0;
  }

  /**
   * Remove a linha de verdade — o **único** `DELETE` deste agregado (T17.9 §43).
   *
   * Usado em um caso só: a publicação nasceu, o anexo da foto foi recusado (mídia de outra conta,
   * de outra sessão ou já usada) e a criação inteira precisa ser desfeita. Um soft delete aqui
   * seria pior que um erro: a `UNIQUE (author_uid, source_session_sync_id)` continuaria valendo
   * sobre uma linha `DELETED`, e aquele treino ficaria **impublicável para sempre** por causa de
   * um `mediaId` errado que o usuário nem escolheu conscientemente.
   *
   * É seguro porque a linha existiu por milissegundos dentro da mesma requisição: ninguém a leu,
   * nenhum comentário ou reação aponta para ela, e nenhuma mídia foi anexada — o anexo é
   * justamente o que falhou.
   */
  hardDelete(checkInId: string): void {
    this.sqlite.connection
      .prepare(`DELETE FROM social_workout_checkins WHERE id = ?`)
      .run(checkInId);
  }

  /**
   * Uma transação do agregado.
   *
   * Exposta aqui porque o dono da conexão é o repositório, e o serviço não conhece
   * `SqliteService` — a mesma separação que o resto do módulo mantém. O caso que a exige é a
   * exclusão: revogar a publicação e a mídia precisa ser uma coisa só, ou existe um instante em
   * que o post sumiu e a foto ainda responde.
   */
  transaction<T>(work: () => T): T {
    return this.sqlite.connection.transaction(work)();
  }

  /**
   * O feed do `viewerUid` (§54–§60, §70–§79).
   *
   * ## A autorização está **na consulta**, e não depois dela (§78)
   *
   * Carregar todos os check-ins e filtrar amizade em JavaScript é o desenho que, no dia de um bug
   * de filtro, responde o dado de quem não devia. Aqui a audiência é derivada em SQL:
   *
   * ```text
   * eligible_authors = { viewer }  ∪  { amigos diretos atuais, com perfil ACTIVE, não bloqueados }
   * ```
   *
   * Desde a T17.9 essa definição não mora mais **aqui**: ela é `VIEWER_SCOPE_CTE`, em
   * `workout-checkin.access-policy.ts`, e é literalmente a mesma que a mídia, as reações, os
   * comentários, o detalhe e a denúncia usam (T17.9 §129/§130). Cinco superfícies respondendo a
   * mesma pergunta com cinco cópias do mesmo SQL é o desenho em que, no dia de um ajuste, quatro
   * mudam e a quinta continua respondendo o dado de quem não devia.
   *
   * - **amizade é avaliada na leitura** (§55): a tabela `friendships` tem uma linha por par, e
   *   desfazer a amizade apaga a linha — a publicação some da leitura seguinte sem que nada toque
   *   no check-in (§56);
   * - **pedido `PENDING` não concede nada**: `friend_requests` não participa desta consulta;
   * - **bloqueio nas duas direções** (§57/§58): a CTE `blocked` reúne quem o viewer bloqueou e
   *   quem bloqueou o viewer, e a revogação vale já na próxima leitura, sem depender de cache do
   *   aparelho;
   * - **autor desativado some** (§60): `p.status = 'ACTIVE'` no `JOIN`, aplicado a **todos** os
   *   autores — inclusive ao próprio viewer, que o serviço já validou antes de chegar aqui;
   * - **conta excluída não deixa órfão** (§63): o `JOIN` com `social_profiles` é interno, e a
   *   exclusão de conta remove o perfil junto com os check-ins.
   *
   * ## Bounded por construção (§73–§77)
   *
   * Janela de 30 dias no `WHERE`, teto de itens no `LIMIT`, e nenhum cursor histórico. Não existe
   * caminho que devolva "todos os posts de todo mundo" para filtrar em memória.
   *
   * `ORDER BY created_at DESC, id DESC` — desempate determinístico por `checkInId` (§76), para que
   * duas publicações no mesmo milissegundo tenham sempre a mesma ordem.
   */
  findFeed(viewerUid: string, publishedSinceMs: number, limit: number): FeedRow[] {
    return this.sqlite.connection
      .prepare(
        `WITH ${VIEWER_SCOPE_CTE}
         SELECT c.id            AS checkInId,
                c.author_uid    AS authorUid,
                p.social_id     AS authorSocialId,
                p.display_name  AS authorDisplayName,
                c.caption       AS caption,
                c.created_at    AS publishedAt
           FROM social_workout_checkins c
           JOIN eligible_authors ea ON ea.uid = c.author_uid
           JOIN social_profiles p   ON p.owner_uid = c.author_uid
          WHERE c.status = 'PUBLISHED'
            AND c.created_at >= :publishedSince
            AND p.status = 'ACTIVE'
          ORDER BY c.created_at DESC, c.id DESC
          LIMIT :limit`,
      )
      .all({ viewer: viewerUid, publishedSince: publishedSinceMs, limit }) as FeedRow[];
  }
}
