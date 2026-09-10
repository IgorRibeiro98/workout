import { Controller, Delete, Get, HttpCode, HttpStatus, UseGuards } from '@nestjs/common';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { AccountDeletionService } from './account-deletion.service';
import type { AccountDeletionResponseDto } from './account-deletion.contract';
import { ACCOUNT_DELETION_ROUTE_PREFIX } from './account-deletion.contract';

@Controller(ACCOUNT_DELETION_ROUTE_PREFIX)
@UseGuards(BearerAuthGuard)
export class AccountDeletionController {
  constructor(private readonly deletionService: AccountDeletionService) {}

  @Delete()
  @HttpCode(HttpStatus.OK)
  async deleteAccount(
    @Principal() principal: AuthenticatedPrincipal,
  ): Promise<AccountDeletionResponseDto> {
    return this.deletionService.deleteAccount(principal.uid);
  }

  @Get('deletion-status')
  async getDeletionStatus(
    @Principal() principal: AuthenticatedPrincipal,
  ): Promise<AccountDeletionResponseDto> {
    return this.deletionService.getDeletionStatus(principal.uid);
  }
}
