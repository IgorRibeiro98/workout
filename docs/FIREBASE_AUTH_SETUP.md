# Conta Spark — configuração do Firebase Authentication

- **Tarefa:** T16.1 — Conta opcional + Firebase Auth.
- **Escopo:** o que **uma pessoa** precisa fazer no Firebase Console e na VPS. O código já está
  pronto e não depende de nenhuma alteração adicional.

> Nenhum segredo aparece neste documento, e nenhum deve ser colado nele. O `google-services.json`
> e a service account do Admin SDK ficam fora do Git — ambos já estão no `.gitignore`.

---

## Estado atual, verificado no repositório

| Item | Estado |
| --- | --- |
| Fronteira de autenticação no Android (`AuthGateway`, `FirebaseAuthGateway`) | **VERIFIED** — implementado e coberto por teste |
| Verificação de token no backend (`AuthTokenVerifier`, `FirebaseAuthTokenVerifier`) | **VERIFIED** — implementado e coberto por teste |
| `GET /v1/auth/me` protegido | **VERIFIED** — 401 sem token, 503 sem credencial, 200 com token válido |
| Provedor **Google** habilitado no Firebase Console | **MANUAL ACTION REQUIRED** |
| `oauth_client` no `app/google-services.json` | **MANUAL ACTION REQUIRED** — hoje está **vazio** |
| SHA-1 / SHA-256 cadastrados | **NOT VERIFIED** — exige acesso ao console |
| Service account do Admin SDK na VPS | **MANUAL ACTION REQUIRED** |
| Login real Google ponta a ponta | **NOT VERIFIED** — exige aparelho, console e backend reais |

