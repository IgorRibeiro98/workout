#!/usr/bin/env bash
#
# O fluxo canônico do bundle para o Google Play (T18.4), provado sem JDK real, sem Android SDK, sem
# a upload key privada, sem senha, sem Firebase e sem rede: dublês de `gradlew`, `java`, `javac`,
# `keytool`, `jarsigner` e `git` num repositório temporário que reproduz a forma do real
# (`settings.gradle.kts`, `app/`, `ops/android/`). O único binário real no caminho crítico é
# `sha256sum`.
#
# O que fica provado:
#   - cada falha fechada da lista de aceite (ferramenta, Firebase, keystore, alias, fingerprint,
#     backend, applicationId, SDK, Git, testes, lint, bundle, AAB ausente/antigo/já assinado,
#     jarsigner, assinatura inválida, signer errado, artefato duplicado) sai ≠ 0, imprime
#     `RESULT: FAIL`, e para ANTES da etapa seguinte (o dublê registra o que foi chamado);
#   - a senha nunca entra no argv do jarsigner, nem por opção do script, nem por ambiente;
#   - sem TTY o fluxo de assinatura falha no preflight, antes de qualquer Gradle;
#   - a assinatura acontece numa cópia, o intermediário do Gradle não muda, e `dist/` só recebe
#     `spark-<versionName>-<versionCode>.aab` + `.sha256` (checksum conferido com sha256sum real)
#     depois de tudo passar;
#   - `--check` valida tudo até o bundle e não assina nem cria dist/.
#
# A assinatura interativa é reproduzida com um pseudo-terminal (`script`, util-linux): o dublê de
# jarsigner não pede senha nenhuma, mas o script exige um TTY antes de chamá-lo — e é isso que este
# teste precisa atravessar.
#
# Uso: ops/tests/build-play-bundle.test.sh

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ops/tests/lib.fakes.sh
. "${OPS_DIR}/tests/lib.fakes.sh"

SCRIPT="${OPS_DIR}/android/build-play-bundle.sh"
CONF="${OPS_DIR}/android/play-release.conf"
CANONICAL_SHA1="$(sed -n 's/^EXPECTED_UPLOAD_CERT_SHA1=//p' "${CONF}")"
CANONICAL_APP_ID="$(sed -n 's/^EXPECTED_APPLICATION_ID=//p' "${CONF}")"
CANONICAL_BACKEND="$(sed -n 's/^EXPECTED_BACKEND_BASE_URL=//p' "${CONF}")"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "${SANDBOX}"' EXIT

# Nada do ambiente de quem roda o teste pode vazar para o script: keystore, alias e SDK são
# controlados caso a caso.
unset SPARK_UPLOAD_KEYSTORE SPARK_UPLOAD_KEY_ALIAS ANDROID_HOME ANDROID_SDK_ROOT

# ---------------------------------------------------------------- dublês

FAKE_BIN="${SANDBOX}/bin"
FAKE_JDK="${SANDBOX}/jdk"
mkdir -p "${FAKE_BIN}" "${FAKE_JDK}/bin"

# `git`: `-C <dir>` é aceito e ignorado. (Prefixo FAKE_: o script sob teste tem um GIT_BRANCH
# próprio, e uma variável herdada com o mesmo nome seria sobrescrita antes de o dublê a ler.)
#   FAKE_GIT_NOT_REPO=1   → não é um repositório
#   FAKE_GIT_BRANCH=x     → branch atual (default main); FAKE_GIT_DETACHED=1 → HEAD desanexado
#   FAKE_GIT_DIRTY=1      → worktree suja
cat > "${FAKE_BIN}/git" <<'FAKE_GIT'
#!/usr/bin/env bash
set -euo pipefail
[ "${1:-}" != "-C" ] || shift 2
case "${1:-} ${2:-}" in
  "rev-parse --is-inside-work-tree") [ -z "${FAKE_GIT_NOT_REPO:-}" ] || exit 128; echo true ;;
  "symbolic-ref --short") [ -z "${FAKE_GIT_DETACHED:-}" ] || exit 1; printf '%s\n' "${FAKE_GIT_BRANCH:-main}" ;;
  "rev-parse --short=12") printf 'abcdef123456\n' ;;
  "rev-parse HEAD") printf 'abcdef1234567890abcdef1234567890abcdef12\n' ;;
  "status --porcelain") [ -z "${FAKE_GIT_DIRTY:-}" ] || printf ' M app/build.gradle.kts\n' ;;
esac
exit 0
FAKE_GIT

# `java`/`javac`: só precisam existir.
printf '#!/usr/bin/env bash\nexit 0\n' > "${FAKE_JDK}/bin/java"
printf '#!/usr/bin/env bash\nexit 0\n' > "${FAKE_JDK}/bin/javac"

