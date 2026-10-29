plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI: every GitHub Actions build gets a higher versionCode (100 + run number) so installed apps can auto-update.
val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val mairaVersionCode = (project.findProperty("mairaVersionCode") as String?)?.toIntOrNull() ?: ciRun?.let { 100 + it } ?: 3

android {
    namespace = "com.maira.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maira.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = mairaVersionCode
        versionName = "1.2"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // One fixed signing key (committed) so local and CI builds can update each other in place.
    signingConfigs {
        create("maira") {
            storeFile = rootProject.file("signing/maira.jks")
            storePassword = "maira-assistant"
            keyAlias = "maira"
            keyPassword = "maira-assistant"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("maira") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("maira") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false; buildConfig = true }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES") }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
