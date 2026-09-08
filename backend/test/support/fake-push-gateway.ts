import type { PushGateway, PushPayload, PushResult } from '../../src/modules/social/push-gateway';

export interface RecordedPush {
  readonly fcmToken: string;
  readonly payload: PushPayload;
  readonly sentAt: number;
}

export class FakePushGateway implements PushGateway {
  private readonly calls: RecordedPush[] = [];
  private tokenResults: Map<string, PushResult> = new Map();
  private defaultResult: PushResult = { success: true };

  send(fcmToken: string, payload: PushPayload): Promise<PushResult> {
    this.calls.push({
      fcmToken,
      payload,
      sentAt: Date.now(),
    });

    const configured = this.tokenResults.get(fcmToken);
    return Promise.resolve(configured ?? this.defaultResult);
  }

  setTokenResult(fcmToken: string, result: PushResult): void {
    this.tokenResults.set(fcmToken, result);
  }

  setDefaultResult(result: PushResult): void {
    this.defaultResult = result;
  }

  get sentPushes(): readonly RecordedPush[] {
    return this.calls;
  }

  clear(): void {
    this.calls.length = 0;
    this.tokenResults.clear();
    this.defaultResult = { success: true };
  }
}
