
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.services)
}

ksp {
    arg("room.schemaLocation", projectDir.absolutePath + "/schemas")
}


/**
 * O endereço do Spark Backend que este build pode usar (T16.8 §6/§7).
 *
 * O portão existe em duas camadas de propósito, e elas respondem a perguntas diferentes:
 *
 * - **aqui**, em tempo de build, para que um APK de release com endereço inseguro simplesmente não
 *   seja produzido — o erro aparece para quem construiu, e não para quem instalou;
 * - em `SparkBackendEndpoint`, em runtime, para que a regra continue valendo se algum caminho
 *   futuro montar o endereço por outro meio.
 *
 * Vazio continua sendo válido e é o padrão: significa "backend não configurado". Sem endereço
 * nenhuma requisição sai, e treino, execução, histórico, templates e gamificação continuam
 * completos — o Spark é local-first, e essa propriedade não depende da nuvem.
 */

/**
 * O portão de endereço de release, e a tarefa que prova que ele concorda com o de runtime.
 *
 * ## Por que uma classe, e não funções soltas no script
 *
 * Porque a verificação é uma **tarefa**, e o cache de configuração do Gradle não serializa
 * referências ao objeto do script: um `doLast` que chamasse funções de topo do `build.gradle.kts`
 * falha o build com "cannot serialize Gradle script object references". Com a lógica dentro da
 * classe da tarefa e no `companion`, tanto a tarefa quanto `releaseBackendBaseUrl` usam a mesma
 * implementação — e não há duas cópias da regra dentro do próprio arquivo.
 */
abstract class VerifyReleaseEndpointGate : DefaultTask() {

  @get:InputFile
  abstract val casesTable: RegularFileProperty

  @TaskAction
  fun verify() {
    val file = casesTable.get().asFile
    val failures = mutableListOf<String>()
    var checked = 0

    file.readLines().forEach { line ->
      val text = line.trim()
      if (text.isEmpty() || text.startsWith("#")) return@forEach

      val columns = text.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
      if (columns.size != 2) {
        throw GradleException("linha malformada na tabela de casos: '$line'")
      }
      val verdict = columns[0]
      val url = columns[1]
      val expected = when (verdict) {
        "ACCEPT" -> true
        "REJECT" -> false
        else -> throw GradleException("veredito desconhecido '$verdict' na tabela de casos")
      }

      checked++
      val actual = isAcceptable(url)
      if (actual != expected) {
        failures += "  $url — esperado $verdict, o portão do build disse " +
          (if (actual) "ACCEPT" else "REJECT")
      }
    }

    // Uma tabela vazia deixaria os dois portões "de acordo" sobre nada.
    if (checked == 0) {
      throw GradleException("a tabela de casos está vazia; o portão não estaria sendo verificado")
    }
    if (failures.isNotEmpty()) {
      throw GradleException(
        "o portão de endereço do build discorda da tabela compartilhada:\n" +
          failures.joinToString("\n")
      )
    }
    logger.lifecycle("portão de endereço de release: $checked casos conferidos")
  }

  companion object {

    /**
     * O host de uma URL, ou `null` quando o texto não descreve um.
     *
     * Espelha `SparkBackendEndpoint.hostOf`, e pelos mesmos motivos: `java.net.URI` aceita coisas
     * demais em silêncio, e credencial embutida (`https://api.exemplo.com@10.0.2.2/`) esconde qual
     * é o host real — um endereço que mente sobre para onde vai não pode passar por ser "bonito".
     *
     * A versão da T16.8 fazia `substringBefore(':')` direto, o que quebrava em IPv6: para
     * `https://[::1]` ela extraía o host `"["` e concluía que era público.
     */
    fun hostOf(url: String): String? {
      val afterScheme = url.substringAfter("://", "")
      if (afterScheme.isEmpty()) return null
      val authority = afterScheme.substringBefore('/').substringBefore('?')
      if (authority.contains('@')) return null
      val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
      } else {
        authority.substringBefore(':')
      }
      return host.lowercase().takeIf { it.isNotEmpty() }
    }

