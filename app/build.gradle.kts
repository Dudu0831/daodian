import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// secrets.properties 不进 git（见 .gitignore）。文件缺失时用空值兜底 ——
// 别人 clone 这个仓库必须能直接构建，不能因为没有 key 就编译失败。
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(key: String, fallback: String = ""): String =
    (secrets.getProperty(key) ?: fallback).replace("\\", "\\\\").replace("\"", "\\\"")

// release 签名。keystore.properties 和签名文件都不进 git（见 .gitignore）；缺了就打不签名的包，不挡构建。
// 签名文件丢了 = 发出去的包再也覆盖不上，内测的人只能卸载重装、丢数据。务必另外备份
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.abc.daodian"
    compileSdk = 35
    // 必须显式钉住：AGP 8.7.3 默认找 build-tools 34.0.0，而它内置的下载器读不懂
    // 新版 cmdline-tools 的 v4 仓库 XML，会以 "Failed to download package" 挂掉。
    // 指到已装的 35.0.0，整个自动下载路径就绕过去了。
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.abc.daodian"
        minSdk = 34          // 见 README「minSdk 从 33 改到 34」。34 才是真正的「零版本分支」边界
        targetSdk = 35
        // 发出去的每个包 versionCode 都要 +1：同号、降号覆盖不上
        versionCode = 2
        versionName = "0.1.0"

        // 本地语音识别（sherpa-onnx）的 native 库四个 ABI 加起来 70MB+，真机只要 arm64 那份
        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "LLM_BASE_URL",  "\"${secret("LLM_BASE_URL")}\"")
        buildConfigField("String", "LLM_API_KEY",   "\"${secret("LLM_API_KEY")}\"")
        buildConfigField("String", "LLM_MODEL",     "\"${secret("LLM_MODEL")}\"")

        // 内测的检查更新（agent/update/Updates.kt）：新包和 latest.json 在官网服务器的 /app/ 下，scripts/publish.sh 传
        buildConfigField("String", "UPDATE_FEED", "\"https://104-168-64-160.sslip.io/app/latest.json\"")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // M2 验包体：openai-java 拖着 Jackson + kotlin-reflect + victools，
            // 必须量 R8 之后的数字，debug 包不裁剪没有参考价值
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // 发给别人的包不带任何人的 key：模型配置留空，装上后在配置页自己填
            buildConfigField("String", "LLM_BASE_URL", "\"\"")
            buildConfigField("String", "LLM_API_KEY",  "\"\"")
            buildConfigField("String", "LLM_MODEL",    "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    androidResources {
        // 识别模型 26MB，native 那边整块读进内存；压着放每次打开都要先解压一遍
        noCompress += "onnx"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.graphics)
    implementation(libs.androidx.compose.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.prefs)

    // 调模型的官方 SDK（OkHttp backend）。包体 +33MB 是接受了的代价，见 DESIGN.md 决策 3.1
    implementation(libs.openai.java)

    // 桌面速记的本地语音识别，见 DESIGN.md 决策 8.3。官方只发 GitHub Releases 的 AAR，没有 Maven 坐标。
    // 用的是 onnxruntime 静态链接那版：arm64 只有一个 24MB 的 .so，不和别的 onnxruntime 撞
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))

    // 对话里模型画的图（```svg），见 DESIGN.md §6.8。只画静态的、不联网，约 200KB
    implementation(libs.androidsvg)

    // harness 的 JVM 单测。harness 核心不碰 Android API，所以不需要 Robolectric
    testImplementation(libs.junit)
}