# `keytool`:
#   -list      → alias ≠ FAKE_KEYSTORE_ALIAS (default spark-upload) falha; FAKE_KEYSTORE_UNREADABLE=1
#                falha como um PKCS12 sem senha; senão imprime PrivateKeyEntry + SHA1 = FAKE_KEYSTORE_SHA1
#   -printcert -jarfile → Signer #1 com SHA1 = FAKE_SIGNER_SHA1 (default: FAKE_KEYSTORE_SHA1);
#                FAKE_TWO_SIGNERS=1 acrescenta um Signer #2
cat > "${FAKE_JDK}/bin/keytool" <<'FAKE_KEYTOOL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${KEYTOOL_CALL_LOG}"
alias="" prev="" mode=""
for arg in "$@"; do
  [ "${prev}" != "-alias" ] || alias="${arg}"
  case "${arg}" in -list) mode=list ;; -printcert) mode=printcert ;; esac
  prev="${arg}"
done
case "${mode}" in
  list)
    if [ -n "${FAKE_KEYSTORE_UNREADABLE:-}" ]; then
      echo "keytool error: java.io.IOException: keystore password was incorrect"; exit 1
    fi
    if [ "${alias}" != "${FAKE_KEYSTORE_ALIAS:-spark-upload}" ]; then
      echo "keytool error: java.lang.Exception: Alias <${alias}> does not exist"; exit 1
    fi
    printf 'Alias name: %s\nCreation date: Sep 12, 2026\nEntry type: PrivateKeyEntry\n' "${alias}"
    printf 'Certificate fingerprints:\n\t SHA1: %s\n\t SHA256: 62:06:25:C6\nSignature algorithm name: SHA256withRSA\n' "${FAKE_KEYSTORE_SHA1}"
    ;;
  printcert)
    printf 'Signer #1:\n\nCertificate #1:\nOwner: CN=spark\nCertificate fingerprints:\n\t SHA1: %s\n\t SHA256: 62:06:25:C6\n' \
      "${FAKE_SIGNER_SHA1:-${FAKE_KEYSTORE_SHA1}}"
    [ -z "${FAKE_TWO_SIGNERS:-}" ] || printf '\nSigner #2:\n\nCertificate #1:\nCertificate fingerprints:\n\t SHA1: 00:11\n'
    ;;
esac
exit 0
FAKE_KEYTOOL

# `jarsigner`: assina anexando um marcador ao arquivo; verifica pelo marcador.
#   FAKE_JARSIGNER_FAILS=1     → a assinatura falha (senha errada, por exemplo)
#   FAKE_SIGNATURE_INVALID=1   → `-verify -strict` reprova (signer errado / cadeia inválida)
#   qualquer -storepass/-keypass no argv → exit 99 (nunca pode acontecer)
cat > "${FAKE_JDK}/bin/jarsigner" <<'FAKE_JARSIGNER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${JARSIGNER_CALL_LOG}"
verify="" strict="" file=""
for arg in "$@"; do
  case "${arg}" in
    -storepass*|-keypass*) exit 99 ;;
    -verify) verify=1 ;;
    -strict) strict=1 ;;
    *.aab) file="${arg}" ;;
  esac
done
if [ -n "${verify}" ]; then
  if grep -q 'JARSIGNER-SIGNATURE' "${file}"; then
    if [ -n "${strict}" ] && [ -n "${FAKE_SIGNATURE_INVALID:-}" ]; then
      printf '\njar verified, with signer errors.\n\nError:\nThis jar contains signed entries which are not signed by the specified alias(es).\n'
      exit 36
    fi
    printf '\njar verified.\n'
  else
    printf 'no manifest.\n\njar is unsigned.\n'
  fi
  exit 0
fi
[ -z "${FAKE_JARSIGNER_FAILS:-}" ] || { echo "jarsigner error: java.io.IOException: keystore password was incorrect"; exit 1; }
[ -t 0 ] || { echo "FAKE: assinatura sem TTY"; exit 98; }
printf '\nJARSIGNER-SIGNATURE\n' >> "${file}"
printf 'jar signed.\n\nWarning: \nThe signer'"'"'s certificate is self-signed.\n'
exit 0
FAKE_JARSIGNER

