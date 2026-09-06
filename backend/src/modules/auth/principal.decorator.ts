import { createParamDecorator, ExecutionContext, UnauthorizedException } from '@nestjs/common';
import type { AuthenticatedPrincipal } from './authenticated-principal';
import type { RequestWithPrincipal } from './bearer-auth.guard';

/**
 * O principal da requisição — colocado pelo `BearerAuthGuard` a partir do token verificado.
 *
 * Se o decorator for usado numa rota sem guard, ele falha em vez de devolver `undefined`: um
 * handler que espera identidade não pode rodar sem identidade.
 */
export const Principal = createParamDecorator(
  (_data: unknown, context: ExecutionContext): AuthenticatedPrincipal => {
    const request = context.switchToHttp().getRequest<RequestWithPrincipal>();
    if (!request.principal) {
      throw new UnauthorizedException({
        code: 'UNAUTHENTICATED',
        message: 'A valid Firebase ID token is required',
      });
    }
    return request.principal;
  },
);
