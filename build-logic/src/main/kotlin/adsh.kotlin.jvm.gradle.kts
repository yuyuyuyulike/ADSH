import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 纯 Kotlin JVM 模块（:seam:* / :core:* 这类没有安卓依赖的模块）的公共约定：
// JVM 目标统一 17，模块自己在 dependencies 里声明依赖。
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("adsh.spotless")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

extensions.configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
