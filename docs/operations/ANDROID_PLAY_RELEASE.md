# Bundle Android assinado para o Google Play (T18.4)

> **Status:** `IMPLEMENTED` — fluxo, task Gradle e testes offline exercitados; a assinatura real com a
> upload key só acontece num terminal, e é `VERIFIED` apenas quando um operador a executou e o
> script terminou com `RESULT: PASS` (ver o relatório da T18.4 para o estado da última execução).

O único procedimento canônico para produzir o Android App Bundle (`.aab`) do Spark destinado ao
Google Play é:

```bash
./ops/android/build-play-bundle.sh
```

Ele faz tudo — preflight, validação da configuração de release, testes, lint, `bundleRelease`,
assinatura com a upload key, verificação criptográfica, artefato em `dist/` e checksum — e só
imprime `RESULT: PASS` (exit `0`) quando **todas** as etapas passaram. Qualquer outra saída é
falha, e falha não deixa artefato em `dist/`.

A única coisa que ele **não** faz é conhecer a senha da upload key: o `jarsigner` a pede
interativamente no terminal, com eco desligado. Não existe opção, variável de ambiente, arquivo ou
prompt do script para ela — e não vai existir.

## 1. Propósito e fronteira

```text
Spark repository (main, worktree limpa)
      │
      ├── preflight: java/javac/keytool/jarsigner/git/sha256sum/gradlew/SDK, TTY
      ├── :app:printPlayReleaseMetadata → applicationId, versionCode, versionName, SDKs, backend
      ├── applicationId == com.aistudio.workout.v2
      ├── targetSdk ≥ mínimo, compileSdk ≥ targetSdk
      ├── backend efetivo == produção (Cloud Run)
      ├── app/google-services.json (existe, não vazio, do applicationId certo)
      ├── upload key: keystore, alias, PrivateKeyEntry, SHA-1 == registrado no Play
      └── dist/spark-<versionName>-<versionCode>.aab ainda não existe
      ▼
:app:testDebugUnitTest → :app:lintVitalRelease → :app:verifyReleaseEndpointGate
      ▼
:app:bundleRelease → app/build/outputs/bundle/release/app-release.aab (SEM assinatura — conferido)
      ▼
cópia temporária em dist/.build-play-bundle.XXXXXX/
      ▼
jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore ~/.android/spark-upload.jks <cópia> spark-upload
      │   ← a senha é digitada aqui, no terminal, por quem está publicando
      ▼
jarsigner -verify -strict -keystore <jks> -protected <cópia> spark-upload   (exige "jar verified.")
keytool -printcert -jarfile <cópia>                                          (1 signer, SHA-1 esperado)
      ▼
sha256sum → ln (atômico, nunca sobrescreve) →
dist/spark-<versionName>-<versionCode>.aab
dist/spark-<versionName>-<versionCode>.aab.sha256
      ▼
resumo + RESULT: PASS
```

O fluxo termina no **AAB assinado localmente + SHA-256**. O upload continua manual, pelo Play
Console (§9). Nada aqui autentica no Play, usa Service Account, Developer API, cria track ou
promove release — isso é, deliberadamente, uma tarefa futura e separada.

### Upload key × App Signing Key

| | Upload key | Google Play App Signing key |
| --- | --- | --- |
| Quem guarda | Você, em `~/.android/spark-upload.jks` | O Google, no Play Console |
| O que assina | O **AAB** que você envia ao Play | O **APK** que o Play entrega aos usuários |
| Se vazar / perder | Redefinível no Play Console (§10) | Não é sua para perder |
| Este fluxo toca? | **Sim** — só ela | **Nunca** |

O Play verifica que o AAB enviado foi assinado pela upload key registrada, descarta essa
assinatura e re-assina os APKs com a App Signing key. Por isso o fingerprint que este fluxo
confere é o da **upload key**, e por isso nenhuma etapa daqui altera, exporta ou automatiza a App
Signing key.

## 2. Pré-requisitos

### Setup inicial (Linux, sem Android Studio)

