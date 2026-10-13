plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shadow.operator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shadow.operator"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0"
        vectorDrawables.useSupportLibrary = true

        // Operator Ed25519 private key for signing RPC commands
        buildConfigField("String", "OPERATOR_PRIV_KEY", "\"${System.getenv("OPERATOR_PRIV_KEY") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("OPERATOR_KEYSTORE_PATH") ?: "../keystore/operator.jks")
            storePassword = System.getenv("OPERATOR_KEYSTORE_PASS") ?: "ShadowOperator2024!"
            keyAlias = System.getenv("OPERATOR_KEY_ALIAS") ?: "shadow_operator"
            keyPassword = System.getenv("OPERATOR_KEY_PASS") ?: "ShadowOperator2024!"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Ed25519 signing that works below API 31 (JCA gained Ed25519 only in
    // Android 12). Lightweight API only -- no JCA provider registration.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Gson (JSON parsing for RPC)
    implementation("com.google.code.gson:gson:2.10.1")
}
