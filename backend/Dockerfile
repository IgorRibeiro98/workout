# Spark Backend — imagem de runtime (T16.0).
#
# Duas decisões que valem explicação:
#
# 1. `bookworm-slim` em vez de Alpine: módulos nativos como `sharp` (libvips, pipeline de
#    imagem dos check-ins) publicam binários pré-compilados para glibc (`@img/sharp-linux-x64`).
#    Em Alpine (musl), eles precisariam ser compilados do zero, o que é mais lento e menos
#    reproduzível para nenhum ganho relevante de tamanho aqui.
#    `docker build` é gate de CI (T17.9 §143): se o binário não vier, a imagem não sai.
# 2. Multi-stage: o estágio de runtime recebe apenas dependências de produção e o `dist` já
#    compilado. Nem TypeScript, nem ESLint, nem Jest, nem código-fonte entram na imagem final.

# ---------- build ----------
FROM node:22.22.1-bookworm-slim AS build

WORKDIR /app

# Instala com o lockfile: mesma árvore de dependências em qualquer máquina e no CI.
COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json tsconfig.build.json nest-cli.json ./
COPY src ./src
RUN npm run build

# Árvore de produção separada, já sem devDependencies.
RUN npm prune --omit=dev


# ---------- runtime ----------
FROM node:22.22.1-bookworm-slim AS runtime

# `SOCIAL_MEDIA_ROOT` **não** entra aqui, e a ausência é deliberada (T18.3.2).
#
# Com ela embutida, a imagem chegava em produção já satisfazendo o portão que `app-config.ts`
# (`missingRequirements`) existe para aplicar: rodar esta imagem com o provider `local` e **sem
# volume montado** passava a subir normalmente e a gravar as fotos num volume anônimo — exatamente
# a perda silenciosa que o portão descreve. Quem declara a raiz é quem sabe onde ela está montada:
# `docker-compose.yml` e `docker-compose.prod.yml` (os dois já declaram `SOCIAL_MEDIA_ROOT: /media`
# ao lado do volume correspondente). O deploy Cloud Run usa `OBJECT_STORAGE_PROVIDER=gcs` e não
# precisa de raiz local nenhuma.
ENV NODE_ENV=production \
    PORT=8080

WORKDIR /app