    /**
     * O host não é publicamente roteável — IPv4 ou IPv6 (T16.8.1 §10).
     *
     * Espelha `SparkBackendEndpoint.isPrivateAddress`. As duas implementações são obrigadas a
     * concordar caso a caso pela tabela em `contracts/endpoint/release-endpoint-cases.tsv`,
     * aplicada a este portão por `verifyReleaseEndpointGate` e ao de runtime por
     * `SparkReleaseEndpointTableTest`.
     */
    fun isPrivateHost(host: String): Boolean {
      if (host in setOf("10.0.2.2", "localhost", "127.0.0.1", "::1")) return true

      if (!host.contains(':')) {
        val octets = host.split('.')
        if (octets.size != 4) return false
        val numbers = octets.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        return isPrivateIpv4Octets(numbers)
      }

      // Um IPv6 que não conseguimos entender é tratado como privado: em release, recusar um
      // endereço estranho só desliga a nuvem — que é um estado normal e completo do Spark —,
      // enquanto aceitá-lo põe o APK publicado falando com uma máquina qualquer.
      val hextets = ipv6Hextets(host.substringBefore('%')) ?: return true
      return when {
        hextets.all { it == 0 } -> true
        hextets.take(7).all { it == 0 } && hextets[7] == 1 -> true
        hextets[0] and 0xFE00 == 0xFC00 -> true
        hextets[0] and 0xFFC0 == 0xFE80 -> true
        hextets[0] and 0xFFC0 == 0xFEC0 -> true
        hextets.take(5).all { it == 0 } && hextets[5] == 0xFFFF -> isPrivateIpv4Octets(
          listOf(hextets[6] shr 8, hextets[6] and 0xFF, hextets[7] shr 8, hextets[7] and 0xFF)
        )
        else -> false
      }
    }

    /** Um APK de release pode usar este endereço? A mesma pergunta que `SparkBackendEndpoint` responde. */
    fun isAcceptable(raw: String): Boolean {
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) return false
      if (trimmed.substringBefore("://", "").lowercase() != "https") return false
      val host = hostOf(trimmed) ?: return false
      return !isPrivateHost(host)
    }

    private fun isPrivateIpv4Octets(numbers: List<Int>): Boolean = when {
      numbers[0] == 10 -> true
      numbers[0] == 127 -> true
      numbers[0] == 172 && numbers[1] in 16..31 -> true
      numbers[0] == 192 && numbers[1] == 168 -> true
      numbers[0] == 169 && numbers[1] == 254 -> true
      numbers[0] == 100 && numbers[1] in 64..127 -> true
      else -> false
    }

    private fun ipv6Hextets(address: String): List<Int>? {
      var text = address

      // Forma mista `::ffff:1.2.3.4`: os quatro octetos viram os dois últimos hextets.
      val lastColon = text.lastIndexOf(':')
      if (lastColon >= 0 && text.substring(lastColon + 1).contains('.')) {
        val octets = text.substring(lastColon + 1).split('.')
        if (octets.size != 4) return null
        val numbers = octets.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it !in 0..255 }) return null
        text = text.substring(0, lastColon + 1) +
          ((numbers[0] shl 8) or numbers[1]).toString(16) + ":" +
          ((numbers[2] shl 8) or numbers[3]).toString(16)
      }

      val halves = text.split("::")
      if (halves.size > 2) return null

      fun groupsOf(part: String): List<String>? {
        if (part.isEmpty()) return emptyList()
        val groups = part.split(':')
        return if (groups.any { it.isEmpty() }) null else groups
      }

      val head = groupsOf(halves[0]) ?: return null
      val tail = if (halves.size == 2) (groupsOf(halves[1]) ?: return null) else emptyList()
      val groups = if (halves.size == 2) {
        val zeros = 8 - head.size - tail.size
        if (zeros < 0) return null
        head + List(zeros) { "0" } + tail
      } else {
        head
      }
      if (groups.size != 8) return null

      return groups.map { group ->
        if (group.length > 4) return null
        val value = group.toIntOrNull(16) ?: return null
        if (value !in 0..0xFFFF) return null
        value
      }
    }
  }
}