Enquanto o provedor Google não estiver habilitado, o Spark **funciona normalmente**: a área de
Conta no Perfil se apresenta como indisponível ("Entrar com o Google não está configurado neste
aplicativo") e todo o resto — treino, execução, histórico, templates, gamificação, Coach IA —
segue igual. Conta é opcional por desenho, não por falta de configuração.

---

## Por que o `default_web_client_id` decide tudo

O fluxo Sign in with Google via Credential Manager exige um **Web Client ID** (server client ID).
Ele não está no código, e não deve estar: o plugin `com.google.gms.google-services` lê o bloco
`oauth_client` do `app/google-services.json` e gera o recurso `default_web_client_id`.

Hoje esse bloco está assim:

```json
"oauth_client": []
```

Sem entrada de `client_type: 3`, o recurso não é gerado. Por isso `GoogleServerClientId` resolve o
recurso **pelo nome**, e não por `R.string.default_web_client_id`: uma referência direta faria o app
inteiro parar de compilar por causa de uma etapa de console pendente.

Depois do passo 1 abaixo, o arquivo novo traz o bloco preenchido, o recurso passa a existir e o
botão "Continuar com Google" aparece sozinho. **Nenhuma mudança de código é necessária.**

---

## 1. Habilitar o provedor Google — MANUAL ACTION REQUIRED

No [Firebase Console](https://console.firebase.google.com/), projeto **`spark-36b11`**:

1. **Build → Authentication → Get started** (se ainda não estiver ativado).
2. Aba **Sign-in method**.
3. **Add new provider → Google → Enable**.
4. Preencher **Project public-facing name** e **Project support email** (obrigatório: sem e-mail de
   suporte o provedor não salva).
5. **Save**.

Não habilite nenhum outro provedor. A T16.1 suporta apenas Google — e-mail/senha, telefone, Apple,
Facebook, GitHub, passkeys e **anonymous auth** estão fora de escopo por decisão. Em particular,
anonymous auth **não** deve ser usado para representar "usuário sem conta": sem conta significa
`FirebaseUser == null`, e o app funciona assim.

## 2. Cadastrar as impressões digitais (SHA-1 e SHA-256) — MANUAL ACTION REQUIRED

O Google Sign-In no Android exige que a assinatura do APK esteja registrada no projeto.

Obtenha as impressões:

```bash
# Todas as variantes de uma vez
JAVA_HOME=... ANDROID_HOME=... sh ./gradlew :app:signingReport

# Ou só a de depuração
keytool -list -v \
  -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android
```

No console: **⚙ Project settings → General → Your apps → Android app
(`com.aistudio.workout.v2`) → Add fingerprint**. Cadastre:

- SHA-1 **e** SHA-256 do keystore de **depuração** (para testar no aparelho de desenvolvimento);
- SHA-1 **e** SHA-256 do keystore de **release**, quando existir;
- se o app for distribuído pelo Google Play, também as do **Play App Signing**
  (Play Console → Setup → App integrity).

Sem a impressão certa, o seletor de contas abre e a autenticação falha depois — o app trata isso
como erro recuperável e continua funcionando.

## 3. Baixar o `google-services.json` atualizado — MANUAL ACTION REQUIRED

Ainda em **Project settings → General → Your apps**, baixe o `google-services.json` novo e
substitua `app/google-services.json`.

Confirme que ele agora tem uma entrada de client web:

```bash
python3 -c "import json;print(json.load(open('app/google-services.json'))['client'][0]['oauth_client'])"
```

Deve aparecer ao menos um item com `"client_type": 3`. É ele que vira `default_web_client_id`.

> O arquivo continua **não versionado** (`.gitignore`), pela política já estabelecida no projeto.
> Ele não é credencial privada — é configuração de cliente, protegida por App Check e pelas
> restrições de chave do console — mas a decisão de não versioná-lo é anterior a esta tarefa e não
> foi alterada aqui. Consequência conhecida: um checkout limpo falha em
> `processDebugGoogleServices` até o arquivo existir.

## 4. Service account do Admin SDK, para o backend — MANUAL ACTION REQUIRED

O Spark Backend roda em VPS — ambiente não-Google —, então precisa da credencial explicitamente.

1. **⚙ Project settings → Service accounts → Firebase Admin SDK → Generate new private key**.
2. Guarde o arquivo **fora do repositório**. Na VPS, por exemplo:

   ```bash
   sudo install -o root -g docker -m 0640 ~/Downloads/spark-firebase-admin.json /etc/spark/firebase-admin.json
   ```

3. Aponte o backend para o **caminho** (nunca para o conteúdo):

   ```bash
   # backend/.env  (não versionado)
   GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/spark-firebase-admin.json
   ```

4. Monte o arquivo somente-leitura no container — as duas linhas já estão em
   `backend/docker-compose.yml`, comentadas:

   ```yaml
   environment:
     GOOGLE_APPLICATION_CREDENTIALS: /run/secrets/spark-firebase-admin.json
   volumes:
     - /etc/spark/firebase-admin.json:/run/secrets/spark-firebase-admin.json:ro
   ```

É proibido, e há teste que verifica: a chave privada no Git, dentro da imagem Docker, no
`docker-compose.yml`, no `.env.example` ou no Android. O Android **nunca** recebe credencial de
Admin — ele só produz ID Tokens.

Sem essa credencial, o backend sobe normalmente, `/health/live` e `/health/ready` continuam
públicos e `/v1/auth/me` responde **503** (incapaz de verificar). Ele nunca responde 200 sem ter
verificado o token: não existe modo "autenticação desligada".

## 5. Apontar o app para o backend — opcional

Enquanto não houver VPS provisionada, deixe em branco. Quando houver:

```bash
# local.properties (não versionado), ou -P na linha de comando
sparkBackendBaseUrl=https://api.exemplo.com
```

Sem esse valor, o cliente responde `NotConfigured` e nenhuma requisição sai. O núcleo do Spark não
depende dele para nada.

---

## Validação ponta a ponta — NOT VERIFIED

Depois dos passos 1 a 4, este é o roteiro que fecha a cadeia. Ele exige aparelho real, console
configurado e backend rodando, e **não foi executado** — nenhuma etapa abaixo pode ser marcada
como verificada sem execução real.

```text
 1. abrir o Spark                       → funciona sem conta
 2. Perfil → Conta Spark                → estado "Entrar é opcional"
 3. tocar "Continuar com Google"        → seletor de contas abre (e só aqui)
 4. escolher a conta
 5. Firebase cria/restaura o usuário    → estado "Conectado", nome e e-mail
 6. tocar "Verificar identidade no servidor"   (só em build de depuração)
 7.   → o app pede um ID Token ao Firebase
 8.   → GET /v1/auth/me com Authorization: Bearer <token>
 9.   → backend verifica pelo Admin SDK e devolve { "uid": ... }
10.   → "Servidor confirmou o mesmo usuário."
11. fechar e reabrir o app              → continua "Conectado", sem seletor
12. tocar "Sair da conta"               → volta para "Entrar é opcional"
13. conferir que o histórico de treinos continua idêntico em todos os passos
```

Também vale testar os caminhos ruins:

- cancelar o seletor no passo 4 → volta ao estado deslogado, **sem** mensagem de erro;
- desligar a rede e tentar entrar → erro recuperável, com "Tentar novamente";
- desligar o backend e repetir o passo 6 → "Servidor indisponível. Sua conta segue conectada."

Verificação equivalente pelo lado do servidor, sem aparelho — com a composição **local**
(`backend/docker-compose.yml`), que publica `127.0.0.1:8080` de propósito para desenvolvimento.
Em **produção** o backend não publica porta nenhuma, e o health é outro:
ver [docs/operations/RUNBOOK.md](./operations/RUNBOOK.md).

```bash
curl -i http://127.0.0.1:8080/health/live      # 200, público
curl -i http://127.0.0.1:8080/v1/auth/me       # 401 UNAUTHENTICATED
curl -i -H "Authorization: Bearer <id-token>" http://127.0.0.1:8080/v1/auth/me
```

---

## O que continua fora de escopo

Exclusão de conta **não** foi implementada, e isso é deliberado. Quando o Spark tiver dados online,
apagar uma conta precisará coordenar identidade no Firebase, dados no Spark Backend, backup, mídia
e social. Um `FirebaseUser.delete()` isolado hoje criaria um fluxo incompleto — a obrigação fica
registrada como **requisito pré-release da fase online/hardening (T16.8)**.

Também fora: e-mail/senha, SMS, anonymous auth, passkeys, account linking, `syncId`, `deviceId`,
outbox, backup, restore, sync, resolução de conflitos e qualquer tabela de usuários no servidor.
