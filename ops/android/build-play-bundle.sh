#!/usr/bin/env bash
#
# Fluxo canônico do Android App Bundle assinado do Spark para o Google Play (T18.4).
#
#   preflight → metadados efetivos (Gradle) → validações de release → testDebugUnitTest →
#   lintVitalRelease → verifyReleaseEndpointGate → bundleRelease → AAB sem assinatura →
#   cópia temporária → jarsigner (senha digitada no terminal) → verificação criptográfica →
#   dist/spark-<versionName>-<versionCode>.aab + .sha256 → resumo → RESULT: PASS
#
# Uso:
#   ./ops/android/build-play-bundle.sh            # fluxo completo; a senha é pedida pelo jarsigner
#   ./ops/android/build-play-bundle.sh --check    # tudo até o bundle validado; NÃO assina, NADA em dist/
#
# ## A fronteira de segurança
#
# Gradle gera o bundle; o `jarsigner` pede a senha da upload key **interativamente**, num terminal.
# Este script nunca conhece a senha: não há opção de linha de comando, variável de ambiente, arquivo
# ou prompt próprio para ela, e sem um terminal interativo o fluxo falha antes de gastar um minuto
# de build. Um agente de IA que rode isto entrega o comando ao usuário e espera — nunca pede a senha.
# O que o script valida, ele valida sem segredo: o certificado público da upload key (JKS) é lido
# sem senha, e a assinatura final é verificada contra ele.
#
# ## O que ele recusa (falha fechada, exit ≠ 0, `RESULT: FAIL`)
#
#   - ferramenta ausente (java/javac/keytool/jarsigner/git/sha256sum/gradlew/SDK);
#   - `app/google-services.json` ausente, vazio ou de outro `applicationId`;
#   - keystore ou alias ausentes, certificado ilegível, fingerprint diferente do registrado;
#   - fora de `main`, worktree suja, ou fora de um repositório Git;
#   - `applicationId`, `targetSdk`/`compileSdk` ou backend efetivo diferentes do esperado;
#   - `dist/spark-<versionName>-<versionCode>.aab` já existente (nunca sobrescreve);
#   - testes, lint, portão de endereço ou `bundleRelease` falhando;
#   - AAB intermediário ausente, antigo, ou já assinado (o processo canônico teria mudado);
#   - `jarsigner` falhando, assinatura não verificável ou signer diferente da upload key.
#
# Nada é escrito em `dist/` antes de todas as verificações passarem; o artefato final chega lá por
# `ln` (atômico, falha se já existir), nunca por cópia parcial.
#
# Convenções de `ops/`: `set -Eeuo pipefail`, narrativa em stderr, resumo final em stdout, sem
# `eval`, sem `source` de arquivo de configuração, sem `set -x`.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${SCRIPT_DIR}/play-release.conf"

# ---------------------------------------------------------------- saída

log()  { printf '%s\n' "$*" >&2; }
step() { printf '\n==> %s\n' "$*" >&2; }
fail() {
  printf '\nERROR: %s\n' "$1" >&2
  shift
  # Linhas adicionais: o que falta, por que é necessário, como corrigir.
  for line in "$@"; do printf '       %s\n' "${line}" >&2; done
  exit 1
}

# ---------------------------------------------------------------- estado

MODE=sign            # sign | check
REPO_ROOT=""
DIST_DIR=""
WORK_DIR=""
SUMMARY_PRINTED=0

# Ferramentas resolvidas no preflight (caminhos absolutos; ver resolve_java_tool).
JAVA_BIN=""; KEYTOOL_BIN=""; JARSIGNER_BIN=""; JAVA_VERSION=""
# `-J` força a saída em inglês: o script lê "SHA1:", "jar verified." e "jar is unsigned." — e
# keytool/jarsigner traduzem essas linhas conforme o locale da máquina.
JAVA_LANG_ARGS=(-J-Duser.language=en -J-Duser.country=US)

# Configuração pública (ops/android/play-release.conf).
EXPECTED_APPLICATION_ID=""; EXPECTED_BACKEND_BASE_URL=""; EXPECTED_UPLOAD_CERT_SHA1=""
MIN_TARGET_SDK=""; DEFAULT_UPLOAD_KEYSTORE=""; DEFAULT_UPLOAD_KEY_ALIAS=""

# Upload key em uso (nunca a senha).
UPLOAD_KEYSTORE=""; UPLOAD_KEY_ALIAS=""

# Metadados efetivos do release (`:app:printPlayReleaseMetadata`).
APPLICATION_ID=""; VERSION_CODE=""; VERSION_NAME=""; COMPILE_SDK=""; TARGET_SDK=""; BACKEND_BASE_URL=""

# Git.
GIT_BRANCH=""; GIT_COMMIT=""; GIT_COMMIT_SHORT=""; GIT_WORKTREE=""

# Artefato.
ARTIFACT_NAME=""; ARTIFACT_PATH=""; ARTIFACT_SHA256=""
INTERMEDIATE_AAB="app/build/outputs/bundle/release/app-release.aab"

# Resultado de cada etapa, para o resumo.
STATUS_FIREBASE="NOT RUN"; STATUS_TESTS="NOT RUN"; STATUS_LINT="NOT RUN"; STATUS_BUNDLE="NOT RUN"
STATUS_UPLOAD_KEY="NOT RUN"; STATUS_SIGNATURE="NOT RUN"