fun releaseBackendBaseUrl(providers: ProviderFactory): String {
  val raw = providers.gradleProperty("sparkBackendBaseUrl").getOrElse("").trim()
  if (raw.isEmpty()) return ""

  if (!raw.startsWith("https://")) {
    throw GradleException(
      "sparkBackendBaseUrl precisa ser HTTPS em release (recebido com esquema inválido). " +
        "Produção fica atrás de Caddy + TLS; para desenvolvimento use -PsparkBackendBaseUrlDebug."
    )
  }
  if (!VerifyReleaseEndpointGate.isAcceptable(raw)) {
    throw GradleException(
      "sparkBackendBaseUrl aponta para um host de desenvolvimento, de rede privada ou " +
        "indecifrável ('" + (VerifyReleaseEndpointGate.hostOf(raw) ?: "sem host") + "'). " +
        "Um APK de release com esse endereço fala com outra máquina na rede de quem o instalar."
    )
  }
  return raw
}

tasks.register<VerifyReleaseEndpointGate>("verifyReleaseEndpointGate") {
  group = "verification"
  description = "Confere o portão de endereço de release contra a tabela compartilhada."
  casesTable.set(
    rootProject.layout.projectDirectory.file("contracts/endpoint/release-endpoint-cases.tsv")
  )
}

