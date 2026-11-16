plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }

val stableSigningPath = System.getenv("LEDSTUDIO_KEYSTORE_PATH")
val stableSigningPassword = System.getenv("LEDSTUDIO_SIGNING_PASSWORD")
val ciVersionCode = System.getenv("LEDSTUDIO_VERSION_CODE")?.toIntOrNull() ?: 1

require(stableSigningPath == null || !stableSigningPassword.isNullOrBlank()) {
    "LEDSTUDIO_SIGNING_PASSWORD is required when LEDSTUDIO_KEYSTORE_PATH is set"
}

android {
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    namespace = "com.ledstudio.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.ledstudio.app"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    signingConfigs {
        if (stableSigningPath != null) {
            create("stable") {
                storeFile = file(stableSigningPath)
                storePassword = stableSigningPassword
                keyAlias = "ledstudio"
                keyPassword = stableSigningPassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        debug {
            if (stableSigningPath != null) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (stableSigningPath != null) signingConfig = signingConfigs.getByName("stable")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("dev.shayxo.liquidglass:liquidglass:1.0.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
