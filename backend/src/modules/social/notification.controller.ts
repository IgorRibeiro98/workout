import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Patch,
  Post,
  Req,
  UseGuards,
} from '@nestjs/common';
import type { Request } from 'express';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { RequestWithRawBody } from '../../common/raw-body';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import type {
  NotificationPreferencesDto,
  PushDeviceRegistrationDto,
} from './notification.contract';
import { NotificationService } from './notification.service';
import {
  validateRegisterPushDevice,
  validateUpdateNotificationPreferences,
} from './notification.validator';

@Controller('social/notifications')
@UseGuards(BearerAuthGuard)
export class NotificationController {
  constructor(private readonly service: NotificationService) {}

  @Post('devices')
  @HttpCode(HttpStatus.CREATED)
  registerDevice(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() req: Request,
    @Body() body: unknown,
  ): PushDeviceRegistrationDto {
    const parsed = validateRegisterPushDevice(body, this.contentLength(req));
    return this.service.registerDevice(principal, this.requestId(req), parsed);
  }

  @Delete('devices/:deviceId')
  @HttpCode(HttpStatus.NO_CONTENT)
  unregisterDevice(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('deviceId') deviceId: string,
  ): void {
    this.service.unregisterDevice(principal, deviceId);
  }

  @Get('preferences')
  getPreferences(@Principal() principal: AuthenticatedPrincipal): NotificationPreferencesDto {
    return this.service.getPreferences(principal);
  }

  @Patch('preferences')
  updatePreferences(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() req: Request,
    @Body() body: unknown,
  ): NotificationPreferencesDto {
    const parsed = validateUpdateNotificationPreferences(body, this.contentLength(req));
    return this.service.updatePreferences(principal, this.requestId(req), parsed);
  }

  private requestId(req: Request): string {
    return (req as RequestWithId).requestId ?? 'unknown';
  }

  private contentLength(req: Request): number | undefined {
    const raw = (req as RequestWithRawBody).rawBody;
    return raw ? raw.length : undefined;
  }
}
