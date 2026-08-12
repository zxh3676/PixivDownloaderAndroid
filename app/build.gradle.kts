plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pixiv.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pixiv.downloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations.addAll(listOf("zh", "en"))
    }

    signingConfigs {
        create("release") {
            val ksPath: String? = (findProperty("keystorePath") as String?) ?: System.getenv("KEYSTORE_PATH")
            val ksPassword: String? = (findProperty("keystorePassword") as String?) ?: System.getenv("KEYSTORE_PASSWORD")
            val kAlias: String? = (findProperty("keyAlias") as String?) ?: System.getenv("KEY_ALIAS")
            val kPassword: String? = (findProperty("keyPassword") as String?) ?: System.getenv("KEY_PASSWORD")
            if (ksPath != null) storeFile = file(ksPath)
            if (ksPassword != null) storePassword = ksPassword
            if (kAlias != null) keyAlias = kAlias
            if (kPassword != null) keyPassword = kPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Lifecycle ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    // WebKit (for WebView)
    implementation("androidx.webkit:webkit:1.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
