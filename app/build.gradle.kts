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
        versionCode = 1
        versionName = "0.1-M1"

        buildConfigField("String", "LLM_BASE_URL",  "\"${secret("LLM_BASE_URL")}\"")
        buildConfigField("String", "LLM_API_KEY",   "\"${secret("LLM_API_KEY")}\"")
        buildConfigField("String", "LLM_MODEL",     "\"${secret("LLM_MODEL")}\"")
        buildConfigField("String", "LLM_API_STYLE", "\"${secret("LLM_API_STYLE", "CHAT_COMPLETIONS")}\"")
        buildConfigField("String", "LLM_JSON_MODE", "\"${secret("LLM_JSON_MODE", "JSON_OBJECT")}\"")
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
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

    // M2 待验：Android 可用性 + 包体增量，见 DESIGN.md 决策 3.1
    implementation(libs.openai.java)
}
