plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.claudegate.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "tw.claudegate.app"
        minSdk = 26          // Anthropic Java SDK 用到 java.time，API 26 起才有
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        // Jackson / Kotlin / jsonschema 的 jar 都帶了同名的 META-INF 檔
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/versions/**", "META-INF/INDEX.LIST",
        )
    }
}

dependencies {
    // 刻意不用 AndroidX：畫面全部用框架元件在程式裡建，相依只有下面這幾個
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Claude API 官方 SDK（內含 OkHttp）
    implementation("com.anthropic:anthropic-java:2.65.0")
    // Gemini 用 REST 呼叫（Claude SDK 本來就帶了 OkHttp，這裡明確宣告）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
