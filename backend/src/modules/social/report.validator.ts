import { BadRequestException } from '@nestjs/common';

/**
 * Os campos que o cliente **não** pode propor numa denúncia (T17.9 §103).
 *
 * O alvo é resolvido no servidor: dado `targetType` e `targetId`, é o banco que responde quem é o
 * autor. Um `reportedUid` no corpo descreve um cliente que se acha autoridade sobre quem está
 * sendo denunciado — e aceitá-lo deixaria qualquer pessoa registrar uma denúncia contra a conta
 * que quisesse, apontando para conteúdo que nem é dela. §195 chama isso de bloqueante.
 *
 * A recusa invalida a requisição **inteira**, em vez de filtrar o campo em silêncio. É a mesma
 * regra que a T17.0 aplica a `ownerUid`, a T17.2 a `level` e a T17.8 a `completed`: ignorar seria
 * pior, porque alguma versão futura do servidor acabaria "aproveitando" um campo que nunca
 * deveria ter existido.
 */
const SERVER_RESOLVED_FIELDS = [
  'reportedUid',
  'reported_uid',
  'authorUid',
  'author_uid',
  'ownerUid',
  'owner_uid',
  'uid',
  'firebaseUid',
  'email',
  // O desfecho da revisão é operacional, e não algo que o denunciante declara (§108).
  'status',
  'resolution',
  'action',
  'ban',
  'banned',
  // Identidade e instante da denúncia são do servidor.
  'reportId',
  'createdAt',
  'created_at',
] as const;

export function rejectClientResolvedReportFields(body: unknown): void {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw new BadRequestException('o corpo da requisição precisa ser um objeto JSON');
  }
  const object = body as Record<string, unknown>;
  for (const field of SERVER_RESOLVED_FIELDS) {
    if (field in object) {
      throw new BadRequestException(
        `${field} não é aceito nesta requisição: o servidor resolve o alvo da denúncia`,
      );
    }
  }
}
