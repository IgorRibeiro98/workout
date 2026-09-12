# Regras de R8 do Spark.
#
# O R8 foi ligado na auditoria de 2026-09-12 (`isMinifyEnabled`/`isShrinkResources` em
# `app/build.gradle.kts`). Até então este arquivo era o template do Android Studio e nada aqui
# importava — o que mudou é que agora **tudo** que o R8 não consegue enxergar estaticamente precisa
# estar declarado neste arquivo, ou a quebra aparece só no APK de release, nunca em debug e nunca
# nos testes.
#
# As bibliotecas que trazem as próprias regras (`consumer-rules.pro`) não são repetidas aqui: Room,
# Retrofit, OkHttp, kotlinx.serialization, Coil, Firebase e Play Services já declaram o que
# precisam. O que sobra são os três casos em que **este** código fala por nome.

# --------------------------------------------------------------------------------------------
# 1. Moshi por reflexão (`KotlinJsonAdapterFactory`)
# --------------------------------------------------------------------------------------------
#
# Quatro pontos do app criam adaptadores por reflexão em vez de codegen: `ExportEngine`,
# `ExerciseDbCatalogCache`, `ExerciseRemoteDataSource` e `ExerciseApiV2Provider`. Reflexão significa
# que o nome de cada propriedade Kotlin **é** o nome do campo no JSON: se o R8 renomear, a
# exportação de dados do usuário sai com chaves ilegíveis e o catálogo do ExerciseDB deixa de casar.
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# O `ExportEngine` serializa `getAllCompletedSessionsWithDetails()` — entidades e relações do Room —
# com um adaptador de `List` cru. Não há classe declarada em lugar nenhum para o R8 seguir: o
# conteúdo é descoberto em runtime. Manter as entidades e DTOs intactos é o que faz o arquivo
# exportado continuar legível.
-keep class com.example.data.local.** { *; }
-keep class com.example.data.remote.** { *; }

# --------------------------------------------------------------------------------------------
# 2. Enums cujo **nome** é dado persistido
# --------------------------------------------------------------------------------------------
#
# Status de sessão, tipo de entidade de sync, estado da Outbox, tipo de PR: todos são gravados no
# Room e no payload do backend como texto, e lidos de volta com `valueOf`. As regras padrão do
# `proguard-android-optimize.txt` preservam `values()`/`valueOf()`, mas não garantem os nomes das
# constantes — e um nome renomeado corrompe banco e protocolo de uma vez.
-keepclassmembers enum com.example.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --------------------------------------------------------------------------------------------
# 3. Recursos resolvidos por nome
# --------------------------------------------------------------------------------------------
#
# `GoogleServerClientId` procura `default_web_client_id` por `getIdentifier`, e não por `R.string`,
# porque o recurso só existe quando o provedor Google está habilitado no console (ver
# `docs/FIREBASE_AUTH_SETUP.md`). O encolhimento de recursos não enxerga essa referência: a regra
# que o preserva está em `res/raw/keep.xml`, porque é lá que o AGP a lê.

# --------------------------------------------------------------------------------------------
# Diagnóstico
# --------------------------------------------------------------------------------------------
#
# Sem estas duas linhas, um stack trace de produção vem sem número de linha e sem nome de arquivo.
# `-renamesourcefileattribute` mantém o ganho de tamanho: o nome do arquivo vira uma constante só.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
