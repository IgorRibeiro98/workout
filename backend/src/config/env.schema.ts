import { z } from 'zod';

/**
 * Uma flag booleana vinda do ambiente.
 *
 * `z.coerce.boolean()` **não** serve aqui: ele considera verdadeira qualquer string não vazia, e
 * `SYNC_WRITE_ENABLED=false` viraria `true` silenciosamente — exatamente o oposto do que um
 * interruptor de emergência precisa fazer. O vocabulário é fechado, e um valor fora dele derruba o
 * processo no startup em vez de escolher um default por conta própria.
 */
const booleanFlag = (defaultValue: boolean) =>
  z
    .union([z.boolean(), z.enum(['true', 'false', '1', '0'])])
    .default(defaultValue)
    .transform((value) => (typeof value === 'boolean' ? value : value === 'true' || value === '1'));

/**
 * Contrato de configuração do Spark Backend.
 *
 * Toda configuração vem do ambiente. Não existe valor secreto embutido no código, e não existe
 * inicialização parcialmente funcional: uma configuração obrigatória inválida derruba o processo
 * no startup (fail fast), antes de o servidor HTTP aceitar qualquer requisição.
 */
export const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),

  /** Porta HTTP. O TLS é responsabilidade do reverse proxy (Caddy), não deste processo. */
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),

  /**
   * Caminho do arquivo SQLite. Obrigatório e sem default: em container ele precisa apontar para
   * um volume persistente (ex.: `/data/spark.db`). Um default silencioso esconderia exatamente o
   * erro que queremos impossibilitar — banco vivendo só na camada efêmera do container.
   */
  DATABASE_PATH: z.string().min(1, 'DATABASE_PATH é obrigatório'),

  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),

  /**
   * `PRAGMA synchronous` (T16.8 §18).
   *
   * **`FULL` por padrão, e a escolha é deliberada.** Em WAL, `NORMAL` deixa o commit voltar antes
   * de o WAL estar no disco: um corte de energia na VPS pode custar as últimas transações
   * *confirmadas*. No Spark isso não é "perder alguns segundos de escrita" — o aparelho só libera
   * a Outbox depois da confirmação do servidor (§13.2/§13.4), então uma transação confirmada e
   * perdida é dado que o cliente considera salvo e que ninguém vai reenviar.
   *
   * O custo é irrelevante na escala do projeto: as escritas são pequenas, esparsas e nunca em
   * laço. `NORMAL` continua disponível como configuração para quem rodar em um disco com
   * garantia de bateria/flush, mas trocar é decisão explícita, não default.
   */
  SQLITE_SYNCHRONOUS: z.enum(['NORMAL', 'FULL']).default('FULL'),

  /**
   * `PRAGMA wal_autocheckpoint`, em páginas (T16.8 §77).
   *
   * O default do SQLite (1000 páginas ≈ 4 MB) já impede o WAL de crescer indefinidamente, e é
   * exatamente ele que preservamos. A configuração existe para que a política seja **explícita e
   * localizável** em vez de herdada em silêncio — e para permitir baixá-la se o arquivo `-wal`
   * incomodar em uma VPS pequena. `0` desliga o checkpoint automático e é aceito só porque o
   * shutdown ainda faz `wal_checkpoint(TRUNCATE)`; não use isso sem motivo.
   */
  SQLITE_WAL_AUTOCHECKPOINT_PAGES: z.coerce.number().int().min(0).max(1_000_000).default(1_000),

  /**
   * Teto de tempo de uma requisição HTTP inteira, aplicado no servidor Node (T16.8 §88).
   *
   * Não é 30 s: o maior corpo aceito é um snapshot de backup de até 4 MiB, e um celular em rede
   * móvel ruim leva mais que isso para enviá-lo. Um teto único e curto transformaria backup
   * legítimo em falha recorrente; um servidor sem teto nenhum deixa conexão pendurada segurando
   * recurso.
   */
  HTTP_REQUEST_TIMEOUT_MS: z.coerce.number().int().min(1_000).max(600_000).default(120_000),

  /**
   * `server.keepAliveTimeout`. Precisa ser **maior** que o keep-alive do proxy à frente, senão o
   * Caddy reaproveita uma conexão que o Node acabou de fechar e o cliente vê um 502 esporádico.
   */
  HTTP_KEEP_ALIVE_TIMEOUT_MS: z.coerce.number().int().min(1_000).max(300_000).default(65_000),

  /**
   * `PRAGMA busy_timeout`. Centralizado aqui: nenhum outro ponto do código escolhe esse valor.
   */
  SQLITE_BUSY_TIMEOUT_MS: z.coerce.number().int().min(0).max(60_000).default(5_000),

  /** Tempo máximo para drenar conexões em SIGTERM/SIGINT antes de encerrar à força. */
  SHUTDOWN_TIMEOUT_MS: z.coerce.number().int().min(0).max(120_000).default(10_000),

  /**
   * Credencial do Firebase Admin (T16.1), no mecanismo oficial para ambientes não-Google:
   * o **caminho** de um arquivo de service account que vive fora do repositório e é montado
   * somente-leitura no container. A chave privada nunca entra no Git, na imagem, no
   * `docker-compose.yml` nem neste schema — aqui só existe um caminho.
   *
   * Opcional: sem ela o processo sobe normalmente, `/health/*` continua público e as rotas
   * autenticadas respondem 503 (incapaz de verificar), nunca 200 sem verificação. Não existe
   * modo "autenticação desligada".
   */
  GOOGLE_APPLICATION_CREDENTIALS: z.string().min(1).optional(),

  /**
   * Projeto Firebase esperado pelo verificador. Opcional: normalmente vem do próprio arquivo de
   * credencial. Quando informado, é o `project_id` contra o qual o token precisa ter sido emitido.
   */
  FIREBASE_PROJECT_ID: z.string().min(1).optional(),
  /**
   * Credencial do Gemini (T16.2). Vive **apenas** no servidor: nunca no APK, no BuildConfig, em
   * resource, em DataStore, no Git ou em teste. A partir da T16.2 o Android não fala com o Gemini.
   *
   * Opcional: sem ela o processo sobe, o núcleo do Spark continua intacto e `/v1/ai/coach`
   * responde 503 (`AI_PROVIDER_UNAVAILABLE`) — nunca 200 sem ter chamado o modelo.
   */
  GEMINI_API_KEY: z.string().min(1).optional(),

  /**
   * Modelo do Coach. O default preserva exatamente o que a T14 usava via Firebase AI Logic:
   * a T16.2 é migração de transporte, não troca oportunista de modelo.
   */
  GEMINI_MODEL: z.string().min(1).default('gemini-3.6-flash'),

  /** Teto de tempo de uma chamada ao provider. O default é o mesmo da T14 (30 s). */
  AI_TIMEOUT_MS: z.coerce.number().int().min(1_000).max(120_000).default(30_000),

  /** Temperatura: análise pede consistência, não criatividade. Mesmo valor da T14. */
  AI_TEMPERATURE: z.coerce.number().min(0).max(2).default(0.2),

  /** Teto de saída, o mesmo da T14: evita custo acidental sem cortar uma análise real. */
  AI_MAX_OUTPUT_TOKENS: z.coerce.number().int().min(256).max(32_768).default(2_048),

  /** Esforço de raciocínio pedido ao modelo. Mesmo nível da T14. */
  AI_THINKING_LEVEL: z.enum(['MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'OFF']).default('MEDIUM'),

  /**
   * Teto diário de chamadas ao provider **por conta**.
   *
   * Existe como configuração, e não como número espalhado no código, porque é a proteção de
   * custo que muda com o uso real.
   */
  AI_MAX_REQUESTS_PER_USER_DAY: z.coerce.number().int().min(0).max(100_000).default(50),

  /**
   * Teto diário de chamadas ao provider no **servidor inteiro**.
   *
   * Um bug em vários aparelhos não pode transformar um app pessoal em uma conta inesperada de IA.
   */
  AI_MAX_REQUESTS_GLOBAL_DAY: z.coerce.number().int().min(0).max(1_000_000).default(500),

  /**
   * Chamadas simultâneas ao provider por conta. 1 de propósito: reduz custo acidental, toque
   * duplo, corrida e resultado fora de ordem.
   */
  AI_MAX_CONCURRENT_REQUESTS_PER_USER: z.coerce.number().int().min(1).max(8).default(1),

  /**
   * Quantos snapshots de backup guardar **por conta** (T16.4).
   *
   * Cinco, e o número tem razão: um backup do Spark é um snapshot completo, então cada um já
   * basta sozinho para restaurar. Guardar vários não é redundância de armazenamento, é janela de
   * arrependimento — recuperar um estado anterior a uma exclusão acidental que só foi percebida
   * dois backups depois. Cinco cobre essa janela com um custo de disco irrelevante para o público
   * do projeto (ADR-0001) e não deixa o banco crescer sem limite.
   *
   * Central de propósito: nenhum outro ponto do código escolhe esse valor.
   */
  BACKUP_RETENTION_COUNT: z.coerce.number().int().min(1).max(100).default(5),

  // --- Prontidão de produção (T16.8) ----------------------------------------------------
  //
  // Duas famílias diferentes, e confundi-las seria o erro:
  //
  // - `REQUIRE_*` decide o que **impede o processo de subir**. É a resposta explícita ao §57:
  //   "startup fail" ou "feature unavailable" é escolha por funcionalidade, e não um default
  //   escondido no código.
  // - `*_ENABLED` são **interruptores de operação**, para desligar uma capacidade em um servidor
  //   que está no ar sem derrubar o resto.

  /**
   * Exige credencial do Firebase Admin no startup.
   *
   * `false` (default) preserva o comportamento das T16.1–T16.7: o processo sobe, `/health/*`
   * responde e rota autenticada devolve `503`. Em um deploy de produção onde backup e sync são o
   * motivo de o servidor existir, subir sem credencial é subir quebrado — e `true` faz isso virar
   * falha de startup, que é visível, em vez de 503 em cada requisição, que parece instabilidade.
   */
  REQUIRE_FIREBASE_ADMIN: booleanFlag(false),

  /**
   * Exige credencial do Gemini no startup.
   *
   * Default `false` de propósito, e a assimetria com o Firebase é o desenho: o Coach é uma
   * capacidade opcional cuja ausência o Spark já sabe representar (`AI_PROVIDER_UNAVAILABLE`),
   * enquanto identidade é pré-requisito de tudo o que é autenticado.
   */
  REQUIRE_GEMINI: booleanFlag(false),

  /**
   * Interruptor de custo do Coach (T16.8 §120).
   *
   * `AI_ENABLED=false` faz `/v1/ai/coach` responder `503 AI_PROVIDER_UNAVAILABLE` **antes** de
   * qualquer chamada ao provider — e antes de consumir quota. Backup e sync continuam intactos:
   * o Coach caindo nunca pode derrubar o que protege dado do usuário.
   *
   * O código de erro é o que já existe de propósito: o Android o trata como
   * `AiCoachErrorKind.UNAVAILABLE` desde a T16.2, então desligar o Coach no servidor não exige
   * publicar um APK novo para o app entender a resposta.
   */
  AI_ENABLED: booleanFlag(true),

  /**
   * Interruptor de escrita do sync (T16.8 §121).
   *
   * `SYNC_WRITE_ENABLED=false` faz `POST /v1/sync/push` responder `503 SYNC_WRITE_DISABLED`. O
   * `GET /v1/sync/pull` continua funcionando: ele é somente leitura e não pode corromper nada.
   *
   * É seguro porque o cliente já trata 5xx como "não confirmado": a Outbox **permanece pendente**
   * e nada é apagado (§13.4). Pausar o push diante de um defeito grave é, portanto, uma pausa —
   * não uma perda.
   */
  SYNC_WRITE_ENABLED: booleanFlag(true),

  /**
   * Manutenção programada (T16.8 §123).
   *
   * Enquanto `true`, toda rota `/v1` responde `503 SERVICE_UNAVAILABLE`. `/health/live` e
   * `/health/ready` continuam respondendo normalmente — quem faz o healthcheck do container e do
   * proxy precisa distinguir "em manutenção" de "morto", e um readiness falso reiniciaria o
   * container no meio da manutenção.
   */
  MAINTENANCE_MODE: booleanFlag(false),

  // --- Notificações sociais (T17.5) ----------------------------------------------------

  /**
   * Kill switch de notificações push sociais.
   * Default `false`: seguro por padrão até produção ser explicitamente configurada.
   */
  SOCIAL_PUSH_ENABLED: booleanFlag(false),

  /** Intervalo em milissegundos entre varreduras do despachante de notificações pendentes. */
  PUSH_DISPATCH_INTERVAL_MS: z.coerce.number().int().min(1_000).max(600_000).default(60_000),

  /** Número máximo de tentativas de entrega de um evento antes de ser marcado como falha permanente. */
  PUSH_MAX_ATTEMPTS: z.coerce.number().int().min(1).max(20).default(5),

  /** Tamanho máximo do lote de eventos a processar em cada ciclo do despachante. */
  PUSH_BATCH_SIZE: z.coerce.number().int().min(1).max(200).default(50),
});

export type SparkEnv = z.infer<typeof envSchema>;