| O quê | Como | Por quê |
| --- | --- | --- |
| JDK 17+ completo (`java`, `javac`, `keytool`, `jarsigner`) | Temurin 21: `curl -sL -o jdk21.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"`, extrair, `export JAVA_HOME=<jdk>` | Gradle 9.3.1 + AGP 9.1.1 exigem 17+; `jarsigner`/`keytool` só existem no JDK (um JRE não serve). |
| Android SDK command-line tools | `commandlinetools-linux-*_latest.zip` em `<sdk>/cmdline-tools/latest/`; `yes \| sdkmanager --sdk_root=<sdk> --licenses` | O AGP resolve o SDK por `local.properties` (`sdk.dir=<sdk>`) ou `ANDROID_HOME`. O script confere, não cria. |
| `platforms;android-36`, `build-tools;36.0.0`, `platform-tools` | `sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"` | `compileSdk = 36` e `targetSdk = 36` (política do Play desde 31/08/2026). |
| Gradle Wrapper | Já está no repositório (`./gradlew`, executável) | Nunca um Gradle global. |
| `git`, `sha256sum`, `mktemp`, `bash` 4+ | Pacotes base da distribuição | Rastreabilidade, checksum, área temporária. |

O CI (`.github/workflows/android.yml`) usa JDK 21 (Temurin) e é a referência de versão; localmente,
`JAVA_HOME=~/spark-toolchain/jdk` é o toolchain documentado nas memórias do projeto.

### Upload key

| | Valor |
| --- | --- |
| Keystore | `~/.android/spark-upload.jks` (formato **JKS**) |
| Alias | `spark-upload` |
| SHA-1 do certificado público (registrado no Play) | `E1:32:79:F1:D1:79:C1:FE:4B:88:D1:54:A1:BB:A3:51:F2:35:E7:56` |
| Senha | Só na cabeça do operador. Nunca em arquivo, variável, argumento, histórico de shell, log, relatório ou conversa com um agente. |

O caminho e o alias podem ser sobrescritos por `SPARK_UPLOAD_KEYSTORE` e `SPARK_UPLOAD_KEY_ALIAS`;
o fingerprint continua sendo exigido seja qual for o arquivo. O certificado **público** é lido
sem senha (`keytool -list -protected`) — é isso que permite recusar a keystore errada antes de
gastar o build, e é por isso que o formato canônico é JKS: um PKCS12 cifra também os certificados
e o script falha dizendo isso.

Confira o fingerprint local a qualquer momento, sem senha:

```bash
keytool -list -v -protected -keystore ~/.android/spark-upload.jks -alias spark-upload | grep SHA1
```

### Backend de release

O release compila `BuildConfig.SPARK_BACKEND_BASE_URL` a partir da propriedade Gradle
`sparkBackendBaseUrl` (`providers.gradleProperty`). A configuração recomendada é em
`~/.gradle/gradle.properties`:

```properties
sparkBackendBaseUrl=https://spark-backend-965678405850.southamerica-east1.run.app
```

Duas barreiras, que respondem a perguntas diferentes:

1. **`app/build.gradle.kts`** (T16.8): recusa HTTP, `localhost`, `10.0.2.2`, IP/rede privada e
   host indecifrável — o build de release nem começa. Vazio passa (significa "nuvem desligada").
2. **`ops/android/build-play-bundle.sh`** (T18.4): exige que o valor **efetivo** seja exatamente
   `EXPECTED_BACKEND_BASE_URL` de `ops/android/play-release.conf`. Vazio, staging ou qualquer outro
   host é `FAIL`. O script lê o valor pela task `:app:printPlayReleaseMetadata`, não procurando
   texto em arquivo — o que está em `~/.gradle/gradle.properties`, em `gradle.properties` ou em
   `-P` é resolvido pelo próprio Gradle.

### `app/google-services.json`

Configuração do projeto Firebase do Spark, baixada do console, **não versionada**
(`.gitignore`). O script exige que exista, seja um arquivo regular, não esteja vazio e contenha um
cliente com `package_name` igual ao `applicationId` efetivo. O conteúdo nunca é impresso, copiado
para `dist/` ou passado a log.

### Versionamento — decisão explícita, sempre

`versionCode` e `versionName` vivem em `app/build.gradle.kts` e **não são alterados pelo script**.
Antes de gerar um release:

1. suba `versionCode` (inteiro, estritamente crescente) e `versionName` (o que o usuário vê);
2. commite em `main` (o script recusa worktree suja e branch diferente de `main`);
3. só então rode o script.