cleanup() {
  local code=$?
  if [ -n "${WORK_DIR}" ] && [ -d "${WORK_DIR}" ]; then rm -rf "${WORK_DIR}"; fi
  if [ "${code}" -ne 0 ] && [ "${SUMMARY_PRINTED}" -eq 0 ]; then
    printf '\nRESULT: FAIL\n' >&2
  fi
}
trap cleanup EXIT

# ---------------------------------------------------------------- argumentos

usage() {
  cat >&2 <<'USAGE'
Uso: ops/android/build-play-bundle.sh [--check]

  (sem opção)  fluxo completo: valida, testa, gera o bundle, assina com a upload key (a senha é
               pedida pelo jarsigner no terminal), verifica e publica em dist/.
  --check      executa tudo até o bundle validado e para: não assina e não escreve em dist/.
               Útil para um agente sem terminal interativo validar o release antes de entregar
               o comando ao usuário.

Não existe — e não vai existir — opção para senha. Leia docs/operations/ANDROID_PLAY_RELEASE.md.
USAGE
}

parse_args() {
  for arg in "$@"; do
    case "${arg}" in
      --check) MODE=check ;;
      -h|--help) usage; exit 0 ;;
      --storepass*|--keypass*|--password*|-storepass*|-keypass*)
        fail "a senha da upload key nunca é aceita como argumento (${arg%%=*})." \
          "O jarsigner a pede interativamente no terminal; ela não passa por este script, por" \
          "variável de ambiente, por arquivo nem por um agente. Execute sem essa opção." ;;
      *) usage; fail "argumento desconhecido: ${arg}" ;;
    esac
  done
}

# ---------------------------------------------------------------- preflight

resolve_repo_root() {
  REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
  if ! { [ -f "${REPO_ROOT}/settings.gradle.kts" ] && [ -d "${REPO_ROOT}/app" ]; }; then
    fail "a raiz do repositório não foi encontrada a partir de ${SCRIPT_DIR}." \
      "O script espera viver em <repo>/ops/android/ e encontrar settings.gradle.kts e app/ na raiz."
  fi
  DIST_DIR="${REPO_ROOT}/dist"
}

# Lê play-release.conf como chave=valor, sem `source`: só as chaves conhecidas, só valores sem
# espaço, aspas, `$`, backtick ou `;`. Um arquivo de configuração não executa nada.
read_release_config() {
  [ -f "${CONFIG_FILE}" ] || fail "configuração pública ausente: ${CONFIG_FILE}." \
    "Ela é versionada em ops/android/ e define applicationId, backend, fingerprint e SDK mínimos."
  local line key value
  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in ''|'#'*) continue ;; esac
    key="${line%%=*}"; value="${line#*=}"
    case "${value}" in
      *[[:space:]\"\'\$\`\;]*|'')
        fail "valor inválido para ${key} em play-release.conf." \
          "Só valores simples, sem espaço, aspas, \$, backtick ou ';'." ;;
    esac
    case "${key}" in
      EXPECTED_APPLICATION_ID)   EXPECTED_APPLICATION_ID="${value}" ;;
      EXPECTED_BACKEND_BASE_URL) EXPECTED_BACKEND_BASE_URL="${value}" ;;
      EXPECTED_UPLOAD_CERT_SHA1) EXPECTED_UPLOAD_CERT_SHA1="${value}" ;;
      MIN_TARGET_SDK)            MIN_TARGET_SDK="${value}" ;;
      DEFAULT_UPLOAD_KEYSTORE)   DEFAULT_UPLOAD_KEYSTORE="${value}" ;;
      DEFAULT_UPLOAD_KEY_ALIAS)  DEFAULT_UPLOAD_KEY_ALIAS="${value}" ;;
      *) fail "chave desconhecida em play-release.conf: ${key}." \
           "O arquivo aceita só as chaves documentadas em docs/operations/ANDROID_PLAY_RELEASE.md." ;;
    esac
  done < "${CONFIG_FILE}"
  local name
  for name in EXPECTED_APPLICATION_ID EXPECTED_BACKEND_BASE_URL EXPECTED_UPLOAD_CERT_SHA1 \
              MIN_TARGET_SDK DEFAULT_UPLOAD_KEYSTORE DEFAULT_UPLOAD_KEY_ALIAS; do
    [ -n "${!name}" ] || fail "play-release.conf não define ${name}."
  done
  [[ "${MIN_TARGET_SDK}" =~ ^[0-9]+$ ]] || fail "MIN_TARGET_SDK precisa ser um inteiro (play-release.conf)."
  local normalized
  normalized="$(normalize_fingerprint "${EXPECTED_UPLOAD_CERT_SHA1}")"
  [ "${#normalized}" -eq 40 ] \
    || fail "EXPECTED_UPLOAD_CERT_SHA1 não parece um SHA-1 (40 dígitos hexadecimais) em play-release.conf."

  # Um `~/` no início do caminho vira $HOME — e só ele, e só ali: nada de `eval`.
  UPLOAD_KEYSTORE="${SPARK_UPLOAD_KEYSTORE:-${DEFAULT_UPLOAD_KEYSTORE}}"
  if [ "${UPLOAD_KEYSTORE#\~/}" != "${UPLOAD_KEYSTORE}" ]; then
    UPLOAD_KEYSTORE="${HOME}/${UPLOAD_KEYSTORE#\~/}"
  fi
  UPLOAD_KEY_ALIAS="${SPARK_UPLOAD_KEY_ALIAS:-${DEFAULT_UPLOAD_KEY_ALIAS}}"
}