# `gradlew` (vive na raiz do repositório de teste, não no PATH):
#   printPlayReleaseMetadata → escreve app/build/play-release/metadata.properties com FAKE_META_*
#                              (FAKE_GRADLE_META_FAILS=1 reproduz o portão de endereço recusando)
#   testDebugUnitTest        → falha com FAKE_TESTS_FAIL=1
#   lintVitalRelease         → falha com FAKE_LINT_FAIL=1
#   bundleRelease            → falha com FAKE_BUNDLE_FAIL=1; FAKE_BUNDLE_NO_OUTPUT=1 termina sem
#                              produzir o AAB; FAKE_BUNDLE_PRESIGNED=1 produz um AAB já assinado
FAKE_GRADLEW="${SANDBOX}/gradlew.fake"
cat > "${FAKE_GRADLEW}" <<'FAKE_GRADLEW'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GRADLE_CALL_LOG}"
for arg in "$@"; do
  case "${arg}" in
    :app:printPlayReleaseMetadata)
      [ -z "${FAKE_GRADLE_META_FAILS:-}" ] || { echo "sparkBackendBaseUrl precisa ser HTTPS em release"; exit 1; }
      mkdir -p app/build/play-release
      {
        printf 'applicationId=%s\n' "${FAKE_META_APP_ID}"
        printf 'versionCode=%s\n' "${FAKE_META_VERSION_CODE:-3}"
        printf 'versionName=%s\n' "${FAKE_META_VERSION_NAME:-1.0.1}"
        printf 'compileSdk=%s\n' "${FAKE_META_COMPILE_SDK:-36}"
        printf 'targetSdk=%s\n' "${FAKE_META_TARGET_SDK:-36}"
        printf 'backendBaseUrl=%s\n' "${FAKE_META_BACKEND-${FAKE_DEFAULT_BACKEND}}"
      } > app/build/play-release/metadata.properties ;;
    :app:testDebugUnitTest) [ -z "${FAKE_TESTS_FAIL:-}" ] || { echo "FAILED tests"; exit 1; } ;;
    :app:lintVitalRelease) [ -z "${FAKE_LINT_FAIL:-}" ] || { echo "Lint found fatal errors"; exit 1; } ;;
    :app:verifyReleaseEndpointGate) : ;;
    :app:bundleRelease)
      [ -z "${FAKE_BUNDLE_FAIL:-}" ] || { echo "Execution failed for task ':app:packageReleaseBundle'"; exit 1; }
      [ -z "${FAKE_BUNDLE_NO_OUTPUT:-}" ] || exit 0
      mkdir -p app/build/outputs/bundle/release
      printf 'aab-without-signature %s\n' "${RANDOM}" > app/build/outputs/bundle/release/app-release.aab
      [ -z "${FAKE_BUNDLE_PRESIGNED:-}" ] || printf 'JARSIGNER-SIGNATURE\n' >> app/build/outputs/bundle/release/app-release.aab ;;
  esac
done
exit 0
FAKE_GRADLEW
chmod +x "${FAKE_BIN}"/* "${FAKE_JDK}"/bin/* "${FAKE_GRADLEW}"

# ---------------------------------------------------------------- repositório de teste

REPO="" HOME_DIR="" SDK_DIR="" KEYSTORE=""
new_repo() {
  REPO="$(mktemp -d "${SANDBOX}/repo.XXXXXX")"
  HOME_DIR="${SANDBOX}/home.$(basename "${REPO}")"
  SDK_DIR="${SANDBOX}/sdk"
  mkdir -p "${REPO}/app" "${REPO}/ops/android" "${HOME_DIR}/.android" "${SDK_DIR}/platforms"
  : > "${REPO}/settings.gradle.kts"
  cp "${SCRIPT}" "${REPO}/ops/android/build-play-bundle.sh"
  cp "${CONF}" "${REPO}/ops/android/play-release.conf"
  cp "${FAKE_GRADLEW}" "${REPO}/gradlew"
  printf 'sdk.dir=%s\n' "${SDK_DIR}" > "${REPO}/local.properties"
  printf '{ "client": [ { "client_info": { "android_client_info": { "package_name": "%s" } } } ] }\n' \
    "${CANONICAL_APP_ID}" > "${REPO}/app/google-services.json"
  KEYSTORE="${HOME_DIR}/.android/spark-upload.jks"
  printf 'not-a-real-keystore\n' > "${KEYSTORE}"
}

dist_listing() { [ -d "${REPO}/dist" ] || return 0; find "${REPO}/dist" -mindepth 1 | sort; }

# run_case [--tty] [--] [args do script]  — variáveis FAKE_*/SPARK_* pelo ambiente do chamador.
# Saída (stdout+stderr) em ${OUT}; código em ${CODIGO}; logs dos dublês em ${GRADLE_LOG} etc.;
# ${DIST_BEFORE} é o conteúdo de dist/ antes da execução (para provar que uma falha não publica).
OUT="" CODIGO=0 GRADLE_LOG="" JARSIGNER_LOG="" KEYTOOL_LOG="" DIST_BEFORE=""
run_case() {
  local tty=""
  if [ "${1:-}" = "--tty" ]; then tty=1; shift; fi
  [ "${1:-}" != "--" ] || shift
  GRADLE_LOG="$(mktemp "${SANDBOX}/gradle.XXXXXX")"
  JARSIGNER_LOG="$(mktemp "${SANDBOX}/jarsigner.XXXXXX")"
  KEYTOOL_LOG="$(mktemp "${SANDBOX}/keytool.XXXXXX")"
  local out_file="${SANDBOX}/out.$$"
  CODIGO=0
  DIST_BEFORE="$(dist_listing)"
  local -a env_args=(
    "PATH=${FAKE_BIN}:${PATH}" "HOME=${HOME_DIR}" "JAVA_HOME=${FAKE_JDK}"
    "GRADLE_CALL_LOG=${GRADLE_LOG}" "JARSIGNER_CALL_LOG=${JARSIGNER_LOG}" "KEYTOOL_CALL_LOG=${KEYTOOL_LOG}"
    "FAKE_KEYSTORE_SHA1=${FAKE_KEYSTORE_SHA1:-${CANONICAL_SHA1}}"
    "FAKE_META_APP_ID=${FAKE_META_APP_ID:-${CANONICAL_APP_ID}}"
    "FAKE_DEFAULT_BACKEND=${CANONICAL_BACKEND}"
  )
  if [ -n "${tty}" ]; then
    # `script -e` devolve o código do comando; `-q` sem cabeçalho; o typescript vai para /dev/null.
    env "${env_args[@]}" script -qec "'${REPO}/ops/android/build-play-bundle.sh' $*" /dev/null \
      > "${out_file}" 2>&1 < /dev/null || CODIGO=$?
  else
    env "${env_args[@]}" "${REPO}/ops/android/build-play-bundle.sh" "$@" \
      > "${out_file}" 2>&1 < /dev/null || CODIGO=$?
  fi
  OUT="$(tr -d '\r' < "${out_file}")"
  rm -f "${out_file}"
}

