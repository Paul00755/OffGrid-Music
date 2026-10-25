import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.File
import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

// Ensure the canonical debug.keystore always exists and is identical across local & CI builds
val persistentDebugKeystoreFile = File(rootDir, "debug.keystore")
val persistentDebugKeystoreBase64File = File(rootDir, "debug.keystore.base64")
val embeddedDebugKeystoreBase64 =
  "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"

if (!persistentDebugKeystoreFile.exists() || persistentDebugKeystoreFile.length() == 0L) {
  val b64Content = if (persistentDebugKeystoreBase64File.exists()) {
    persistentDebugKeystoreBase64File.readText().replace("\\s".toRegex(), "")
  } else {
    embeddedDebugKeystoreBase64
  }
  persistentDebugKeystoreFile.writeBytes(Base64.getDecoder().decode(b64Content))
}

val ciVersionSuffix = (project.findProperty("versionNameSuffix") as? String)?.trim().orEmpty()

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    // Keep a single fixed applicationId so updates always install in-place over the existing app
    applicationId = "com.aistudio.offgridmusic.player"
    minSdk = 24
    targetSdk = 35
    // Keep versionCode constant across builds so Android allows both upgrading to newer builds
    // AND installing older GitHub Release APKs without INSTALL_FAILED_VERSION_DOWNGRADE
    versionCode = 5
    versionName = "1.0.4$ciVersionSuffix"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("debugConfig") {
      storeFile = persistentDebugKeystoreFile
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH")
      val releaseKeystore = if (!keystorePath.isNullOrBlank()) file(keystorePath) else file("${rootDir}/my-upload-key.jks")
      if (releaseKeystore.exists()) {
        storeFile = releaseKeystore
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      } else {
        // Fallback to the persistent debug keystore so release builds also share the same signature
        storeFile = persistentDebugKeystoreFile
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  packaging {
    jniLibs {
      // Do NOT compress native .so libraries: allows Android PackageInstaller to mmap them directly
      // without stalling mid-installation to extract native libraries to disk
      useLegacyPackaging = false
    }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/DEPENDENCIES"
      excludes += "/META-INF/LICENSE*"
      excludes += "/META-INF/NOTICE*"
      excludes += "/META-INF/*.kotlin_module"
    }
  }
}

base {
  archivesName.set("OffGrid Music")
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))

  implementation("com.google.firebase:firebase-analytics")
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
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.coil.compose)

  // Media3 for Audio Playback & MediaSession
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.common)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.firebase.firestore)

  // Firebase Auth and Google Sign-In via Credential Manager
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
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
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(platform(libs.firebase.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}