import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

// Android 应用模块的公共约定：Sdk 档位、JVM 目标、Compose、lint 门禁、JVM 测试资源。
// 只放"每个应用模块都必须一致"的项；applicationId / versionCode / 签名 / abiFilters 归模块自己。
plugins {
    id("com.android.application")
    id("adsh.spotless")
}

// kotlin 扩展由 AGP 的内建 Kotlin 支持注册，不是本脚本 plugins 块里的插件，所以按类型显式取。
extensions.configure<ApplicationExtension> {
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Robolectric 要读合并后的 AndroidManifest / 资源，只有打开这项才会生成测试用的那一份。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // targetSdk 钉在 28 是有意的：内嵌 Termux 直接 exec 自己数据目录里文件的前提
        // （docs/ARCHITECTURE.md 硬约束 1）。lint 默认把它判成 error，会让 :app:lint 与
        // lintVitalRelease 都失败，这里显式关掉；其余检查照旧、且保留 error 即失败。
        disable += "ExpiredTargetSdkVersion"
        // 按项豁免（含理由）写在 gradle/lint.xml：只收有意为之的平台事实，别把告警一股脑静音。
        lintConfig = rootProject.file("gradle/lint.xml")
        abortOnError = true
        warningsAsErrors = false
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
