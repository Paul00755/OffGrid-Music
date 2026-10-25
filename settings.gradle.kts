pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "OffGrid Music"

include(":app")

// Suppress benign KSP2 post-disposal AWT-EventQueue race condition where IntelliJ's
// BinaryFileTypeDecompilers.notifyDecompilerSetChange invokeLater callback executes after
// KSP2 disposes StandaloneAnalysisAPISession (ApplicationManager.getApplication() == null).
val previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
val kspAwtSafeHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
  val msg = throwable.message.orEmpty()
  val isBenignKspAwtPostDisposalRace =
    thread.name.startsWith("AWT-EventQueue") &&
      throwable is NullPointerException &&
      (msg.contains("ksp.com.intellij.openapi.application.Application") ||
        throwable.stackTrace.any { it.className.startsWith("ksp.com.intellij.") })
  if (!isBenignKspAwtPostDisposalRace) {
    if (previousUncaughtHandler != null) {
      previousUncaughtHandler.uncaughtException(thread, throwable)
    } else {
      System.err.print("Exception in thread \"${thread.name}\" ")
      throwable.printStackTrace(System.err)
    }
  }
}
Thread.setDefaultUncaughtExceptionHandler(kspAwtSafeHandler)