# Uma senha no ambiente não é lida — e não é tolerada. Alguém que exportou uma "para ajudar"
# precisa saber que este fluxo não a usa e que ela está exposta a todo processo filho.
refuse_password_in_environment() {
  local name
  for name in PLAY_KEYSTORE_PASSWORD KEYSTORE_PASSWORD SPARK_UPLOAD_KEY_PASSWORD SPARK_KEYSTORE_PASSWORD \
              SPARK_UPLOAD_STOREPASS SPARK_UPLOAD_KEYPASS; do
    [ -z "${!name:-}" ] || fail "a variável de ambiente ${name} está definida; este fluxo não aceita senha por ambiente." \
      "Remova-a da sessão (unset ${name}) — a senha é digitada no prompt do jarsigner, e só lá."
  done
}

# Ferramentas do JDK: `$JAVA_HOME/bin` quando JAVA_HOME está definido (é o que o Gradle usa),
# senão o PATH. Um JRE não serve: `javac` e `jarsigner` só vêm com o JDK.
resolve_java_tool() {
  local tool="$1"
  if [ -n "${JAVA_HOME:-}" ]; then
    [ -x "${JAVA_HOME}/bin/${tool}" ] || fail "${tool} não existe em JAVA_HOME (${JAVA_HOME}/bin/${tool})." \
      "O release exige um JDK completo (17+): javac, keytool e jarsigner. Aponte JAVA_HOME para um JDK" \
      "ou remova a variável para usar o java do PATH."
    printf '%s' "${JAVA_HOME}/bin/${tool}"
  else
    command -v "${tool}" > /dev/null 2>&1 || fail "${tool} não foi encontrado no PATH." \
      "O release exige um JDK completo (17+): java, javac, keytool e jarsigner. Instale um JDK" \
      "(por exemplo Temurin 21) e defina JAVA_HOME ou coloque <jdk>/bin no PATH."
    command -v "${tool}"
  fi
}

require_command() {
  local tool="$1" why="$2"
  command -v "${tool}" > /dev/null 2>&1 || fail "comando obrigatório não encontrado: ${tool}." "${why}"
}

# O SDK que o Gradle vai resolver: `sdk.dir` em local.properties tem precedência sobre
# ANDROID_HOME/ANDROID_SDK_ROOT — a mesma ordem do AGP. Nenhum dos três é criado aqui.
resolve_android_sdk() {
  local sdk="" origin=""
  if [ -f "${REPO_ROOT}/local.properties" ]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "${REPO_ROOT}/local.properties" | head -n 1 | sed 's/\\:/:/g')"
    [ -z "${sdk}" ] || origin="local.properties"
  fi
  if [ -z "${sdk}" ] && [ -n "${ANDROID_HOME:-}" ]; then sdk="${ANDROID_HOME}"; origin="ANDROID_HOME"; fi
  if [ -z "${sdk}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then sdk="${ANDROID_SDK_ROOT}"; origin="ANDROID_SDK_ROOT"; fi
  [ -n "${sdk}" ] || fail "nenhum Android SDK resolvível: sem sdk.dir em local.properties e sem ANDROID_HOME." \
    "O Gradle precisa do SDK (platforms;android-<compileSdk>, build-tools) para gerar o bundle." \
    "Defina ANDROID_HOME=<sdk> ou crie local.properties com sdk.dir=<sdk> (nenhum dos dois é criado aqui)."
  [ -d "${sdk}/platforms" ] || fail "o Android SDK apontado por ${origin} (${sdk}) não tem platforms/." \
    "Instale com sdkmanager: \"platforms;android-36\" \"build-tools;36.0.0\" \"platform-tools\"."
  log "Android SDK ........... ${sdk} (${origin})"
}

require_interactive_terminal() {
  [ "${MODE}" = "sign" ] || return 0
  if ! { [ -t 0 ] && [ -t 1 ]; }; then
    fail "assinatura requer terminal interativo (stdin e stdout precisam ser um TTY)." \
      "O jarsigner pede a senha da upload key no terminal, com eco desligado; sem TTY ela seria lida" \
      "de um pipe em texto claro — e isso este fluxo não faz." \
      "Execute localmente, num terminal:" \
      "    ./ops/android/build-play-bundle.sh" \
      "Sem terminal (um agente, por exemplo), use --check para validar tudo até o bundle." \
      "Nunca forneça a senha da upload key a um agente."
  fi
}

