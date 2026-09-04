@file:Suppress("DEPRECATION")

import java.security.MessageDigest
import java.util.Properties

plugins {
  id("com.android.application")
  id("com.google.dagger.hilt.android")
  id("com.google.devtools.ksp")
  id("org.jetbrains.kotlin.plugin.compose")
  kotlin("plugin.serialization")
  id("com.google.gms.google-services")
  id("com.google.firebase.crashlytics")
  id("com.google.firebase.firebase-perf")
}

tasks.register("lintFast") {
  description = ""
  dependsOn("lintDebug")

  doFirst {
    // disable KSP
    tasks.matching { it.name.startsWith("ksp") }.configureEach { enabled = false }
    // disable Kotlin compile
    tasks.matching { it.name.contains("compile", ignoreCase = true) }.configureEach { enabled = false }
  }
}

android {
  namespace = "git.shin.animevsub"
  compileSdk = 37

  val localProperties = Properties()
  val localPropertiesFile = rootProject.file("local.properties")
  if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
  }

  fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
  }

  val devPassword = localProperties.getProperty("PASSWORD_UNLOCK_DEVELOPER") ?: ""
  val hashedDevPassword = sha256(devPassword)

  defaultConfig {
    applicationId = "git.shin.animevsub"
    minSdk = 26
    //noinspection OldTargetApi
    targetSdk = 36
    versionCode = project.property("versionCode").toString().toInt()
    versionName = project.property("versionName").toString()

    buildConfigField(
      "String",
      "SUPABASE_URL",
      "\"${localProperties.getProperty("SUPABASE_URL") ?: ""}\""
    )
    buildConfigField(
      "String",
      "SUPABASE_KEY",
      "\"${localProperties.getProperty("SUPABASE_KEY") ?: ""}\""
    )
    buildConfigField(
      "String",
      "DEV_PWD_HASH",
      "\"$hashedDevPassword\""
    )
    buildConfigField(
      "String",
      "RELEASE_CERT_SHA256",
      "\"${localProperties.getProperty("RELEASE_CERT_SHA256") ?: ""}\""
    )

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables {
      useSupportLibrary = true
    }
  }

  buildTypes {
    debug {
      applicationIdSuffix = ".dev"
      versionNameSuffix = "-dev"
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
      excludes += "/META-INF/DEPENDENCIES"
    }
  }
}

dependencies {
  // Compose BOM
  val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
  implementation(composeBom)

  // ... (rest of the dependencies remain unchanged)
}