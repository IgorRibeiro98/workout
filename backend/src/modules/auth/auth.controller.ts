import { Controller, Get, UseGuards } from '@nestjs/common';
import type { AuthenticatedPrincipal } from './authenticated-principal';
import { BearerAuthGuard } from './bearer-auth.guard';
import { Principal } from './principal.decorator';

/** Resposta de `/v1/auth/me`. Deliberadamente mínima. */
export interface AuthenticatedIdentityResponse {
  readonly uid: string;
}

/**
 * `/v1/auth/me` — a prova de que a cadeia de identidade fecha ponta a ponta.
 *
 * ```text
 * Google → Firebase Auth → Android → Firebase ID Token → aqui → Firebase Admin → uid
 * ```
 *
 * Não é a fonte de verdade da UI: o Perfil sabe se o usuário está logado pelo Firebase Auth
 * local, e continua sabendo com o backend fora do ar (ARCHITECTURE §17). Este endpoint responde
 * *quem o servidor concluiu que você é*, e serve para validar que essa conclusão bate com a do app.
 *
 * O corpo traz só o `uid`, e o `uid` vem só do token verificado. Não existe parâmetro, header ou
 * campo de corpo capaz de influenciar a resposta — nada aqui lê a requisição além do principal.
 *
 * Nada é persistido: a T16.1 não cria tabela de usuários. O Firebase tem a identidade, o backend
 * a verifica, o SQLite ainda não precisa guardar usuário nenhum.
 */
@Controller('auth')
export class AuthController {
  @UseGuards(BearerAuthGuard)
  @Get('me')
  me(@Principal() principal: AuthenticatedPrincipal): AuthenticatedIdentityResponse {
    return { uid: principal.uid };
  }
}
