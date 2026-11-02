/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) {
        file.inputStream().use { load(it) }
      }
    }

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 36

  buildFeatures { buildConfig = true }

  defaultConfig {
    // Beacon companion-app identity. The Kotlin namespace stays the DAT
    // sample's (so the sample's DAT integration is untouched); only the
    // installed package + launcher label are Beacon's.
    //
    // CROSS-TRACK ALIGNMENT (Oct 30, coordinator-fixed, NOT optional):
    // Play Services routes Data Layer messages ONLY between apps whose
    // package name AND signing certificate both match across devices.
    // The watch app is us.ninjatek.beacon, so this phone app MUST also
    // be us.ninjatek.beacon (the old us.ninjatek.beacon.glasses package
    // is retired — it can never receive /beacon/one_tap or deliver
    // /beacon/demo_answer) and MUST be signed with the watch app's cert
    // (see the beacon signing config below). Consequence, stated plainly:
    // this is a NEW package, so it installs as a FRESH app, not over
    // 1.1-trigger — the old .glasses app stays installed until Alan
    // uninstalls it, and its SharedPreferences (incl. any custom backend
    // URL) do NOT carry over; first launch must re-check the backend URL.
    applicationId = "us.ninjatek.beacon"
    minSdk = 31
    targetSdk = 36
    versionCode = 4
    versionName = "1.2.1-crashfix"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Meta Wearables Device Access Toolkit Setup
    // Without Developer Mode, these values need to be set with credentials from the app registered
    // in Wearables Developer Center. With Developer Mode ON in the Meta AI
    // app, manifest credentials are NOT required; the application id below
    // is the Wearables Developer Center project "Beacon" id (an id, not a
    // secret) and the client token stays empty. NEVER put a token/key here.
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "2194479907777415")
    manifestPlaceholders["mwdat_client_token"] =
        localProperties.getProperty("mwdat_client_token", "")

    // Default home-box backend URL. Overridable at runtime in the Ask sheet
    // (persisted in SharedPreferences) and at build time via
    // beacon_backend_url in local.properties. No key of any kind lives here.
    buildConfigField(
        "String",
        "BEACON_BACKEND_URL",
        "\"" +
            localProperties.getProperty(
                "beacon_backend_url", "http://100.99.129.53:8901") + "\"",
    )
  }

  // Beacon signing (Oct 30, cross-track alignment): the debug build must
  // be signed with the WATCH app's key (the v2.3 debug keystore), or Play
  // Services will not deliver Data Layer messages between phone and
  // watch even with matching package names. Keystore path, alias, and
  // passwords come ONLY from local.properties on the build box (never
  // committed, never in this file); without them the debug build falls
  // back to the default debug key — which does NOT match the watch, so
  // such a build must NOT be shipped as the aligned build.
  val beaconKeystorePath = localProperties.getProperty("beacon_keystore_path")
  if (beaconKeystorePath != null) {
    signingConfigs {
      create("beacon") {
        storeFile = file(beaconKeystorePath)
        storePassword = localProperties.getProperty("beacon_keystore_pass", "")
        keyAlias = localProperties.getProperty("beacon_key_alias", "beacon")
        keyPassword = localProperties.getProperty("beacon_key_pass", "")
      }
    }
  }

  buildTypes {
    debug {
      if (beaconKeystorePath != null) {
        signingConfig = signingConfigs.getByName("beacon")
      }
    }
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)
  // Watch relay: Data Layer send to Beacon Wear v2.4 (/beacon/demo_answer).
  implementation("com.google.android.gms:play-services-wearable:18.2.0")
  // Beacon backend-client unit tests only (pure JSON helpers, no network).
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
}
