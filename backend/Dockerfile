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

ENV NODE_ENV=production \
    PORT=8080 \
    SOCIAL_MEDIA_ROOT=/media

WORKDIR /app

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
# está; o Cloud Run service `spark-maintenance` e o Job `spark-db-migrate` sobrepõem o comando na
# própria definição do serviço/job (`--command`/`--args` do `gcloud`, ver `ops/gcp/deploy-cloud-
# run.sh`) para `node dist/maintenance-main.js` e `node dist/cli/migrate-database.js`,
# respectivamente — nunca três imagens quase iguais, e nunca `latest` como identidade de deploy.
CMD ["node", "dist/main.js"]
