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

rootProject.name = "Remote Control"

// Auto-restore debug.keystore for local Android Studio builds if missing
val keystoreFile = file("debug.keystore")
val keystoreB64File = file("debug.keystore.base64")
if (!keystoreFile.exists()) {
  if (keystoreB64File.exists()) {
    try {
      val bytes = java.util.Base64.getDecoder().decode(keystoreB64File.readText().trim())
      keystoreFile.writeBytes(bytes)
    } catch (_: Exception) {
    }
  }
  if (!keystoreFile.exists()) {
    val homeAndroidKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
    if (homeAndroidKeystore.exists()) {
      try {
        homeAndroidKeystore.copyTo(keystoreFile, overwrite = true)
      } catch (_: Exception) {
      }
    }
  }
}

include(":app")