preflight() {
  step "Preflight"
  [ "${BASH_VERSINFO[0]}" -ge 4 ] || fail "bash 4+ é necessário (encontrado ${BASH_VERSION})."
  refuse_password_in_environment
  JAVA_BIN="$(resolve_java_tool java)"
  resolve_java_tool javac > /dev/null
  KEYTOOL_BIN="$(resolve_java_tool keytool)"
  JARSIGNER_BIN="$(resolve_java_tool jarsigner)"
  require_command git "O release precisa de branch, commit e estado da worktree para ser rastreável."
  require_command sha256sum "O checksum do artefato final é calculado com sha256sum."
  require_command mktemp "A assinatura acontece numa cópia temporária, criada com mktemp."
  [ -f "${REPO_ROOT}/gradlew" ] || fail "${REPO_ROOT}/gradlew não existe." \
    "O fluxo usa exclusivamente o Gradle Wrapper do repositório — nunca um Gradle global."
  [ -x "${REPO_ROOT}/gradlew" ] || fail "${REPO_ROOT}/gradlew não é executável." \
    "Corrija com: chmod +x ${REPO_ROOT}/gradlew"
  resolve_android_sdk
  require_interactive_terminal
  # A versão fica no resumo: o CI constrói com JDK 21, e um release feito com outro JDK precisa
  # ser rastreável como tal (Robolectric, R8 e o próprio Gradle mudam de comportamento por major).
  JAVA_VERSION="$("${JAVA_BIN}" -version 2>&1 | head -n 1 | sed -n 's/.*version "\([^"]*\)".*/\1/p')"
  log "Java .................. ${JAVA_BIN} (${JAVA_VERSION:-versão desconhecida})"
  log "Gradle ................ ${REPO_ROOT}/gradlew"
  log "Modo .................. ${MODE}"
}

# ---------------------------------------------------------------- git

validate_git_state() {
  step "Estado do Git"
  git -C "${REPO_ROOT}" rev-parse --is-inside-work-tree > /dev/null 2>&1 \
    || fail "${REPO_ROOT} não é um repositório Git válido." \
      "Um bundle para o Play precisa ser rastreável até um commit."
  GIT_BRANCH="$(git -C "${REPO_ROOT}" symbolic-ref --short -q HEAD || true)"
  [ -n "${GIT_BRANCH}" ] || fail "HEAD está desanexado (detached); o release precisa sair da branch main." \
    "Faça checkout de main: git checkout main"
  [ "${GIT_BRANCH}" = "main" ] || fail "branch atual é '${GIT_BRANCH}'; um bundle para o Play só sai de 'main'." \
    "Integre o trabalho em main primeiro (é a regra de deploy do projeto: produção sai de main)."
  GIT_COMMIT="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
  GIT_COMMIT_SHORT="$(git -C "${REPO_ROOT}" rev-parse --short=12 HEAD)"
  local dirty
  dirty="$(git -C "${REPO_ROOT}" status --porcelain)"
  if [ -n "${dirty}" ]; then
    log "${dirty}"
    fail "worktree contém alterações não commitadas (acima)." \
      "Um bundle de produção com alteração não versionada não é reproduzível a partir do commit." \
      "Commite (ou descarte) as alterações — inclusive versionCode/versionName — e rode de novo."
  fi
  GIT_WORKTREE="clean"
  log "Branch ................ ${GIT_BRANCH}"
  log "Commit ................ ${GIT_COMMIT}"
  log "Worktree .............. ${GIT_WORKTREE}"
}

# ---------------------------------------------------------------- metadados efetivos

gradlew() {
  ( cd "${REPO_ROOT}" && ./gradlew --console=plain "$@" )
}

read_release_metadata() {
  step "Metadados efetivos do release (:app:printPlayReleaseMetadata)"
  local file="${REPO_ROOT}/app/build/play-release/metadata.properties"
  rm -f "${file}"
  gradlew -q :app:printPlayReleaseMetadata \
    || fail ":app:printPlayReleaseMetadata falhou (saída do Gradle acima)." \
      "Se a mensagem cita sparkBackendBaseUrl, o portão de endereço do build recusou o valor efetivo:" \
      "corrija ~/.gradle/gradle.properties (HTTPS em host público; produção é o Cloud Run)."
  [ -s "${file}" ] || fail "o Gradle não produziu ${file}." \
    "A task :app:printPlayReleaseMetadata deveria escrevê-lo; confira app/build.gradle.kts."
  local line key value
  while IFS= read -r line || [ -n "${line}" ]; do
    [ -n "${line}" ] || continue
    key="${line%%=*}"; value="${line#*=}"
    case "${key}" in
      applicationId)  APPLICATION_ID="${value}" ;;
      versionCode)    VERSION_CODE="${value}" ;;
      versionName)    VERSION_NAME="${value}" ;;
      compileSdk)     COMPILE_SDK="${value}" ;;
      targetSdk)      TARGET_SDK="${value}" ;;
      backendBaseUrl) BACKEND_BASE_URL="${value}" ;;
      *) fail "chave inesperada nos metadados do release: ${key}." ;;
    esac
  done < "${file}"
  local name
  for name in APPLICATION_ID VERSION_CODE VERSION_NAME COMPILE_SDK TARGET_SDK; do
    [ -n "${!name}" ] || fail "metadados do release sem ${name} (${file})."
  done
  [[ "${VERSION_CODE}" =~ ^[0-9]+$ ]] || fail "versionCode efetivo não é um inteiro: '${VERSION_CODE}'."
  [[ "${COMPILE_SDK}" =~ ^[0-9]+$ && "${TARGET_SDK}" =~ ^[0-9]+$ ]] \
    || fail "compileSdk/targetSdk efetivos não são inteiros: '${COMPILE_SDK}'/'${TARGET_SDK}'."
  # O nome do artefato carrega versionName: ele precisa ser um nome de arquivo sem surpresas.
  [[ "${VERSION_NAME}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
    || fail "versionName efetivo ('${VERSION_NAME}') tem caracteres que não cabem num nome de artefato." \
      "Use só letras, dígitos, '.', '_' e '-' em versionName (app/build.gradle.kts)."
  log "Application ID ........ ${APPLICATION_ID}"
  log "Version name .......... ${VERSION_NAME}"
  log "Version code .......... ${VERSION_CODE}"
  log "Compile SDK ........... ${COMPILE_SDK}"
  log "Target SDK ............ ${TARGET_SDK}"
  log "Backend ............... ${BACKEND_BASE_URL:-<vazio>}"
}

# ---------------------------------------------------------------- validações de release

validate_application_id() {
  [ "${APPLICATION_ID}" = "${EXPECTED_APPLICATION_ID}" ] \
    || fail "applicationId efetivo é '${APPLICATION_ID}', mas este fluxo só empacota '${EXPECTED_APPLICATION_ID}'." \
      "Assinar outro aplicativo com a upload key do Spark seria um engano difícil de desfazer." \
      "Se a mudança é intencional, atualize EXPECTED_APPLICATION_ID em ops/android/play-release.conf" \
      "conscientemente — e o registro no Play Console e no Firebase junto."
}

validate_release_sdk() {
  [ "${TARGET_SDK}" -ge "${MIN_TARGET_SDK}" ] \
    || fail "targetSdk efetivo é ${TARGET_SDK}; o Play exige no mínimo ${MIN_TARGET_SDK} para este projeto." \
      "Suba targetSdk em app/build.gradle.kts (MIN_TARGET_SDK vive em ops/android/play-release.conf)."
  [ "${COMPILE_SDK}" -ge "${TARGET_SDK}" ] \
    || fail "compileSdk (${COMPILE_SDK}) é menor que targetSdk (${TARGET_SDK})." \
      "compileSdk precisa ser >= targetSdk (app/build.gradle.kts)."
}

# O valor EFETIVO que o Gradle vai compilar no BuildConfig, não um texto procurado num arquivo.
# O build de release já recusa HTTP, localhost, 10.0.2.2 e rede privada antes desta função rodar
# (releaseBackendBaseUrl em app/build.gradle.kts); o que resta é exigir o endpoint de produção.
validate_backend() {
  [ -n "${BACKEND_BASE_URL}" ] \
    || fail "sparkBackendBaseUrl efetivo está vazio: o bundle sairia com a nuvem desligada." \
      "Defina em ~/.gradle/gradle.properties:" \
      "    sparkBackendBaseUrl=${EXPECTED_BACKEND_BASE_URL}"
  local actual="${BACKEND_BASE_URL%/}" expected="${EXPECTED_BACKEND_BASE_URL%/}"
  case "${actual}" in
    https://*) : ;;
    *) fail "sparkBackendBaseUrl efetivo não é HTTPS ('${BACKEND_BASE_URL}')." \
         "O portão do Gradle deveria ter recusado isto antes; confira app/build.gradle.kts." ;;
  esac
  [ "${actual}" = "${expected}" ] \
    || fail "sparkBackendBaseUrl efetivo é '${BACKEND_BASE_URL}', e o release para o Play exige '${EXPECTED_BACKEND_BASE_URL}'." \
      "Um bundle publicado falando com outro host (staging, dev, IP) é incidente de produção." \
      "Corrija ~/.gradle/gradle.properties, ou — se produção mudou de endereço — atualize" \
      "EXPECTED_BACKEND_BASE_URL em ops/android/play-release.conf conscientemente."
}