falhou()        { [ "${CODIGO}" != "0" ] && echo sim || echo não; }
saida_tem()     { printf '%s\n' "${OUT}" | grep -qF -- "$1" && echo sim || echo não; }
gradle_chamou() { grep -q -- "$1" "${GRADLE_LOG}" && echo sim || echo não; }
assinou()       { grep -v -- '-verify' "${JARSIGNER_LOG}" | grep -q . && echo sim || echo não; }
dist_tem()      { [ -e "${REPO}/dist/$1" ] && echo sim || echo não; }

# Uma falha fechada: exit ≠ 0, RESULT: FAIL, a mensagem esperada, e a etapa seguinte nunca rodou.
expect_fail() {
  local titulo="$1" mensagem="$2" etapa_seguinte="${3:-}"
  check "${titulo}: exit ≠ 0" "sim" "$(falhou)"
  check "${titulo}: RESULT: FAIL" "sim" "$(saida_tem 'RESULT: FAIL')"
  check "${titulo}: explica (${mensagem})" "sim" "$(saida_tem "${mensagem}")"
  [ -z "${etapa_seguinte}" ] || check "${titulo}: para antes de ${etapa_seguinte}" "não" "$(gradle_chamou "${etapa_seguinte}")"
  check "${titulo}: nada novo em dist/" "sim" "$( [ "$(dist_listing)" = "${DIST_BEFORE}" ] && echo sim || echo não )"
}

echo "=== estático: a fronteira de segurança está no código ==="
code="$(grep -vE '^[[:space:]]*#' "${SCRIPT}")"
check "set -Eeuo pipefail" "sim" "$(printf '%s\n' "${code}" | grep -q '^set -Eeuo pipefail' && echo sim || echo não)"
check "sem eval" "0" "$(printf '%s\n' "${code}" | grep -cE '(^|[^a-z_])eval[[:space:]]' || true)"
check "sem set -x" "0" "$(printf '%s\n' "${code}" | grep -c 'set -x' || true)"
check "sem source/. de arquivo de configuração" "0" "$(printf '%s\n' "${code}" | grep -cE '^[[:space:]]*(source|\.) ' || true)"
check "jarsigner nunca recebe -storepass/-keypass" "0" "$(printf '%s\n' "${code}" | grep -cE '(^|[[:space:]])-(storepass|keypass)([[:space:]]|$)' || true)"
check "nenhuma variável de senha é lida" "0" "$(printf '%s\n' "${code}" | grep -cE '\$\{?[A-Z_]*PASS(WORD)?\b' || true)"
check "o executável tem bit de execução" "sim" "$( [ -x "${SCRIPT}" ] && echo sim || echo não )"
check "o fingerprint canônico vive só em play-release.conf" "0" "$(printf '%s\n' "${code}" | grep -c 'E1:32:79' || true)"
check "o applicationId esperado vive só em play-release.conf" "0" "$(printf '%s\n' "${code}" | grep -c "${CANONICAL_APP_ID}" || true)"
check "o endpoint de produção vive só em play-release.conf" "0" "$(printf '%s\n' "${code}" | grep -c 'run\.app' || true)"

