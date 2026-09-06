/**
 * Envelope de erro único do Spark Backend.
 *
 * Formato estável para o cliente Android: um código legível por máquina, uma mensagem curta e o
 * `requestId` que permite correlacionar com o log do servidor sem que o servidor precise devolver
 * detalhe interno.
 */
export interface ErrorEnvelope {
  error: {
    code: string;
    message: string;
    requestId: string;
  };
}

export function errorEnvelope(code: string, message: string, requestId: string): ErrorEnvelope {
  return { error: { code, message, requestId } };
}

export const INTERNAL_ERROR_CODE = 'INTERNAL_ERROR';
export const INTERNAL_ERROR_MESSAGE = 'Unexpected server error';