# Existe, é arquivo regular, não está vazio e pertence a este applicationId. O conteúdo nunca é
# impresso, copiado ou passado adiante.
validate_firebase_config() {
  local file="${REPO_ROOT}/app/google-services.json"
  [ -e "${file}" ] || fail "app/google-services.json não existe." \
    "O plugin Google Services (Firebase Auth, App Check) exige o arquivo do console do Firebase." \
    "Baixe-o do projeto Firebase do Spark para app/google-services.json (ele é ignorado pelo Git)."
  [ -f "${file}" ] || fail "app/google-services.json não é um arquivo regular."
  [ -s "${file}" ] || fail "app/google-services.json está vazio."
  grep -qE "\"package_name\"[[:space:]]*:[[:space:]]*\"${APPLICATION_ID//./\\.}\"" "${file}" \
    || fail "app/google-services.json não contém um cliente para ${APPLICATION_ID}." \
      "O arquivo é de outro app ou de outro projeto Firebase; baixe o do Spark no console."
  STATUS_FIREBASE=PASS
  log "Firebase config ....... PASS"
}

normalize_fingerprint() {
  printf '%s' "$1" | tr -d ': -' | tr '[:lower:]' '[:upper:]'
}

# Primeira linha "SHA1: ..." de uma saída do keytool, normalizada.
first_sha1_of() {
  printf '%s\n' "$1" | sed -n 's/^[[:space:]]*SHA1:[[:space:]]*//p' | head -n 1
}