echo
echo "=== sucesso: fluxo completo com TTY ==="
new_repo
run_case --tty
check "exit 0" "0" "${CODIGO}"
check "RESULT: PASS" "sim" "$(saida_tem 'RESULT: PASS')"
check "ordem: testes → lint → portão → bundle" "sim" \
  "$(paste -sd' ' "${GRADLE_LOG}" | grep -q 'testDebugUnitTest.*lintVitalRelease.*verifyReleaseEndpointGate.*bundleRelease' && echo sim || echo não)"
check "os metadados vieram do Gradle, antes dos testes" "sim" \
  "$(paste -sd' ' "${GRADLE_LOG}" | grep -q 'printPlayReleaseMetadata.*testDebugUnitTest' && echo sim || echo não)"
check "artefato dist/spark-1.0.1-3.aab" "sim" "$(dist_tem spark-1.0.1-3.aab)"
check "checksum dist/spark-1.0.1-3.aab.sha256" "sim" "$(dist_tem spark-1.0.1-3.aab.sha256)"
check "o checksum confere (sha256sum -c)" "sim" "$( (cd "${REPO}/dist" && sha256sum -c --quiet spark-1.0.1-3.aab.sha256 > /dev/null 2>&1) && echo sim || echo não )"
check "o checksum publicado é o do resumo" "sim" \
  "$(saida_tem "$(cut -d' ' -f1 "${REPO}/dist/spark-1.0.1-3.aab.sha256")")"
check "o artefato publicado está assinado" "sim" "$(grep -q 'JARSIGNER-SIGNATURE' "${REPO}/dist/spark-1.0.1-3.aab" && echo sim || echo não)"
check "o intermediário do Gradle NÃO foi alterado" "não" \
  "$(grep -q 'JARSIGNER-SIGNATURE' "${REPO}/app/build/outputs/bundle/release/app-release.aab" && echo sim || echo não)"
check "a assinatura foi feita numa cópia com o nome final" "sim" \
  "$(grep -v -- '-verify' "${JARSIGNER_LOG}" | grep -q 'spark-1.0.1-3.aab' && echo sim || echo não)"
check "jarsigner: SHA256withRSA + SHA-256, keystore e alias canônicos" "sim" \
  "$(grep -v -- '-verify' "${JARSIGNER_LOG}" | grep -q -- "-sigalg SHA256withRSA -digestalg SHA-256 -keystore ${KEYSTORE} .* spark-upload" && echo sim || echo não)"
check "jarsigner: nenhuma senha no argv" "não" "$(grep -qE -- '-storepass|-keypass' "${JARSIGNER_LOG}" && echo sim || echo não)"
check "verificação estrita contra a keystore e o alias" "sim" \
  "$(grep -q -- "-verify -strict -protected -keystore ${KEYSTORE} .* spark-upload" "${JARSIGNER_LOG}" && echo sim || echo não)"
check "o certificado do signer foi inspecionado (keytool -printcert -jarfile)" "sim" "$(grep -q -- '-printcert -jarfile' "${KEYTOOL_LOG}" && echo sim || echo não)"
check "o certificado da keystore foi lido sem senha (-protected)" "sim" "$(grep -- '-list' "${KEYTOOL_LOG}" | grep -q -- '-protected' && echo sim || echo não)"
check "área temporária removida de dist/" "não" "$(dist_listing | grep -q '/\.build-play-bundle' && echo sim || echo não)"
check "resumo: applicationId, versão, targetSdk, backend, commit" "sim" \
  "$( [ "$(saida_tem "Application ID ....... ${CANONICAL_APP_ID}")" = sim ] && [ "$(saida_tem 'Version code ......... 3')" = sim ] \
     && [ "$(saida_tem 'Target SDK ........... 36')" = sim ] && [ "$(saida_tem "Backend .............. ${CANONICAL_BACKEND}")" = sim ] \
     && [ "$(saida_tem 'Commit ............... abcdef123456')" = sim ] && echo sim || echo não )"
check "resumo: todas as etapas PASS" "6" "$(printf '%s\n' "${OUT}" | grep -cE '^  (Firebase config|Unit tests|Lint release|Bundle|Upload key|Signature) \.+ PASS$' || true)"

