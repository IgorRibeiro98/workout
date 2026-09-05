
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


android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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
  compileSdk = 35

  defaultConfig {
    applicationId = "com.aistudio.workout.v2"
    minSdk = 24
    targetSdk = 35
    versionCode = 2
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug { }
  }
  compileOptions {
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
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.coil.gif)
  implementation(libs.converter.moshi)
  // Coach IA (T14.0): Firebase AI Logic + Gemini Developer API é o único provider do Coach.
  // O plugin `com.google.gms.google-services` NÃO é aplicado ainda porque exige
  // `app/google-services.json`, que depende do console Firebase. Sem esse arquivo o app
  // compila e roda normalmente: o gateway responde `UNAVAILABLE` e o core segue local-first.
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.ai)
  implementation(libs.firebase.appcheck.playintegrity)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.serialization.json)
  // Uncomment to use Firestore:

  // Sign-In via Credential Manager:
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
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
  "ksp"(libs.moshi.kotlin.codegen)
}