# Keystore existe, alias existe, o certificado público é legível SEM a senha (formato JKS) e o
# fingerprint é o da upload key registrada. Nada privado sai daqui: só o SHA-1 do certificado.
validate_keystore() {
  step "Upload key"
  [ -e "${UPLOAD_KEYSTORE}" ] || fail "upload key não encontrada: ${UPLOAD_KEYSTORE}." \
    "O bundle para o Play precisa ser assinado com a upload key registrada no console." \
    "Coloque o JKS nesse caminho (fora do repositório) ou aponte SPARK_UPLOAD_KEYSTORE para ele."
  [ -f "${UPLOAD_KEYSTORE}" ] || fail "${UPLOAD_KEYSTORE} não é um arquivo regular."
  local listing
  if ! listing="$("${KEYTOOL_BIN}" "${JAVA_LANG_ARGS[@]}" -list -v -protected \
        -keystore "${UPLOAD_KEYSTORE}" -alias "${UPLOAD_KEY_ALIAS}" 2>&1)"; then
    fail "não foi possível ler o certificado do alias '${UPLOAD_KEY_ALIAS}' em ${UPLOAD_KEYSTORE}." \
      "keytool: $(printf '%s\n' "${listing}" | grep -v '^[[:space:]]*$' | head -n 1)" \
      "Confira o alias (keytool -list -keystore <jks>) — o padrão é '${DEFAULT_UPLOAD_KEY_ALIAS}'," \
      "ou defina SPARK_UPLOAD_KEY_ALIAS. O certificado público é lido sem a senha, o que exige o" \
      "formato JKS (é o formato da upload key canônica do Spark)."
  fi
  printf '%s\n' "${listing}" | grep -q 'Entry type: PrivateKeyEntry' \
    || fail "o alias '${UPLOAD_KEY_ALIAS}' em ${UPLOAD_KEYSTORE} não é uma chave privada (PrivateKeyEntry)." \
      "Um certificado confiável não assina nada; a upload key é a entrada com chave privada."
  local sha1 actual expected
  sha1="$(first_sha1_of "${listing}")"
  [ -n "${sha1}" ] || fail "keytool não informou o SHA-1 do certificado do alias '${UPLOAD_KEY_ALIAS}'."
  actual="$(normalize_fingerprint "${sha1}")"
  expected="$(normalize_fingerprint "${EXPECTED_UPLOAD_CERT_SHA1}")"
  [ "${actual}" = "${expected}" ] \
    || fail "upload key não corresponde ao certificado esperado." \
      "Encontrado  SHA-1: ${sha1}" \
      "Registrado  SHA-1: ${EXPECTED_UPLOAD_CERT_SHA1}" \
      "O script encontrou uma upload key DIFERENTE da atualmente registrada para o Spark no Play" \
      "Console. Um AAB assinado com ela seria recusado no upload. Use a keystore certa — ou, se a" \
      "upload key foi redefinida no console, atualize EXPECTED_UPLOAD_CERT_SHA1 em" \
      "ops/android/play-release.conf (ver docs/operations/ANDROID_PLAY_RELEASE.md)."
  STATUS_UPLOAD_KEY=PASS
  log "Keystore .............. ${UPLOAD_KEYSTORE}"
  log "Alias ................. ${UPLOAD_KEY_ALIAS}"
  log "Certificado SHA-1 ..... ${sha1}"
  log "Upload key ............ PASS"
}

# Nome final decidido cedo, para recusar um versionCode repetido antes de gastar o build.
resolve_artifact() {
  ARTIFACT_NAME="spark-${VERSION_NAME}-${VERSION_CODE}.aab"
  ARTIFACT_PATH="${DIST_DIR}/${ARTIFACT_NAME}"
  local existing
  for existing in "${ARTIFACT_PATH}" "${ARTIFACT_PATH}.sha256"; do
    [ ! -e "${existing}" ] \
      || fail "dist/${existing##*/} já existe." \
      "Dois bundles diferentes com o mesmo versionCode não podem coexistir: o Play recusa o segundo," \
      "e este fluxo não sobrescreve releases. Se versionCode/versionName são os certos e a" \
      "regeneração é consciente, remova dist/${ARTIFACT_NAME} e dist/${ARTIFACT_NAME}.sha256 e rode de novo." \
      "Se não, suba versionCode/versionName em app/build.gradle.kts e commite."
  done
  log "Artefato .............. dist/${ARTIFACT_NAME}"
}

# ---------------------------------------------------------------- Gradle

run_gradle_checks() {
  step "Testes unitários (:app:testDebugUnitTest)"
  gradlew :app:testDebugUnitTest || fail ":app:testDebugUnitTest falhou (saída do Gradle acima)." \
    "Nenhum bundle é gerado com a suíte vermelha. Relatório: app/build/reports/tests/testDebugUnitTest/"
  STATUS_TESTS=PASS

  step "Lint de release (:app:lintVitalRelease)"
  gradlew :app:lintVitalRelease || fail ":app:lintVitalRelease falhou (saída do Gradle acima)." \
    "São as regras fatais do lint sobre a variante de release; corrija-as, nunca com baseline." \
    "Relatório: app/build/reports/lint-results-release.html"
  STATUS_LINT=PASS

  step "Portão de endereço (:app:verifyReleaseEndpointGate)"
  gradlew :app:verifyReleaseEndpointGate || fail ":app:verifyReleaseEndpointGate falhou (saída do Gradle acima)." \
    "O portão de endereço do build discorda da tabela contracts/endpoint/release-endpoint-cases.tsv."
}

build_bundle() {
  step "Bundle de release (:app:bundleRelease)"
  local aab="${REPO_ROOT}/${INTERMEDIATE_AAB}"
  # O AAB de uma execução anterior sai ANTES do build: assim, existir depois do build só pode
  # significar que este build o produziu — um Gradle que falhasse no meio não deixa um arquivo
  # antigo passando por novo. Nada além dele é apagado.
  rm -f "${aab}"
  [ ! -e "${aab}" ] || fail "não foi possível remover o bundle anterior em ${INTERMEDIATE_AAB}."
  gradlew :app:bundleRelease || fail ":app:bundleRelease falhou (saída do Gradle acima)." \
    "Nenhum artefato foi assinado nem publicado em dist/."
  [ -f "${aab}" ] || fail "o Gradle terminou sem produzir ${INTERMEDIATE_AAB}." \
    "O caminho intermediário esperado mudou? Ajuste INTERMEDIATE_AAB neste script conscientemente."
  [ -s "${aab}" ] || fail "${INTERMEDIATE_AAB} está vazio."
  STATUS_BUNDLE=PASS
  log "Bundle ................ PASS (${INTERMEDIATE_AAB})"
}

