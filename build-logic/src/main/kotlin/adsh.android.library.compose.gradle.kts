import com.android.build.api.dsl.LibraryExtension

// Compose 库模块的公共约定：在 adsh.android.library 之上打开 Compose 与它的编译器插件。
// BOM / ui / material3 这些依赖归模块自己声明 —— 与 :app 的分工一致。
plugins {
    id("adsh.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

extensions.configure<LibraryExtension> {
    buildFeatures {
        compose = true
    }
    // Compose 的 JVM 单测（Robolectric + createComposeRule）要读合并后的资源与清单：
    // 不开这个，:feature:chat 这类库模块里的 ActivityScenario 找不到宿主 Activity。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