# As ferramentas nativas do PostgreSQL (T18.3 §2): `pg_dump`, `pg_restore` e `psql` da major 18,
# do repositório oficial PGDG — o `postgresql-client` do próprio bookworm é a 15, e um `pg_dump`
# com major MENOR que a do servidor se recusa a rodar ("server version mismatch" — foi exatamente o
# que o primeiro backup real reportou com a 17: o Neon roda PostgreSQL 18). Uma major MAIOR que a
# do servidor é aceita, então a 18 serve ao Neon (18) e ao PostgreSQL 17 do CI. É o que o Job `spark-db-backup`
# (`dist/cli/db-backup.js`) e o ensaio de restauração (`dist/cli/db-restore-drill.js`) invocam por
# processo, com a conexão entrando por variáveis de ambiente da libpq — nunca pelo argv.
#
# A mesma imagem continua servindo a API, a manutenção e o Job de migration (T18.2 §2): quatro
# superfícies, um digest. Uma imagem separada só para o backup dobraria o que o deploy rastreia e
# o que a retenção do Artifact Registry precisa proteger, por ~40 MB de binários que a API
# simplesmente não chama. `ca-certificates` fica: é o que `PGSSLROOTCERT=system` (verify-full
# contra o Neon) lê. `curl` e `gnupg` entram só para registrar o repositório e saem na mesma camada.
#
# O pacote `postgresql-client-18` é **extraído** (`dpkg-deb -x`), não instalado: instalá-lo
# arrasta `postgresql-client-common` e, com ele, o Perl inteiro (~50 MB) só para os wrappers de
# `/usr/bin` — que aqui não servem para nada, porque os binários reais vivem em
# `/usr/lib/postgresql/18/bin` e entram no `PATH` diretamente. As bibliotecas de que eles
# dependem (`libpq5`, readline, zlib, lz4, zstd) são instaladas normalmente, e o `--version` no
# fim prova que os três binários carregam.
#
# A versão é **pinada** (T18.3.2): sem o pin, o mesmo commit construía binários diferentes conforme
# o dia — o repositório PGDG publica uma revisão nova e a imagem muda sem ninguém ter mudado nada.
# Um `ARG` porque subir a versão é uma alteração revisada de uma linha (e um
# `--build-arg PG_CLIENT_VERSION=` resolve na hora quando o PGDG rotaciona o pacote antigo para
# fora do repositório, que é o único jeito de este passo falhar: alto, no build, antes do deploy).
ARG PG_CLIENT_VERSION=18.6-1.pgdg12+2
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl gnupg; \
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
      | gpg --dearmor -o /usr/share/keyrings/postgresql-archive-keyring.gpg; \
    echo "deb [signed-by=/usr/share/keyrings/postgresql-archive-keyring.gpg] https://apt.postgresql.org/pub/repos/apt bookworm-pgdg main" \
      > /etc/apt/sources.list.d/pgdg.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends libpq5 libreadline8 zlib1g liblz4-1 libzstd1; \
    cd /tmp && apt-get download "postgresql-client-18=${PG_CLIENT_VERSION}"; \
    dpkg-deb -x /tmp/postgresql-client-18_*.deb /; \
    rm -f /tmp/postgresql-client-18_*.deb; \
    apt-get purge -y --auto-remove curl gnupg; \
    rm -rf /var/lib/apt/lists/* /etc/apt/sources.list.d/pgdg.list; \
    /usr/lib/postgresql/18/bin/pg_dump --version; \
    /usr/lib/postgresql/18/bin/pg_restore --version; \
    /usr/lib/postgresql/18/bin/psql --version
ENV PATH="/usr/lib/postgresql/18/bin:${PATH}"

# `node` é um usuário sem privilégios já presente na imagem base (uid 1000). O processo nunca roda
# como root, e `/media` pertence a ele para que o volume seja gravável sem elevar privilégio.
#
# `/media` é o volume de mídia social da T17.9 (§26).
#
# `0750`: o processo lê e escreve; o grupo compartilhado (T16.8.1 §3) lê, que é o que o backup
# precisa. Ninguém mais alcança.
RUN mkdir -p /media \
 && chown -R node:node /media \
 && chmod 0750 /media

COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
# Só as migrations do PostgreSQL (T18.0.2): `migrations/*.sql` na raiz são a sequência SQLite
# histórica, mantida para os testes de migração legada, e não têm o que fazer numa imagem cujo
# runtime é exclusivamente PostgreSQL (`MIGRATIONS_DIRNAME`).
COPY --chown=node:node migrations/postgres ./migrations/postgres
COPY --chown=node:node package.json ./

USER node

EXPOSE 8080

# A mídia vive em volume, nunca na camada efêmera do container (T17.9 §26).
#
# Sem isto, `docker compose up` recriando o container apagaria a foto de todo mundo — em silêncio,
# porque escrever num diretório efêmero funciona perfeitamente até alguém reiniciar. Em produção
# `SOCIAL_MEDIA_ROOT` precisa apontar para um volume real, e o processo **recusa subir** sem a
# variável definida (§28, `AppConfig.missingRequirements`).
VOLUME ["/media"]

# Healthcheck sem curl: a imagem slim não traz um, e o `fetch` global do Node 22 resolve.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/health/live').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

# Sem shell wrapper: o Node é PID 1 e recebe SIGTERM/SIGINT diretamente, que é o que o graceful
# shutdown do `main.ts` escuta.
#
# Esta é a única imagem do Spark Backend (T18.2 §2): o Cloud Run API service usa este `CMD` como
# está; o Cloud Run service `spark-maintenance` e os Jobs `spark-db-migrate`, `spark-db-backup`
# (T18.3) e `spark-storage-audit` (T18.3) sobrepõem o comando na própria definição do serviço/job
# (`--command`/`--args` do `gcloud`, ver `ops/gcp/deploy-cloud-run.sh`) para
# `node dist/maintenance-main.js`, `node dist/cli/migrate-database.js`, `node dist/cli/db-backup.js`
# e `node dist/cli/storage-audit.js`, respectivamente — nunca imagens quase iguais, e nunca
# `latest` como identidade de deploy.
CMD ["node", "dist/main.js"]
