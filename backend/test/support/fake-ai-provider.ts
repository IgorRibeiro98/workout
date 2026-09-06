import type {
  AiProviderGateway,
  AiProviderRequest,
  AiProviderResult,
} from '../../src/modules/ai/provider/ai-provider.gateway';
import { AiProviderError } from '../../src/modules/ai/provider/ai-provider.gateway';

/**
 * Provider de teste.
 *
 * Vive em `test/`, e só em `test/`: não existe provider, variável de ambiente ou flag que faça o
 * processo de produção usá-lo. É esta separação que permite ao CI exercitar quota, concorrência,
 * validação, prompt injection e mapeamento de erro **sem chave do Gemini, sem rede e sem cota**.
 *
 * Ele guarda tudo o que recebeu — inclusive o prompt — para os testes provarem propriedades que
 * de outra forma seriam invisíveis: quantas chamadas aconteceram, o que foi enviado ao modelo e
 * que o texto do usuário atravessou como dado.
 */
export class FakeAiProviderGateway implements AiProviderGateway {
  readonly calls: AiProviderRequest[] = [];

  constructor(
    private responder: (request: AiProviderRequest) => Promise<AiProviderResult> | AiProviderResult,
  ) {}

  get callCount(): number {
    return this.calls.length;
  }

  /** Responde sempre o mesmo objeto, serializado como o structured output faria. */
  static respondingWith(payload: unknown, model = 'fake-model'): FakeAiProviderGateway {
    return new FakeAiProviderGateway(() => ({
      text: JSON.stringify(payload),
      model,
      usage: { promptTokens: 100, outputTokens: 50, totalTokens: 150 },
    }));
  }

  /** Responde texto cru — para o teste de JSON inválido. */
  static respondingWithText(text: string): FakeAiProviderGateway {
    return new FakeAiProviderGateway(() => ({ text, model: 'fake-model' }));
  }

  static failingWith(error: AiProviderError): FakeAiProviderGateway {
    return new FakeAiProviderGateway(() => {
      throw error;
    });
  }

  /** Segura a chamada até `release()`, para exercitar concorrência de verdade. */
  static blocking(payload: unknown): BlockingFakeAiProviderGateway {
    return new BlockingFakeAiProviderGateway(payload);
  }

  generate(request: AiProviderRequest): Promise<AiProviderResult> {
    this.calls.push(request);
    return Promise.resolve(this.responder(request));
  }
}

/** Um provider que fica pendurado até o teste liberar. */
export class BlockingFakeAiProviderGateway implements AiProviderGateway {
  readonly calls: AiProviderRequest[] = [];
  private readonly gates: Array<() => void> = [];

  constructor(private readonly payload: unknown) {}

  get callCount(): number {
    return this.calls.length;
  }

  generate(request: AiProviderRequest): Promise<AiProviderResult> {
    this.calls.push(request);
    return new Promise<AiProviderResult>((resolve) => {
      this.gates.push(() => resolve({ text: JSON.stringify(this.payload), model: 'fake-model' }));
    });
  }

  /** Libera todas as chamadas presas. */
  releaseAll(): void {
    while (this.gates.length > 0) {
      this.gates.shift()?.();
    }
  }
}
