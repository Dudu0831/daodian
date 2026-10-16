plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 跑 agent 的那一层（DESIGN.md §2.2、§6.1）：纯 Kotlin，不认识 Android、不认识 app 里的任何包。
// 单独一个 Gradle 模块，这条由编译器管：这里没有 android.jar，也看不见 :app。
// 要用 app 里的东西（模型配置存在哪、记忆库、trace 写到哪），由 app 实现它的接口装进来（AgentRuntime.install）。

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        // 和 :app 一样产 17 的字节码，D8 吃得下
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 对外的接口里到处是 Flow 和挂起函数
    api(libs.kotlinx.coroutines.core)

    // 调模型的官方 SDK（OkHttp backend），只在 model/ 里用，不往外露。包体 +33MB 是接受了的代价，见 DESIGN.md 决策 3.1
    implementation(libs.openai.java)
    implementation(libs.jackson.databind)

    testImplementation(libs.junit)
}
