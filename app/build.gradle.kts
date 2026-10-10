plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.offlineinterp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.offlineinterp"
        // AudioPlaybackCapture 从 Android 10 (API 29) 才有，低于此版本无解
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-m0"
    }

    buildTypes {
        release {
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

    // 骨架阶段不启任何可选特性，尽量降低构建门槛
    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    // 刻意保持零依赖：只用平台 API（Activity / Notification / HttpURLConnection）
    // 接入 ASR 原生库时，在这里加：
    //   implementation(files("libs/sherpa-onnx.aar"))
    // 或接入 AndroidX 时再加（并同步把 gradle.properties 的 useAndroidX 打开）
}
