plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hawwwran.waketracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hawwwran.waketracker"
        // 29: the first Android that records a shutdown and a startup in its usage log,
        // which is what closes a screen session the phone was switched off in.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            // Android replaces an installed app only by one carrying the same signature, and
            // uninstalling the app deletes its history. So every build handed to a phone is
            // signed with this one key (docs/signing.md). The keystore stays out of git;
            // credentials come from ~/.gradle/gradle.properties. The release script builds
            // in a fresh clone, which has no keystore, and points at the checkout's with
            // -PWT_KEYSTORE_FILE.
            val keystore = (project.findProperty("WT_KEYSTORE_FILE") as? String)?.let { file(it) }
                ?: rootProject.file("keystore.jks")
            if (keystore.exists()) {
                storeFile = keystore
                storePassword = (project.findProperty("WT_KEYSTORE_PASSWORD") as? String) ?: ""
                keyAlias = (project.findProperty("WT_KEY_ALIAS") as? String) ?: "wake-tracker"
                keyPassword = (project.findProperty("WT_KEY_PASSWORD") as? String) ?: ""
            }
        }
    }

    buildTypes {
        debug {
            // Co-signed with the release key when it is present, so a debug build installs
            // over a released one and the other way round. Without it (a fresh clone): the
            // machine's own debug key.
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    // Pairing: zxing draws this phone's code, Google Play services reads the other phone's
    // with its own screen, so the app needs neither the camera permission nor a camera library.
    implementation(libs.zxing.core)
    implementation(libs.play.services.code.scanner)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android has org.json built in; the JVM the unit tests run on does not.
    testImplementation(libs.json)
}