echo
echo "=== sucesso: --check sem TTY valida tudo e não assina ==="
new_repo
run_case --check
check "exit 0" "0" "${CODIGO}"
check "RESULT: CHECK PASS" "sim" "$(saida_tem 'RESULT: CHECK PASS')"
check "não diz RESULT: PASS puro" "não" "$(printf '%s\n' "${OUT}" | grep -q '^RESULT: PASS$' && echo sim || echo não)"
check "testes, lint e bundle rodaram" "sim" \
  "$( [ "$(gradle_chamou testDebugUnitTest)" = sim ] && [ "$(gradle_chamou lintVitalRelease)" = sim ] && [ "$(gradle_chamou bundleRelease)" = sim ] && echo sim || echo não )"
check "o estado do intermediário foi conferido (jarsigner -verify)" "sim" "$(grep -q -- '-verify' "${JARSIGNER_LOG}" && echo sim || echo não)"
check "nunca assinou" "não" "$(assinou)"
check "dist/ não foi criado" "não" "$( [ -d "${REPO}/dist" ] && echo sim || echo não )"

echo
echo "=== o artefato repetido é recusado ANTES do build ==="
new_repo
mkdir -p "${REPO}/dist" && printf 'old\n' > "${REPO}/dist/spark-1.0.1-3.aab"
run_case --tty
check "exit ≠ 0" "sim" "$(falhou)"
check "explica que dist/spark-1.0.1-3.aab já existe" "sim" "$(saida_tem 'dist/spark-1.0.1-3.aab já existe')"
check "nenhum teste nem build rodou" "não" "$(gradle_chamou testDebugUnitTest)"
check "o artefato antigo não foi tocado" "old" "$(cat "${REPO}/dist/spark-1.0.1-3.aab")"
new_repo
mkdir -p "${REPO}/dist" && printf 'stale\n' > "${REPO}/dist/spark-1.0.1-3.aab.sha256"
run_case --tty
check "um .sha256 órfão também é recusado" "sim" "$(saida_tem 'dist/spark-1.0.1-3.aab.sha256 já existe')"

echo
echo "=== sem TTY, sem --check: falha no preflight, antes de qualquer Gradle ==="
new_repo
run_case
check "exit ≠ 0" "sim" "$(falhou)"
check "explica que a assinatura requer terminal interativo" "sim" "$(saida_tem 'assinatura requer terminal interativo')"
check "manda executar localmente" "sim" "$(saida_tem './ops/android/build-play-bundle.sh')"
check "diz para nunca fornecer a senha ao agente" "sim" "$(saida_tem 'Nunca forneça a senha da upload key a um agente')"
check "o Gradle não foi chamado" "não" "$(grep -q . "${GRADLE_LOG}" && echo sim || echo não)"

echo
echo "=== a senha não entra por argumento nem por ambiente ==="
new_repo
run_case --tty --storepass=abc
expect_fail "--storepass" "nunca é aceita como argumento"
run_case --tty -- --keypass abc
expect_fail "--keypass" "nunca é aceita como argumento"
run_case --tty -- --password=abc
expect_fail "--password" "nunca é aceita como argumento"
run_case --tty -- --force
expect_fail "--force (não existe)" "argumento desconhecido"
KEYSTORE_PASSWORD=abc run_case --tty
expect_fail "KEYSTORE_PASSWORD no ambiente" "não aceita senha por ambiente" printPlayReleaseMetadata
PLAY_KEYSTORE_PASSWORD=abc run_case --tty
expect_fail "PLAY_KEYSTORE_PASSWORD no ambiente" "não aceita senha por ambiente" printPlayReleaseMetadata

echo
echo "=== preflight: ferramentas ausentes ==="
new_repo
mv "${FAKE_JDK}/bin/jarsigner" "${FAKE_JDK}/bin/jarsigner.off"
run_case --tty
expect_fail "sem jarsigner" "jarsigner não existe em JAVA_HOME" printPlayReleaseMetadata
mv "${FAKE_JDK}/bin/jarsigner.off" "${FAKE_JDK}/bin/jarsigner"
mv "${FAKE_JDK}/bin/keytool" "${FAKE_JDK}/bin/keytool.off"
run_case --tty
expect_fail "sem keytool" "keytool não existe em JAVA_HOME" printPlayReleaseMetadata
mv "${FAKE_JDK}/bin/keytool.off" "${FAKE_JDK}/bin/keytool"
mv "${FAKE_JDK}/bin/java" "${FAKE_JDK}/bin/java.off"
run_case --tty
expect_fail "sem java" "java não existe em JAVA_HOME" printPlayReleaseMetadata
mv "${FAKE_JDK}/bin/java.off" "${FAKE_JDK}/bin/java"
mv "${FAKE_JDK}/bin/javac" "${FAKE_JDK}/bin/javac.off"
run_case --tty
expect_fail "sem javac (JRE não serve)" "javac não existe em JAVA_HOME" printPlayReleaseMetadata
mv "${FAKE_JDK}/bin/javac.off" "${FAKE_JDK}/bin/javac"
chmod -x "${REPO}/gradlew"
run_case --tty
expect_fail "gradlew sem bit de execução" "não é executável" printPlayReleaseMetadata
chmod +x "${REPO}/gradlew"
rm "${REPO}/local.properties"
run_case --tty
expect_fail "sem Android SDK resolvível" "nenhum Android SDK resolvível" printPlayReleaseMetadata
printf 'sdk.dir=%s\n' "${SDK_DIR}" > "${REPO}/local.properties"

