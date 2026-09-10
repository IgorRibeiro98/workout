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
/**
 * O valor de desenvolvimento da chave HMAC de exclusão de conta.
 *
 * Exportado para que `AppConfig` possa recusá-lo em produção — e para que o teste possa afirmar
 * essa recusa sem repetir a string.
 */
export const DEVELOPMENT_DELETION_HMAC_KEY = 'spark-dev-deletion-hmac-key-not-for-production';

/**
 * Uma string opcional em que **vazio significa ausente**.
 *
 * Ferramentas de infraestrutura representam "não definido" como string vazia: o Compose expande
 * `${DATABASE_URL_DIRECT:-}` para `""` quando a variável não existe no `.env`, e um `-e VAR=` no
 * `docker run` faz o mesmo. Um `z.string().min(1).optional()` aceita `undefined` e recusa `""` —
 * e foi exatamente isso que derrubou a topologia de produção na T18.0.1 (`DATABASE_URL_DIRECT:
 * Too small`). Aqui, vazio e só-espaços viram `undefined` **antes** da validação, e a variável
 * segue o caminho de ausente: o fallback documentado, e não uma falha de startup.
 */
const optionalString = () =>
  z.preprocess(
    (value) => (typeof value === 'string' && value.trim() === '' ? undefined : value),
    z.string().min(1).optional(),
  );