# Sem signingConfig de release, o Gradle entrega o AAB SEM assinatura — e é sobre isso que este
# fluxo assina. Um bundle que já chegasse assinado significaria que o processo canônico mudou
# (um signingConfig novo, talvez com credencial): não se assina por cima, falha-se e explica-se.
verify_intermediate_unsigned() {
  step "Estado do bundle intermediário"
  local aab="${REPO_ROOT}/${INTERMEDIATE_AAB}" verdict
  verdict="$("${JARSIGNER_BIN}" "${JAVA_LANG_ARGS[@]}" -verify "${aab}" 2>&1 || true)"
  if printf '%s\n' "${verdict}" | grep -q 'jar is unsigned'; then
    log "Bundle intermediário .. sem assinatura (esperado)"
    return 0
  fi
  if printf '%s\n' "${verdict}" | grep -q 'jar verified'; then
    fail "bundle produzido está assinado de forma inesperada (${INTERMEDIATE_AAB})." \
      "O processo canônico assume que o Gradle entrega o AAB sem assinatura e que a upload key entra" \
      "só pelo jarsigner interativo. Um bundle já assinado indica um signingConfig de release novo" \
      "em app/build.gradle.kts — reveja essa mudança (credencial no build é proibida) e ajuste esta" \
      "automação conscientemente antes de publicar."
  fi
  fail "não foi possível determinar o estado de assinatura de ${INTERMEDIATE_AAB}." \
    "jarsigner: $(printf '%s\n' "${verdict}" | grep -v '^[[:space:]]*$' | head -n 1)"
}

# ---------------------------------------------------------------- assinatura

# Assina uma CÓPIA em WORK_DIR (dentro de dist/, para o `ln` final ser atômico). O intermediário
# do Gradle nunca é alterado. O comando não tem senha: o jarsigner a pede no terminal.
sign_bundle() {
  step "Assinatura com a upload key (jarsigner)"
  require_interactive_terminal
  local copy="${WORK_DIR}/${ARTIFACT_NAME}"
  cp "${REPO_ROOT}/${INTERMEDIATE_AAB}" "${copy}"
  log "A senha da upload key será pedida pelo jarsigner. Digite-a no terminal: este script não a lê,"
  log "não a guarda e não a repassa. (Keystore ${UPLOAD_KEYSTORE}, alias ${UPLOAD_KEY_ALIAS}.)"
  "${JARSIGNER_BIN}" "${JAVA_LANG_ARGS[@]}" \
      -sigalg SHA256withRSA -digestalg SHA-256 \
      -keystore "${UPLOAD_KEYSTORE}" \
      "${copy}" "${UPLOAD_KEY_ALIAS}" \
    || fail "jarsigner falhou; nenhum artefato foi publicado." \
      "Senha incorreta, alias errado ou keystore ilegível são as causas comuns. Rode de novo."
}

# Não basta o jarsigner ter saído 0: um AAB sem assinatura também sai 0 ("jar is unsigned").
# Exige-se (1) "jar verified." em modo estrito contra a própria keystore e o alias — o que
# reprova signer diferente (exit 32/36) e entrada adulterada (exit 1) —, e (2) que o certificado
# embutido na assinatura seja exatamente a upload key registrada (SHA-1), com um único signer.
verify_bundle() {
  step "Verificação da assinatura"
  local copy="${WORK_DIR}/${ARTIFACT_NAME}" verdict code=0 certs signers sha1 actual expected
  verdict="$("${JARSIGNER_BIN}" "${JAVA_LANG_ARGS[@]}" -verify -strict -protected \
      -keystore "${UPLOAD_KEYSTORE}" "${copy}" "${UPLOAD_KEY_ALIAS}" 2>&1)" || code=$?
  if [ "${code}" -ne 0 ] || printf '%s\n' "${verdict}" | grep -q 'jar is unsigned' \
     || ! printf '%s\n' "${verdict}" | grep -q '^jar verified\.$'; then
    log "${verdict}"
    fail "assinatura final inválida (jarsigner -verify -strict saiu com ${code})." \
      "O AAB assinado não passou na verificação estrita contra ${UPLOAD_KEYSTORE} / '${UPLOAD_KEY_ALIAS}'." \
      "Nenhum artefato foi publicado."
  fi
  certs="$("${KEYTOOL_BIN}" "${JAVA_LANG_ARGS[@]}" -printcert -jarfile "${copy}" 2>&1)" \
    || fail "keytool não conseguiu ler o certificado do signer do AAB assinado." \
      "keytool: $(printf '%s\n' "${certs}" | grep -v '^[[:space:]]*$' | head -n 1)"
  signers="$(printf '%s\n' "${certs}" | grep -c '^Signer #' || true)"
  [ "${signers}" -eq 1 ] || fail "o AAB assinado tem ${signers} signer(s); esperado exatamente 1 (a upload key)."
  sha1="$(first_sha1_of "${certs}")"
  [ -n "${sha1}" ] || fail "keytool não informou o SHA-1 do certificado do signer do AAB."
  actual="$(normalize_fingerprint "${sha1}")"
  expected="$(normalize_fingerprint "${EXPECTED_UPLOAD_CERT_SHA1}")"
  [ "${actual}" = "${expected}" ] \
    || fail "o signer do AAB (SHA-1 ${sha1}) não é a upload key registrada (${EXPECTED_UPLOAD_CERT_SHA1})." \
      "Nenhum artefato foi publicado."
  STATUS_SIGNATURE=PASS
  log "jarsigner -verify ..... jar verified (strict, alias ${UPLOAD_KEY_ALIAS})"
  log "Signer SHA-1 .......... ${sha1}"
  log "Signature ............. PASS"
}