echo
echo "=== Git ==="
new_repo
FAKE_GIT_DIRTY=1 run_case --tty
expect_fail "worktree suja" "alterações não commitadas" printPlayReleaseMetadata
FAKE_GIT_BRANCH=feature/x run_case --tty
expect_fail "fora de main" "só sai de 'main'" printPlayReleaseMetadata
FAKE_GIT_DETACHED=1 run_case --tty
expect_fail "HEAD desanexado" "desanexado" printPlayReleaseMetadata
FAKE_GIT_NOT_REPO=1 run_case --tty
expect_fail "fora de um repositório" "não é um repositório Git" printPlayReleaseMetadata

echo
echo "=== configuração efetiva do release ==="
new_repo
FAKE_META_APP_ID=com.example.outro run_case --tty
expect_fail "applicationId diferente" "só empacota 'com.aistudio.workout.v2'" testDebugUnitTest
FAKE_META_BACKEND='' run_case --tty
expect_fail "backend vazio" "sparkBackendBaseUrl efetivo está vazio" testDebugUnitTest
FAKE_META_BACKEND=https://spark-staging.example.com run_case --tty
expect_fail "backend diferente do de produção" "exige '${CANONICAL_BACKEND}'" testDebugUnitTest
FAKE_META_BACKEND=http://10.0.2.2:8080 run_case --tty
expect_fail "backend HTTP (se o portão do Gradle deixasse passar)" "não é HTTPS" testDebugUnitTest
FAKE_GRADLE_META_FAILS=1 run_case --tty
expect_fail "o portão de endereço do Gradle recusou" "printPlayReleaseMetadata falhou" testDebugUnitTest
FAKE_META_BACKEND="${CANONICAL_BACKEND}/" run_case --tty
check "barra final no backend é tolerada" "0" "${CODIGO}"
FAKE_META_TARGET_SDK=35 FAKE_META_COMPILE_SDK=35 run_case --tty
expect_fail "targetSdk abaixo do mínimo" "no mínimo 36" testDebugUnitTest
FAKE_META_TARGET_SDK=37 FAKE_META_COMPILE_SDK=36 run_case --tty
expect_fail "compileSdk menor que targetSdk" "é menor que targetSdk" testDebugUnitTest
new_repo
FAKE_META_TARGET_SDK=37 FAKE_META_COMPILE_SDK=37 run_case --tty
check "API acima do mínimo passa (o mínimo não é um teto)" "0" "${CODIGO}"
new_repo
FAKE_META_VERSION_NAME='1.0 beta/2' run_case --tty
expect_fail "versionName que não cabe num nome de arquivo" "não cabem num nome de artefato" testDebugUnitTest
new_repo
FAKE_META_VERSION_NAME=2.3.4 FAKE_META_VERSION_CODE=17 run_case --tty
check "composição do nome: dist/spark-2.3.4-17.aab" "sim" "$(dist_tem spark-2.3.4-17.aab)"
check "…e dist/spark-2.3.4-17.aab.sha256" "sim" "$(dist_tem spark-2.3.4-17.aab.sha256)"

echo
echo "=== Firebase ==="
new_repo
rm "${REPO}/app/google-services.json"
run_case --tty
expect_fail "google-services.json ausente" "app/google-services.json não existe" testDebugUnitTest
: > "${REPO}/app/google-services.json"
run_case --tty
expect_fail "google-services.json vazio" "está vazio" testDebugUnitTest
printf '{ "client": [ { "client_info": { "android_client_info": { "package_name": "com.example.outro" } } } ] }\n' > "${REPO}/app/google-services.json"
run_case --tty
expect_fail "google-services.json de outro app" "não contém um cliente para com.aistudio.workout.v2" testDebugUnitTest
check "o conteúdo do JSON não é impresso" "não" "$(saida_tem 'android_client_info')"

