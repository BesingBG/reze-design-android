import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rezedesign.android"

    // 只使用本机已安装的 SDK 组件（android-35 / build-tools 35.0.0），
    // 避免 AGP 自动向共享 SDK 目录下载或修改组件而影响其它工程。
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.rezedesign.android"

        // minSdk 26：本方案不打包任何 native 库，真正的运行门槛是
        // 「系统 WebView >= 121 且设备有 Vulkan 1.1」，由运行时门禁负责判定。
        minSdk = 26
        targetSdk = 35

        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // 上游产物包含 .vmd / .pmx 等自定义扩展名，以及字体与 wasm，
        // 关闭压缩以便 WebView 直接从 assets 读取。
        noCompress += listOf("wasm", "vmd", "pmx", "pmd", "vpd")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.webkit)
}