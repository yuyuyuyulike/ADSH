import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 约定插件的宿主构建。kotlin-dsl 提供 src/main/kotlin/*.gradle.kts 的预编译脚本支持。
plugins {
    `kotlin-dsl`
}

group = "com.adsh.buildlogic"

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // 约定脚本里要 apply 这些插件并配置它们的 DSL，所以必须是实现依赖。
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    // 约定插件按 id 挂 Spotless 与 Compose 编译器，两者必须是实现依赖（坐标与版本见 catalog）。
    implementation(libs.spotless.plugin.gradle)
    implementation(libs.compose.compiler.gradle.plugin)
}
