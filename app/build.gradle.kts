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
        versionName = "1.4"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Private release key: signing/maira-release.jks is decrypted from the committed
    // signing/maira-release.jks.enc using MAIRA_SIGNING_PASS (GitHub Actions secret).
    // The decrypted .jks and the password are never committed. No key = build fails (never ship a wrong-key APK).
    val signPass: String? = System.getenv("MAIRA_SIGNING_PASS") ?: (project.findProperty("mairaSigningPass") as String?)
    val keyFile = rootProject.file("signing/maira-release.jks")
    signingConfigs {
        create("maira") {
            storeFile = keyFile
            storePassword = signPass
            keyAlias = "maira"
            keyPassword = signPass
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("maira") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("maira") }
    }
    if (!keyFile.exists() || signPass.isNullOrBlank()) {
        gradle.taskGraph.whenReady {
            if (allTasks.any { it.name.startsWith("package") || it.name.startsWith("assemble") })
                throw GradleException("Maira signing key missing: decrypt signing/maira-release.jks.enc with MAIRA_SIGNING_PASS first.")
        }
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")   // Maira Brain (own model)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