echo
echo "=== upload key ==="
new_repo
rm "${KEYSTORE}"
run_case --tty
expect_fail "keystore ausente" "upload key não encontrada" testDebugUnitTest
new_repo
SPARK_UPLOAD_KEY_ALIAS=outro-alias run_case --tty
expect_fail "alias incorreto" "não foi possível ler o certificado do alias 'outro-alias'" testDebugUnitTest
check "a mensagem repete o erro do keytool" "sim" "$(saida_tem 'does not exist')"
FAKE_KEYSTORE_UNREADABLE=1 run_case --tty
expect_fail "certificado ilegível sem senha (PKCS12)" "exige o" testDebugUnitTest
FAKE_KEYSTORE_SHA1='AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD' run_case --tty
expect_fail "fingerprint incorreto" "upload key não corresponde ao certificado esperado" testDebugUnitTest
check "diz que é uma upload key DIFERENTE da registrada" "sim" "$(saida_tem 'DIFERENTE da atualmente registrada')"
check "nunca assinou" "não" "$(assinou)"
FAKE_KEYSTORE_SHA1="$(printf '%s' "${CANONICAL_SHA1}" | tr -d ':' | tr '[:upper:]' '[:lower:]')" run_case --tty
check "fingerprint em minúsculas e sem separadores é reconhecido" "0" "${CODIGO}"
new_repo
SPARK_UPLOAD_KEYSTORE="${HOME_DIR}/outra.jks" run_case --tty
expect_fail "SPARK_UPLOAD_KEYSTORE apontando para arquivo inexistente" "upload key não encontrada: ${HOME_DIR}/outra.jks" testDebugUnitTest
cp "${KEYSTORE}" "${HOME_DIR}/outra.jks"
SPARK_UPLOAD_KEYSTORE="${HOME_DIR}/outra.jks" run_case --tty
check "SPARK_UPLOAD_KEYSTORE válido é usado (e o fingerprint continua exigido)" "sim" \
  "$( [ "${CODIGO}" = 0 ] && grep -q -- "-keystore ${HOME_DIR}/outra.jks" "${JARSIGNER_LOG}" && echo sim || echo não )"

echo
echo "=== Gradle: testes, lint, bundle ==="
new_repo
FAKE_TESTS_FAIL=1 run_case --tty
expect_fail "testDebugUnitTest falhou" ":app:testDebugUnitTest falhou" lintVitalRelease
check "…sem bundle" "não" "$(gradle_chamou bundleRelease)"
FAKE_LINT_FAIL=1 run_case --tty
expect_fail "lintVitalRelease falhou" ":app:lintVitalRelease falhou" bundleRelease
FAKE_BUNDLE_FAIL=1 run_case --tty
expect_fail "bundleRelease falhou" ":app:bundleRelease falhou"
check "…sem assinatura" "não" "$(assinou)"
FAKE_BUNDLE_NO_OUTPUT=1 run_case --tty
expect_fail "AAB intermediário ausente" "terminou sem produzir app/build/outputs/bundle/release/app-release.aab"
# Um AAB antigo no lugar do intermediário não pode passar por novo.
mkdir -p "${REPO}/app/build/outputs/bundle/release"
printf 'STALE-AAB\n' > "${REPO}/app/build/outputs/bundle/release/app-release.aab"
FAKE_BUNDLE_NO_OUTPUT=1 run_case --tty
expect_fail "AAB antigo não é reaproveitado" "terminou sem produzir"
check "…o antigo foi removido antes do build" "não" "$( [ -e "${REPO}/app/build/outputs/bundle/release/app-release.aab" ] && echo sim || echo não )"
FAKE_BUNDLE_PRESIGNED=1 run_case --tty
expect_fail "AAB intermediário já assinado" "assinado de forma inesperada"
check "…explica que o processo canônico mudou" "sim" "$(saida_tem 'signingConfig de release novo')"
check "…e não assina por cima" "não" "$(assinou)"

echo
echo "=== assinatura e verificação ==="
new_repo
FAKE_JARSIGNER_FAILS=1 run_case --tty
expect_fail "jarsigner falhou (senha errada)" "jarsigner falhou; nenhum artefato foi publicado"
check "…o intermediário continua intacto" "sim" "$(grep -q 'aab-without-signature' "${REPO}/app/build/outputs/bundle/release/app-release.aab" && echo sim || echo não)"
FAKE_SIGNATURE_INVALID=1 run_case --tty
expect_fail "assinatura final inválida (strict)" "assinatura final inválida"
FAKE_SIGNER_SHA1='AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD' run_case --tty
expect_fail "signer diferente da upload key" "não é a upload key registrada"
FAKE_TWO_SIGNERS=1 run_case --tty
expect_fail "dois signers" "esperado exatamente 1"
run_case --tty
check "o mesmo repositório assina com sucesso depois das falhas" "0" "${CODIGO}"
run_case --tty
expect_fail "segunda execução com a mesma versão" "dist/spark-1.0.1-3.aab já existe" testDebugUnitTest

finish_checks "build-play-bundle: falha fechada em cada etapa, senha só no jarsigner, artefato só depois de tudo passar"