> **Antes de enviar ao Google Play, `versionCode` precisa ser maior que qualquer versão já enviada
> ao Play Console** — em qualquer track, inclusive as internas. O script não consulta o Play e não
> tem como saber o que já foi enviado: ele gera tecnicamente o bundle que o código descreve. Se
> `dist/spark-<versionName>-<versionCode>.aab` já existe, ele recusa — é o sinal mais barato de
> "esqueci de subir a versão".

## 3. O comando

```bash
# na raiz do repositório, ou de qualquer diretório — o script resolve a raiz sozinho
./ops/android/build-play-bundle.sh
```

Opções:

| Opção | Efeito |
| --- | --- |
| *(nenhuma)* | Fluxo completo. Exige terminal interativo (stdin **e** stdout num TTY) — é onde a senha é digitada. |
| `--check` | Tudo até o bundle validado (preflight, configuração, testes, lint, portão, `bundleRelease`, estado sem assinatura). **Não assina, não cria `dist/`.** Termina em `RESULT: CHECK PASS`. Serve para um agente sem TTY validar o release antes de entregar o comando ao usuário. |
| `--help` | Uso. |

Não existe `--force`, `--storepass`, `--keypass`, `--password` nem variante deles: qualquer
argumento com essa forma é recusado com explicação. `KEYSTORE_PASSWORD`, `PLAY_KEYSTORE_PASSWORD`
e afins definidos no ambiente também são recusados — o fluxo não os lê, e alguém precisa saber
que eles estão expostos a todo processo filho da sessão.

Rode sem redirecionar stdout (`| tee`, `> log`): o `jarsigner` só desliga o eco da senha quando
stdin e stdout são um terminal, e o script recusa qualquer outra coisa.

## 4. Output esperado

```text
==> Preflight
Android SDK ........... /home/…/android-sdk (local.properties)
Java .................. /home/…/jdk/bin/java
Gradle ................ /home/…/workout/gradlew
Modo .................. sign

==> Estado do Git
Branch ................ main
Commit ................ <sha completo>
Worktree .............. clean

==> Metadados efetivos do release (:app:printPlayReleaseMetadata)
Application ID ........ com.aistudio.workout.v2
Version name .......... 1.0.1
Version code .......... 3
Compile SDK ........... 36
Target SDK ............ 36
Backend ............... https://spark-backend-965678405850.southamerica-east1.run.app

==> Validação da configuração de release
Firebase config ....... PASS

==> Upload key
Keystore .............. /home/…/.android/spark-upload.jks
Alias ................. spark-upload
Certificado SHA-1 ..... E1:32:79:F1:D1:79:C1:FE:4B:88:D1:54:A1:BB:A3:51:F2:35:E7:56
Upload key ............ PASS
Artefato .............. dist/spark-1.0.1-3.aab

==> Testes unitários (:app:testDebugUnitTest)
…  (saída do Gradle)
==> Lint de release (:app:lintVitalRelease)
==> Portão de endereço (:app:verifyReleaseEndpointGate)
==> Bundle de release (:app:bundleRelease)
Bundle ................ PASS (app/build/outputs/bundle/release/app-release.aab)

==> Estado do bundle intermediário
Bundle intermediário .. sem assinatura (esperado)

==> Assinatura com a upload key (jarsigner)
A senha da upload key será pedida pelo jarsigner. Digite-a no terminal: este script não a lê,
não a guarda e não a repassa. (Keystore /home/…/.android/spark-upload.jks, alias spark-upload.)
Enter Passphrase for keystore:            ← digite a senha; nada é ecoado
jar signed.

Warning:
The signer's certificate is self-signed.  ← normal: a upload key é autoassinada

==> Verificação da assinatura
jarsigner -verify ..... jar verified (strict, alias spark-upload)
Signer SHA-1 .......... E1:32:79:F1:D1:79:C1:FE:4B:88:D1:54:A1:BB:A3:51:F2:35:E7:56
Signature ............. PASS

==> Publicação em dist/
Artefato .............. /home/…/workout/dist/spark-1.0.1-3.aab
Checksum .............. /home/…/workout/dist/spark-1.0.1-3.aab.sha256

Spark — Google Play Bundle

Git
  Branch ............... main
  Commit ............... abcdef123456
  Worktree ............. clean

Android
  Application ID ....... com.aistudio.workout.v2
  Version name ......... 1.0.1
  Version code ......... 3
  Compile SDK .......... 36
  Target SDK ........... 36

Release
  Backend .............. https://spark-backend-965678405850.southamerica-east1.run.app
  Firebase config ...... PASS
  Unit tests ........... PASS
  Lint release ......... PASS
  Bundle ............... PASS
  Upload key ........... PASS
  Signature ............ PASS

Artifact
  dist/spark-1.0.1-3.aab
  dist/spark-1.0.1-3.aab.sha256

SHA-256
  <64 hex>

RESULT: PASS
```