android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // `isReturnDefaultValues` saiu na auditoria de 2026-09-12. Com ele, um teste de JVM puro
            // que tocasse `android.*` sem Robolectric recebia `null`/`0`/`false` em silêncio, e a
            // asserção seguinte passava ou falhava por acaso. Sem ele, o mesmo teste falha com
            // "Method ... not mocked" — que é a informação certa: falta `@RunWith(AndroidJUnit4)`.
        }
    }
    // Fonte canônica dos schemas do Room: `app/schemas`, gerada pelo KSP e versionada no Git.
    // Nada é copiado para `build/intermediates`, `src/main/assets` ou `src/test/assets`.
    //
    // `MigrationTestHelper` lê os schemas pelo AssetManager. Nos testes locais (Robolectric) o AGP
    // aponta `android_merged_assets` para os assets mesclados da variante testada, então apenas a
    // entrada de `debug` faz o teste de migração encontrar os arquivos — assets dos source sets de
    // teste não são lidos nesse caminho. Sem ela, `AppDatabaseMigrationTest` falha com
    // "Cannot find the schema file in the assets folder". A variante de release não os recebe.
    sourceSets {
        getByName("androidTest") {
            assets.directories.add("$projectDir/schemas")
        }
        getByName("test") {
            assets.directories.add("$projectDir/schemas")
        }
        getByName("debug") {
            assets.directories.add("$projectDir/schemas")
        }
    }

  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.workout.v2"
    minSdk = 24
    // API 36 (auditoria 2026-09-12 §2). A política do Play exige a API 36 para atualizações
    // desde 31/08/2026: com 35 o próximo envio ao console é recusado. O `minSdk` continua 24 — o
    // que destrava `java.time` nesses aparelhos é o desugaring, não o alvo.
    targetSdk = 36
    versionCode = 2
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

  }

  buildTypes {
    release {
      isCrunchPngs = false
      // R8 ligado (auditoria 2026-09-12 §2). O APK saía com 18,5 MB e com
      // `material-icons-extended` inteiro para 85 ícones usados; sem encolhimento, cada ícone da
      // biblioteca viajava para o aparelho. Ligar o R8 também torna obrigatórias as regras de
      // `proguard-rules.pro`: o que é lido por reflexão (Moshi) ou por nome (enums persistidos,
      // recursos resolvidos por `getIdentifier`) precisa estar declarado lá, senão a quebra
      // aparece **só** em release.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

      // Endereço do Spark Backend em produção (T16.1/T16.2). Não é segredo, mas também não é
      // código: vem de `-PsparkBackendBaseUrl=...` ou de `local.properties`, e nasce vazio.
      // Vazio significa "backend não configurado": o Coach responde indisponível, nenhuma
      // requisição sai e o núcleo do Spark continua completo. Nunca há fallback para localhost.
      buildConfigField(
        "String",
        "SPARK_BACKEND_BASE_URL",
        "\"${releaseBackendBaseUrl(providers)}\""
      )
    }
    debug {
      // Endereço de desenvolvimento, separado do de produção de propósito: apontar o app de
      // release para uma máquina local seria um acidente esperando acontecer. Em emulador,
      // `http://10.0.2.2:8080/` alcança o host — e só o build de depuração aceita cleartext
      // (ver `src/debug/res/xml/network_security_config.xml`).
      buildConfigField(
        "String",
        "SPARK_BACKEND_BASE_URL",
        "\"${providers.gradleProperty("sparkBackendBaseUrlDebug").getOrElse("")}\""
      )
    }
  }
  compileOptions {
    // `java.time` em `minSdk 24` (auditoria 2026-09-12 §1.1). Sem isto, `LocalDate`, `Instant` e
    // `DateTimeFormatter` — usados em 14 arquivos, um deles no `onCreate` — lançam
    // `NoClassDefFoundError` em Android 7.0/7.1. O `lintDebug` acusava 217 erros `NewApi`, e o CI
    // não rodava `lint` (só `lintVital`), então nada disso aparecia.
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  // Ver o comentário em `gradle/libs.versions.toml`: declarada para que o `lintVitalRelease` leia
  // a versão que o Gradle já resolvia, destravando `assembleRelease` sem baseline.
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // Sincronização incremental (T16.6). É a **única** coisa agendada no Spark: um trabalho único,
  // com restrição de rede e backoff exponencial, enfileirado quando uma alteração local entra na
  // Outbox. Não existe trabalho periódico, polling nem alarme — e nenhum caminho do app depende
  // dele para funcionar offline.
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.coil.compose)
  implementation(libs.coil.gif)
  implementation(libs.converter.moshi)
  // Firebase (T14 → T16.2). O SDK do Firebase AI Logic saiu com a migração do Coach: quem fala
  // com o Gemini agora é o Spark Backend, e o app não carrega credencial de modelo nenhuma.
  // O plugin `com.google.gms.google-services` continua aplicado e exige `app/google-services.json`
  // (configuração do console, não versionada) — ele é o que sustenta Firebase Auth e App Check.
  implementation(platform(libs.firebase.bom))
  // App Check por variante: Play Integrity em release, provedor de depuração só em debug. Ele
  // atesta o **aplicativo** perante o Firebase e desde a T16.2 é instalado pela fronteira de
  // autenticação. `debugImplementation` é o que garante que o provedor de depuração não entra no
  // APK publicado — a escolha vive em `src/debug` / `src/release` (`SparkAppCheck`).
  implementation(libs.firebase.appcheck.playintegrity)
  debugImplementation(libs.firebase.appcheck.debug)
  // Conta opcional (T16.1): Firebase Authentication + Sign in with Google via Credential Manager.
  // A conta é opcional em runtime — sem ela o núcleo do Spark funciona por completo. Nenhum
  // client ID vive no código: o Web Client ID vem de `google-services.json` (não versionado),
  // pelo recurso `default_web_client_id` gerado pelo plugin do Gradle.
  implementation(libs.firebase.auth)
  // Notificações sociais (T17.5): Firebase Cloud Messaging para alertas de solicitações e desafios.
  // Notificação é mero sinal auxiliar e descartável; a verdade canônica continua sendo o backend.
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  // QR Code (T17.1). O código de amigo vira QR **localmente**: nada é pedido ao servidor, e o
  // conteúdo é só `spark://friend/v1/SPK-XXXXXXXX` — sem uid, sem e-mail, sem token.
  //
  // `zxing:core` é Java puro (o módulo `javase`, que depende de `java.awt`, deliberadamente não
  // entra): ele desenha a matriz, e o Compose pinta. É também o que permite ao teste de JVM
  // provar `código → QR → parser → mesmo código` sem câmera nenhuma.
  implementation(libs.zxing.core)
  // A leitura usa o Google Code Scanner, que roda na UI do Play Services e **não exige permissão
  // de câmera** — o app recebe só o texto lido. Ele fica atrás de `QrScanner`, então nenhum teste
  // carrega classe de Play Services e o app degrada sozinho onde ele não existe.
  implementation(libs.play.services.code.scanner)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  // `moshi-kotlin-codegen` saiu daqui na auditoria de 2026-09-12: o projeto não tem **nenhuma**
  // classe `@JsonClass`, então o processador rodava a cada build sem gerar um adaptador sequer.
  // Quem serializa por Moshi aqui usa `KotlinJsonAdapterFactory` (reflexão), coberta por
  // `proguard-rules.pro`.
}


