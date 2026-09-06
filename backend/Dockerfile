# Spark Backend — imagem de runtime (T16.0).
#
# Duas decisões que valem explicação:
#
# 1. `bookworm-slim` em vez de Alpine: `better-sqlite3` é um módulo nativo e publica binários
#    pré-compilados para glibc. Em Alpine (musl) ele precisaria ser compilado a cada build, o que
#    é mais lento e menos reproduzível para nenhum ganho relevante de tamanho aqui.
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
    DATABASE_PATH=/data/spark.db

WORKDIR /app

# `node` é um usuário sem privilégios já presente na imagem base (uid 1000). O processo nunca roda
# como root, e `/data` pertence a ele para que o volume seja gravável sem elevar privilégio.
RUN mkdir -p /data && chown -R node:node /data

COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --chown=node:node migrations ./migrations
COPY --chown=node:node package.json ./

USER node

EXPOSE 8080

# O banco vive no volume, nunca na camada efêmera do container.
VOLUME ["/data"]

# Healthcheck sem curl: a imagem slim não traz um, e o `fetch` global do Node 22 resolve.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/health/live').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

# Sem shell wrapper: o Node é PID 1 e recebe SIGTERM/SIGINT diretamente, que é o que o graceful
# shutdown do `main.ts` escuta.
CMD ["node", "dist/main.js"]
