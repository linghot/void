plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.offlineinterp"
    compileSdk = 34

    // whisper.cpp 需要 NDK 编译。这个版本号必须与构建机上实际安装的 NDK 一致；
    // CI 由 workflow 用 sdkmanager 安装，本地用 Android Studio 的 SDK Manager 装。
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.dsh.offlineinterp"
        // AudioPlaybackCapture 从 Android 10 (API 29) 才有，低于此版本无解
        minSdk = 29
        targetSdk = 34
        versionCode = 7
        versionName = "0.7-ui"

        ndk {
            // 只打主流真机架构。要用模拟器就再加上 "x86_64"
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += "-O3"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            // 构建时会用 FetchContent 拉取 whisper.cpp 源码（需要网络）
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
        viewBinding = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // 依然是零第三方依赖：
    // 识别 = CMake 编进来的 whisper.cpp（离线）
    // 翻译 = HttpURLConnection 直连你自己的 API
}
