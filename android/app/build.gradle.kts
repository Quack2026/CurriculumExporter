plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.quack.curriculumexporter"
    compileSdk = 35
    // 钉住 build-tools：不写的话 AGP 会去下它默认的 34.0.0（本机该包曾下载损坏导致构建失败）
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.quack.curriculumexporter"
        // 26 是刻意的：java.util.Base64 从 API 26 起可用，密码加密逻辑因此能被纯 JVM 单测覆盖
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // 用 debug 签名，这样 release APK 也能直接侧载安装
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = false
    }
}

dependencies {
    // 零第三方运行时依赖：网络走系统 HttpURLConnection，JSON 走系统 org.json，加密走 javax.crypto
    testImplementation("junit:junit:4.13.2")
    // Android 自带的 org.json 在 JVM 单测里只是 stub，测试时换成真实实现
    testImplementation("org.json:json:20240303")
}
