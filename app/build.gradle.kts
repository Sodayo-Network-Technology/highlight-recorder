plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.highlightrecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.highlightrecorder"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("rc") {
            // release-CANDIDATE signing, credentials ONLY from the environment
            // (or a local gradle.properties) — never committed. The original
            // rc-candidate key was retired after its keystore+passwords were
            // accidentally committed publicly (ac22356); it must not be reused.
            val ks = System.getenv("RC_KEYSTORE_PATH")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("RC_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("RC_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("RC_KEY_PASSWORD") ?: System.getenv("RC_STORE_PASSWORD") ?: ""
            }
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            // isolated-stack integration only: allows http + private hosts
            // (e.g. emulator -> host 10.0.2.2). NEVER enabled in release.
            buildConfigField("boolean", "ALLOW_PRIVATE_BASE_URL", "true")
        }
        create("rc") {
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("rc")
            buildConfigField("boolean", "ALLOW_PRIVATE_BASE_URL", "false")
        }
        release {
            isMinifyEnabled = false
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
        buildConfig = true
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 云端上传:持久任务队列(Room+WorkManager)与 Cloudreve V4 协议客户端(OkHttp)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