# ---------------------------------------------------------------- publicação

# Checksum calculado sobre a cópia assinada e verificada; depois AAB e .sha256 entram em dist/ por
# `ln`, que é atômico e FALHA se o destino já existir — nunca há sobrescrita nem arquivo parcial.
publish_artifact() {
  step "Publicação em dist/"
  local copy="${WORK_DIR}/${ARTIFACT_NAME}"
  ( cd "${WORK_DIR}" && sha256sum "${ARTIFACT_NAME}" > "${ARTIFACT_NAME}.sha256" ) \
    || fail "sha256sum falhou sobre ${ARTIFACT_NAME}."
  ARTIFACT_SHA256="$(cut -d' ' -f1 "${copy}.sha256")"
  [ "${#ARTIFACT_SHA256}" -eq 64 ] || fail "checksum inválido para ${ARTIFACT_NAME}."
  ln "${copy}" "${ARTIFACT_PATH}" \
    || fail "dist/${ARTIFACT_NAME} apareceu durante a execução; nada foi sobrescrito."
  ln "${copy}.sha256" "${ARTIFACT_PATH}.sha256" \
    || fail "dist/${ARTIFACT_NAME}.sha256 já existe; o AAB foi publicado, mas o checksum não."
  ( cd "${DIST_DIR}" && sha256sum -c --quiet "${ARTIFACT_NAME}.sha256" ) \
    || fail "o checksum publicado não confere com dist/${ARTIFACT_NAME}."
  log "Artefato .............. ${ARTIFACT_PATH}"
  log "Checksum .............. ${ARTIFACT_PATH}.sha256"
}

# ---------------------------------------------------------------- resumo

print_summary() {
  SUMMARY_PRINTED=1
  local title="Spark — Google Play Bundle"
  [ "${MODE}" = "sign" ] || title="${title} (--check: sem assinatura, nada em dist/)"
  cat <<SUMMARY

${title}

Git
  Branch ............... ${GIT_BRANCH}
  Commit ............... ${GIT_COMMIT_SHORT}
  Worktree ............. ${GIT_WORKTREE}

Toolchain
  JDK .................. ${JAVA_VERSION:-desconhecido} (${JAVA_BIN})

Android
  Application ID ....... ${APPLICATION_ID}
  Version name ......... ${VERSION_NAME}
  Version code ......... ${VERSION_CODE}
  Compile SDK .......... ${COMPILE_SDK}
  Target SDK ........... ${TARGET_SDK}

Release
  Backend .............. ${BACKEND_BASE_URL}
  Firebase config ...... ${STATUS_FIREBASE}
  Unit tests ........... ${STATUS_TESTS}
  Lint release ......... ${STATUS_LINT}
  Bundle ............... ${STATUS_BUNDLE}
  Upload key ........... ${STATUS_UPLOAD_KEY}
  Signature ............ ${STATUS_SIGNATURE}
SUMMARY
  if [ "${MODE}" = "sign" ]; then
    cat <<SUMMARY

Artifact
  dist/${ARTIFACT_NAME}
  dist/${ARTIFACT_NAME}.sha256

SHA-256
  ${ARTIFACT_SHA256}

RESULT: PASS
SUMMARY
  else
    cat <<SUMMARY

Artifact
  (nenhum — --check não assina e não escreve em dist/; o bundle sem assinatura está em
  ${INTERMEDIATE_AAB})

Próximo passo, num terminal interativo:
  ./ops/android/build-play-bundle.sh

RESULT: CHECK PASS (bundle NÃO assinado, nada publicado)
SUMMARY
  fi
}

# ---------------------------------------------------------------- main

main() {
  parse_args "$@"
  resolve_repo_root
  read_release_config
  preflight
  validate_git_state
  read_release_metadata
  step "Validação da configuração de release"
  validate_application_id
  validate_release_sdk
  validate_backend
  validate_firebase_config
  validate_keystore
  resolve_artifact

  # A área de trabalho fica DENTRO de dist/ para que o `ln` final seja no mesmo sistema de
  # arquivos; `--check` não cria dist/ e usa um diretório temporário comum.
  if [ "${MODE}" = "sign" ]; then
    mkdir -p "${DIST_DIR}"
    WORK_DIR="$(mktemp -d "${DIST_DIR}/.build-play-bundle.XXXXXX")"
  else
    WORK_DIR="$(mktemp -d)"
  fi

  run_gradle_checks
  build_bundle
  verify_intermediate_unsigned

  if [ "${MODE}" = "sign" ]; then
    sign_bundle
    verify_bundle
    publish_artifact
  else
    STATUS_SIGNATURE="SKIPPED (--check)"
  fi
  print_summary
}

main "$@"
