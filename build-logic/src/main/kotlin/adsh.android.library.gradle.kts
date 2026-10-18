import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

// Android 库模块的公共约定：Sdk 档位、JVM 目标、lint 门禁。
// 只放"每个库模块都必须一致"的项；namespace 归模块自己，Compose / BuildConfig / 测试资源
// 留给真正需要它们的模块在 build 文件里开。
plugins {
    id("com.android.library")
    id("adsh.spotless")
}

extensions.configure<LibraryExtension> {
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
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