A narrativa (`==>`, `ERROR:`) vai para **stderr**; o resumo final vai para **stdout**. Em falha, a
última linha é `RESULT: FAIL` e o exit code é `≠ 0` — nunca há artefato em `dist/` nesse caso.

Tempo típico: os passos de Gradle dominam (testes ≈ 1–2 min, lint e bundle ≈ 1–3 min cada em cache
frio); o resto leva segundos.

## 5. Onde fica o AAB, e o checksum

```text
dist/spark-<versionName>-<versionCode>.aab          ← o que vai para o Play Console
dist/spark-<versionName>-<versionCode>.aab.sha256   ← saída de `sha256sum <nome>`, conferível com:
                                                       (cd dist && sha256sum -c spark-1.0.1-3.aab.sha256)
```

`dist/` está no `.gitignore`. O AAB **intermediário** do Gradle
(`app/build/outputs/bundle/release/app-release.aab`) nunca é alterado: a assinatura acontece numa
cópia, e ele é removido antes de cada `bundleRelease` para que um arquivo antigo jamais passe por
novo.

Para regenerar conscientemente a **mesma** versão (por exemplo, o Play recusou por um motivo que
não muda `versionCode`), remova `dist/spark-<v>-<c>.aab` e o `.sha256` correspondente e rode de
novo. O script não sobrescreve.

## 6. Como subir manualmente no Play Console

1. Play Console → app **Spark** (`com.aistudio.workout.v2`) → *Testing* / *Production* → a track
   desejada → **Create new release**.
2. Envie `dist/spark-<versionName>-<versionCode>.aab`.
3. O console valida a assinatura contra a upload key registrada; se recusar por "wrong key",
   volte ao §10 — o fingerprint local e o registrado divergem, e o script deveria ter pego isso.
4. Confira `versionCode`/`versionName` na tela do release, preencha as release notes, revise e
   publique na track.
5. Guarde o SHA-256 junto com o registro do release (o commit SHA do resumo é o que liga o
   artefato ao código).

Nada disso é automatizado nesta tarefa, de propósito.

## 7. Política de segredos

- A senha da upload key **não existe** em: repositório, `gradle.properties`, `local.properties`,
  `.env`, GitHub Secrets, variável de ambiente, argumento de linha de comando, arquivo temporário,
  log, histórico de shell, relatório, ou qualquer conversa com um agente.
- A senha é digitada **uma vez por release**, no prompt do `jarsigner`, com eco desligado. Se o
  JKS tem senha de chave diferente da senha do keystore, o `jarsigner` pede as duas.
- Não use `-storepass`/`-keypass`, `read -s` + variável, `expect`, `sshpass`, keyring automático
  nem "só desta vez". O script recusa o que consegue detectar; o resto é disciplina.
- Não há `signingConfig` de release em `app/build.gradle.kts`, e não deve haver: Gradle gera o
  bundle sem assinatura, o `jarsigner` assina. Um bundle que chegue já assinado do Gradle faz o
  script **falhar** ("assinado de forma inesperada") — é o sinal de que alguém criou um
  `signingConfig`, e isso precisa ser revisado antes de qualquer release.
- A keystore vive fora do repositório (`~/.android/`), com permissão `600`. `*.jks` e
  `*.keystore` estão no `.gitignore` como defesa em profundidade; certificados públicos `.pem`
  não estão, de propósito.
- `app/google-services.json` não é segredo no sentido estrito, mas é configuração do projeto
  Firebase real: continua fora do Git e fora de `dist/`.

## 8. Regra para agentes de IA