export const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),

  /** Porta HTTP. O TLS é responsabilidade do reverse proxy (Caddy), não deste processo. */
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),

  /**
   * Connection string pooled para runtime (PostgreSQL / Neon).
   * Obrigatório e sem default: falha cedo no bootstrap se ausente.
   * Nunca é logado.
   */
  DATABASE_URL: z.string().min(1, 'DATABASE_URL é obrigatório'),

  /**
   * Connection string direta (não-pooled), usada para migrations e administração.
   *
   * Opcional: quando ausente — ou vazia, que é como o Compose representa ausência (T18.0.2) —
   * o backend usa `DATABASE_URL` para tudo. Nunca é logado.
   */
  DATABASE_URL_DIRECT: optionalString(),

  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),

  /** Tamanho mínimo do pool de conexões PostgreSQL. */
  DATABASE_POOL_MIN: z.coerce.number().int().min(0).max(50).default(2),

  /** Tamanho máximo do pool de conexões PostgreSQL (adequado para Neon Free e Cloud Run). */
  DATABASE_POOL_MAX: z.coerce.number().int().min(1).max(100).default(10),

  /** Timeout para aquisição de conexão do pool (ms). */
  DATABASE_CONNECTION_TIMEOUT_MS: z.coerce.number().int().min(500).max(60_000).default(5_000),

  /** Tempo máximo que uma conexão pode ficar ociosa no pool (ms). */
  DATABASE_IDLE_TIMEOUT_MS: z.coerce.number().int().min(1_000).max(300_000).default(30_000),

  /** Statement timeout no PostgreSQL (ms). */
  DATABASE_STATEMENT_TIMEOUT_MS: z.coerce.number().int().min(1_000).max(120_000).default(30_000),

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

  // --- Hardening social e exclusão de conta (T17.6) -------------------------------------

  /**
   * Chave HMAC dos tombstones de exclusão de conta (T17.6, endurecida na T17.10 §136/§137).
   *
   * O default abaixo é **de desenvolvimento**, e o nome dele diz isso. Ele existe para que teste e
   * `npm run start:dev` funcionem sem configuração; produção é obrigada a substituí-lo, e
   * `AppConfig.missingRequirements()` derruba o startup se não substituir.
   *
   * Duas razões, e a segunda é a que dói:
   *
   * 1. o tombstone guarda `HMAC(uid)` justamente para que a tabela não revele quais contas
   *    existiram. Com uma chave que está no repositório, qualquer pessoa confirma um uid
   *    conhecido — a propriedade que separa HMAC de hash simples é o segredo da chave;
   * 2. a chave é o que liga o tombstone ao uid. Subir em produção com o default e trocá-lo
   *    depois faria **todos** os tombstones existentes deixarem de casar: contas excluídas
   *    voltariam a passar pelo guard e a reconciliação de DR pararia de reconhecê-las. É
   *    ressurreição silenciosa, e o momento em que ela acontece é uma troca de configuração
   *    aparentemente inofensiva.
   */
  ACCOUNT_DELETION_HMAC_KEY: z.string().min(16).default(DEVELOPMENT_DELETION_HMAC_KEY),

  /** Caminho do arquivo append-only de tombstones de deleção para DR (anti-ressurreição). */
  DELETION_TOMBSTONES_FILE_PATH: z.string().min(1).default('/data/deletion_tombstones.tsv'),

  // --- Mídia social (T17.9) -------------------------------------------------------------

  /**
   * Raiz do armazenamento de mídia social (T17.9 §22/§26/§27/§28).
   *
   * **Sem default, e opcional aqui de propósito.** Em produção ela é obrigatória e a ausência
   * derruba o startup (`AppConfig.missingRequirements`), pela mesma razão de `DATABASE_URL` não
   * ter default: um valor silencioso apontaria para a camada efêmera do container ou para `/tmp`,
   * e a foto de todo mundo sumiria no próximo `docker compose up` — sem erro, sem log, sem
   * ninguém perceber até alguém abrir o Feed.
   *
   * Fora de produção, `AppConfig` deriva um diretório ao lado do banco: teste e desenvolvimento
   * precisam funcionar sem configuração, e ali o efêmero é o esperado.
   */
  SOCIAL_MEDIA_ROOT: z.string().min(1).optional(),

  /**
   * Teto de bytes de um upload **antes** do processamento (§18).
   *
   * 10 MiB: acima de qualquer foto de celular já comprimida, e ordens de grandeza abaixo do que
   * um cliente hostil tentaria. Ele é aplicado no parser de corpo **e** relido antes de decodificar
   * — o segundo é o que protege contra um `Content-Length` mentiroso.
   */
  SOCIAL_MEDIA_MAX_UPLOAD_BYTES: z.coerce
    .number()
    .int()
    .min(64 * 1024)
    .max(64 * 1024 * 1024)
    .default(10 * 1024 * 1024),

  /**
   * Quota de disco por conta, em bytes (§29/§30).
   *
   * Conta a mídia **real**: `PENDING` e `ATTACHED`. Upload abandonado ocupa quota até o cleanup,
   * porque ele ocupa disco até o cleanup — descontá-lo antes seria contabilizar uma liberação que
   * ainda não aconteceu, e é exatamente esse buraco que alguém usaria para encher a partição.
   */
  SOCIAL_MEDIA_MAX_USER_BYTES: z.coerce
    .number()
    .int()
    .min(1024 * 1024)
    .max(64 * 1024 * 1024 * 1024)
    .default(250 * 1024 * 1024),

  /** Intervalo entre varreduras da limpeza de mídia expirada/órfã (§39/§140). */
  SOCIAL_MEDIA_CLEANUP_INTERVAL_MS: z.coerce
    .number()
    .int()
    .min(10_000)
    .max(6 * 60 * 60 * 1000)
    .default(15 * 60 * 1000),

  // --- Object Storage (T18.1) -----------------------------------------------------------
  //
  // Onde os **bytes** vivem: fotos dos check-ins (T17.9) e o documento canônico dos backups
  // (T16.5). O PostgreSQL continua sendo a autoridade de metadata, ownership, hashes e estado;
  // ele só deixa de carregar o conteúdo pesado. A escolha do provider acontece em **um** lugar
  // (`object-storage.factory.ts`), e nenhum service, controller ou repositório sabe qual é.

  /**
   * `local` guarda os objetos no sistema de arquivos sob `SOCIAL_MEDIA_ROOT` — desenvolvimento,
   * teste e CI, sem Google Cloud, sem ADC, sem rede. `gcs` usa o bucket privado do Google Cloud
   * Storage com Application Default Credentials.
   *
   * Default `local` de propósito: a suíte e o CI não podem depender de projeto GCP, e um deploy
   * que queira o bucket precisa dizê-lo explicitamente — junto com `GCS_BUCKET_NAME`.
   */
  OBJECT_STORAGE_PROVIDER: z.enum(['local', 'gcs']).default('local'),

  /**
   * O bucket do provider `gcs`. Obrigatório quando `OBJECT_STORAGE_PROVIDER=gcs`, e a ausência
   * derruba o startup (`AppConfig.missingRequirements`) — nunca um bucket default no código.
   *
   * Vazio significa ausente, como `DATABASE_URL_DIRECT`: é o que o Compose injeta para uma
   * variável não definida. A forma é a das regras de nome de bucket do GCS (minúsculas, dígitos,
   * `-`, `_`, `.`; 3 a 222 caracteres).
   *
   * **Não existe credencial aqui.** Nenhuma `GCS_PRIVATE_KEY`, `GCS_CLIENT_EMAIL` ou JSON de
   * service account: o SDK autentica por ADC — a service account anexada ao serviço (Cloud Run,
   * T18.2) ou o `gcloud auth application-default login` do operador. Há teste estrutural.
   */
  GCS_BUCKET_NAME: z.preprocess(
    (value) => (typeof value === 'string' && value.trim() === '' ? undefined : value),
    z
      .string()
      .regex(/^[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]$/, 'nome de bucket GCS inválido')
      .optional(),
  ),

  /**
   * Teto de tempo de **uma** requisição HTTP ao Object Storage (ms).
   *
   * I/O externo sem teto é requisição presa para sempre segurando um worker. O SDK aplica este
   * valor por requisição e faz o retry dele por cima — bounded, sem laço manual. Só vale para o
   * provider `gcs`; o sistema de arquivos local não tem rede para esperar.
   */
  OBJECT_STORAGE_TIMEOUT_MS: z.coerce.number().int().min(1_000).max(120_000).default(30_000),

  /**
   * Intervalo entre varreduras da limpeza de objetos órfãos de **backup** (`backups/`).
   *
   * Órfão de backup é raro — o processo morreu entre gravar o objeto e o commit do PostgreSQL, ou
   * a retenção não conseguiu apagar um objeto —, e cada varredura é uma listagem paga no GCS.
   * Seis horas por default: frequente o bastante para não acumular, raro o bastante para custar
   * nada. O mínimo baixo existe para o teste, não para produção.
   */
  BACKUP_PAYLOAD_CLEANUP_INTERVAL_MS: z.coerce
    .number()
    .int()
    .min(10_000)
    .max(24 * 60 * 60 * 1000)
    .default(6 * 60 * 60 * 1000),
});

export type SparkEnv = z.infer<typeof envSchema>;