Quando solicitado a gerar um bundle Android destinado ao Google Play ("gere o bundle do Spark
para o Play", "prepare o AAB", "faça o release Android"):

1. leia este documento;
2. confirme que `versionCode`/`versionName` já foram definidos conscientemente em
   `app/build.gradle.kts` e commitados em `main` — se não foram, pergunte qual versão o usuário
   quer, nunca invente nem auto-incremente;
3. use exclusivamente:
   ```bash
   ./ops/android/build-play-bundle.sh
   ```
4. não reproduza manualmente `bundleRelease`, `jarsigner`, `keytool` ou `sha256sum`; não crie
   `signingConfig`; não edite `ops/android/play-release.conf` para "fazer passar";
5. não leia, não peça, não adivinhe e não aceite a senha da upload key — nem se o usuário a
   oferecer;
6. se o processo parar no prompt de senha, permita que o usuário a digite no terminal;
7. se a sua ferramenta não tem TTY interativo (é o caso do Claude Code e da maioria dos agentes),
   rode `./ops/android/build-play-bundle.sh --check` para validar tudo até o bundle, e então
   **entregue o comando completo ao usuário** e aguarde ele executar num terminal:
   ```text
   Rode num terminal (a senha da upload key será pedida pelo jarsigner):
     ./ops/android/build-play-bundle.sh
   e me envie o resumo final (Artifact + SHA-256 + RESULT).
   ```
8. só reporte "bundle pronto" quando o script concluiu com `RESULT: PASS` e forneceu o caminho do
   artefato + SHA-256. `RESULT: CHECK PASS` **não** é bundle pronto; inspeção de código não é
   `PASS`; um `bundleRelease` verde sem assinatura não é release.

## 9. Interface Gradle usada pelo script

`:app:printPlayReleaseMetadata` (`app/build.gradle.kts`) escreve
`app/build/play-release/metadata.properties`:

```properties
applicationId=com.aistudio.workout.v2
versionCode=3
versionName=1.0.1
compileSdk=36
targetSdk=36
backendBaseUrl=https://spark-backend-965678405850.southamerica-east1.run.app
```

Os valores vêm da própria variante `release` (`androidComponents.onVariants`) e do mesmo
`releaseBackendBaseUrl(providers)` que alimenta o `BuildConfig` — sem duplicar a regra do portão
de endereço, sem tocar segredo, compatível com o configuration cache. É a única forma pela qual
o script conhece `versionCode`/`versionName`: nada de `grep` em `build.gradle.kts`.

## 10. Rotação futura da upload key

Quando a upload key for redefinida no Play Console (perda, comprometimento, troca de máquina):

```text
novo JKS local (keytool -genkeypair -storetype JKS -alias spark-upload …)
        ↓
exportar o certificado PÚBLICO (keytool -exportcert -rfc … > upload_certificate.pem)
        ↓
Play Console → Setup → App integrity → App signing → Request upload key reset → enviar o .pem
        ↓
Google aceita a redefinição (o SHA-1 novo passa a ser o registrado)
        ↓
atualizar EXPECTED_UPLOAD_CERT_SHA1 em ops/android/play-release.conf, commitar em main
        ↓
./ops/android/build-play-bundle.sh --check   (deve passar em "Upload key")
        ↓
release normal com a chave nova
```

Até o `.conf` ser atualizado, o script **recusa** a chave nova ("upload key não corresponde ao
certificado esperado") — é o comportamento certo: ele protege contra a chave errada, e a chave
nova é "errada" até o Play dizer o contrário. **A App Signing Key do Google Play não muda, não é
exportada e não entra nessa rotina.** Se o Play também pediu novos fingerprints para Firebase
(Auth/App Check usam os da App Signing key, não os da upload key), isso é outro procedimento
(`docs/FIREBASE_AUTH_SETUP.md`).

Guarde a keystore antiga até o Play confirmar que a nova está ativa; depois destrua-a
conscientemente.

## 11. Troubleshooting

| Sintoma | Causa | O que fazer |
| --- | --- | --- |
| `ERROR: assinatura requer terminal interativo` | Rodou sem TTY (agente, `\| tee`, cron, CI) | Rode direto num terminal; agentes usam `--check` e entregam o comando ao usuário. |
| `ERROR: worktree contém alterações não commitadas` | Há mudança não commitada (inclusive o bump de versão) | Commite em `main` ou descarte. Não há bypass. |
| `ERROR: branch atual é 'x'; … só sai de 'main'` | Fora de `main` | Integre em `main`. Produção sai de `main` (mesma regra do deploy do backend). |
| `ERROR: sparkBackendBaseUrl efetivo está vazio` | Propriedade não definida | `~/.gradle/gradle.properties` com o endereço de produção (§2). |
| `ERROR: sparkBackendBaseUrl efetivo é '…', e o release … exige '…'` | Staging/dev configurado | Corrija a propriedade; se produção mudou de endereço, atualize o `.conf` conscientemente. |
| `:app:printPlayReleaseMetadata falhou` citando `sparkBackendBaseUrl` | O portão do Gradle recusou (HTTP, privado) | Idem. |
| `ERROR: app/google-services.json não existe` | Arquivo não baixado nesta máquina | Baixe do console do Firebase (projeto do Spark) para `app/`. |
| `ERROR: upload key não corresponde ao certificado esperado` | Keystore errada, alias errado apontando para outra chave, ou rotação sem atualizar o `.conf` | Confira o SHA-1 (§2); se houve rotação, §10. |
| `ERROR: não foi possível ler o certificado do alias` + `does not exist` | Alias errado | `keytool -list -keystore <jks>` lista os aliases; use `SPARK_UPLOAD_KEY_ALIAS` ou corrija o keystore. |
| `ERROR: não foi possível ler o certificado do alias` + `password was incorrect` | Keystore em PKCS12 (certificado cifrado) | A upload key canônica é JKS. Se a keystore é PKCS12, converta o certificado público para conferência ou regenere como JKS na próxima rotação. |
| `ERROR: dist/spark-1.0.1-3.aab já existe` | Mesma versão já gerada | Suba a versão; ou remova o artefato antigo se a regeneração é consciente. |
| `ERROR: bundle produzido está assinado de forma inesperada` | Alguém adicionou `signingConfig` de release | Reveja o `build.gradle.kts`; credencial no build é proibida. |
| `ERROR: jarsigner falhou` | Senha errada (mais comum) | Rode de novo. Nada foi publicado; o intermediário está intacto. |
| `ERROR: assinatura final inválida` / `não é a upload key registrada` | A assinatura não verifica contra a keystore/alias, ou o signer não é a chave registrada | Não envie nada ao Play. Confira keystore, alias e `.conf`. |
| `:app:testDebugUnitTest falhou` / `lintVitalRelease falhou` | Suíte vermelha / lint fatal | Corrija o código. Nunca baseline, nunca `-x test`. |
| Play Console: "You uploaded an APK or Android App Bundle that was signed in debug mode" ou "wrong key" | AAB assinado com outra chave | O script deveria ter recusado; confira se o AAB enviado é o de `dist/` e o SHA-256 confere. |
| Play Console: "Version code X has already been used" | `versionCode` não subiu | Suba `versionCode`, commite, gere de novo (o artefato anterior fica em `dist/` com o nome antigo). |

## 12. Testes

- `ops/tests/build-play-bundle.test.sh` — offline, sem JDK, SDK, keystore, senha ou rede: dublês de
  `gradlew`/`keytool`/`jarsigner`/`git`, pseudo-terminal via `script`. Cobre cada falha fechada
  (ferramenta, Firebase, keystore/alias/fingerprint, backend, `applicationId`, SDK, Git, testes,
  lint, bundle, AAB ausente/antigo/já assinado, `jarsigner`, assinatura inválida, signer errado,
  dois signers, artefato duplicado), a ausência de senha no argv/ambiente, a recusa sem TTY,
  `--check`, a composição do nome e o `.sha256`. Roda no CI (`backend.yml`, job `ops-scripts`).
- `ops/tests/ops-scripts-safety.test.sh` + `shellcheck ops/android/*.sh` — regras estáticas.
- `./gradlew :app:printPlayReleaseMetadata` — a interface Gradle, coberta pelo próprio build.

## Ver também

- [`AGENT_WORKFLOW.md` §15](../../AGENT_WORKFLOW.md) — roteamento para este runbook.
- [`ops/android/play-release.conf`](../../ops/android/play-release.conf) — os valores pinados
  (applicationId, backend, fingerprint, SDK mínimo, keystore/alias padrão) e seu único dono.
- [`docs/FIREBASE_AUTH_SETUP.md`](../FIREBASE_AUTH_SETUP.md) — fingerprints no Firebase (inclusive
  os da App Signing key).
- [`AGENT_DEPLOYMENT.md`](./AGENT_DEPLOYMENT.md) — o equivalente para o backend (mesma filosofia:
  um fluxo, nenhum atalho).
