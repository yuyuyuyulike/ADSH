import java.util.Properties

plugins {
    // 约定插件自带 AGP、compileSdk/minSdk、JVM 目标、Compose、lint 门禁与 JVM 测试资源开关
    id("adsh.android.application")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 并存版的签名：一把全新的密钥（keystore/adsh-side.jks），与 debug 那把毫无关系。
 * 凭据放在根目录的 keystore.properties（已在 .gitignore 里，不进版本库）；
 * 换一台机器没有这个文件时不注册该构建类型，正常 sync。
 */
val sideProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val hasSideSigning = sideProps.getProperty("sideStoreFile") != null

android {
    namespace = "com.adsh.app"

    defaultConfig {
        // 包名就是 com.termux：官方前缀 /data/data/com.termux/files/usr 真实存在，
        // 官方 .deb 的 shebang 与硬编码路径天然可用（见 docs/ARCHITECTURE.md 硬约束第 1 条）。
        applicationId = "com.termux"
        /**
         * targetSdk **必须钉在 28**：内嵌 Termux 直接 exec 自己数据目录里文件的唯一前提。
         * 29 起 AOSP sepolicy 禁止这种 exec；官方 `system_linker_exec` 绕法会让 `/proc/<pid>/exe` 指向
         * linker64，靠自身路径定位资源的程序（ICU / `uv_exepath` / 静态链接 / 裸 `execve`）启动即崩。
         * 代价（上不了 Play、旧版行为回归）可接受 —— 本项目只走 Release 侧载。
         * 完整约束见 `docs/ARCHITECTURE.md` 的硬约束清单第 1 条。
         */
        targetSdk = 28
        // versionCode 只往前加：手机上的 0.1.3（code 5）不能降级安装
        // （release 不可 debuggable，-d 也不放行），退回就是「卸载重装 = 丢数据」。
        versionCode = 16
        versionName = "0.2.4"
    }

    signingConfigs {
        if (hasSideSigning) {
            create("side") {
                storeFile = rootProject.file(sideProps.getProperty("sideStoreFile"))
                storePassword = sideProps.getProperty("sideStorePassword")
                keyAlias = sideProps.getProperty("sideKeyAlias")
                keyPassword = sideProps.getProperty("sideKeyPassword")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            // release 比 debug 还大就是因为没开这两项：R8 现在会删掉未用代码并改写名字，
            // shrinkResources 再删掉没人引用的资源（两者必须一起开）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 自用构建：release 与 debug 同包名（com.termux）、同一把 debug 签名，
            // 这样可以直接覆盖安装到手机上，会话 / API 密钥 / 工作区都不会丢。
            signingConfig = signingConfigs.getByName("debug")
        }

        /**
         * 并存版：独立签名、**与 debug 同包名**（没有 applicationIdSuffix），所以它不是另一个应用 ——
         * 与 debug 版互斥（同包名不同签名，装不进同一台设备）。用途只有「用另一把密钥出一个
         * 与 debug 等价的包」。
         */
        if (hasSideSigning) {
            create("side") {
                // 与 debug 完全一致（不混淆，且 BuildConfig.DEBUG=true 会打开 LlmClient 的
                // ADSH_LLM 请求 / SSE 日志），只换签名。
                initWith(getByName("debug"))
                versionNameSuffix = "-side"
                signingConfig = signingConfigs.getByName("side")
                isDebuggable = true
                isMinifyEnabled = false
                isShrinkResources = false
                // 依赖的库模块（:seam / :ui:blocks / :feature:chat …）只有 debug / release 两个变体。
                // 少了这一行，AGP 会给 side 变体报「No matching variant」，**打 APK 直接失败**；
                // 而单测 / lint 只碰 debug 变体，看不见它 —— 所以门禁必须包含一次 APK 组装。
                matchingFallbacks += listOf("debug")
            }
        }
    }

    packaging {
        jniLibs {
            // 可执行文件必须解压成真实文件才会落到 nativeLibraryDir
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // execLibs 直接进各变体的 jniLibs（src/main/execLibs/arm64-v8a/lib*.so）。
    // 包名就是 com.termux，官方前缀即真前缀 —— **不要**在构建期改这些 ELF 与脚本里的任何字节
    // （它们从 nativeLibraryDir 执行，改文本会把前缀改成不存在的路径）。
    //
    // 路径只能静态给出：AGP 9 的 SourceSet API 不收 Provider，Variant API 的 addStaticSourceDirectory
    // 又不生效（实测产物没进 APK），所以按变体名写死 —— 变体集合在 buildTypes 里就固定了。
    sourceSets {
        getByName("debug").jniLibs.srcDir(layout.projectDirectory.dir("src/main/execLibs"))
        getByName("release").jniLibs.srcDir(layout.projectDirectory.dir("src/main/execLibs"))
        if (hasSideSigning) {
            getByName("side").jniLibs.srcDir(layout.projectDirectory.dir("src/main/execLibs"))
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.quickjs.android)

    // 抽屉拉开时主内容逐渐变糊（同窗口内 Haze 背景模糊）

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // 能力缝（seam）：定义与实现分属不同模块，边界由编译器守。
    implementation(project(":seam"))

    // 平台层：Termux 进程 / PTY / 原生 shim，实现 :seam 的 Shell 端口。
    implementation(project(":platform:termux"))

    // 界面渲染基础（原 :ui:kit 并入）：Markdown / LaTeX / 块渲染 / 调色板。
    implementation(project(":ui:blocks"))

    // 对话 feature：会话宿主 / 会话屏 / 输入区 / 轮次渲染。
    implementation(project(":feature:chat"))

    // 核心层：agent 循环、工具、LLM、持久化、后台任务。
    implementation(project(":core:tools"))
    implementation(project(":core:data"))
    implementation(project(":core:agent"))

    // JVM 测试底座：JUnit4 跑，Robolectric 提供安卓运行时，Compose ui-test 断言语义树，
    // Turbine 断言 Flow。全部在 JVM 上跑，不需要模拟器。
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // 不挂 androidx.compose.ui:ui-test-manifest：它声明的 androidx.activity.ComponentActivity 带
    // MAIN/LAUNCHER，debug 包会多出第二个桌面图标（实测）。:app 自己没有 Compose 规则单测；
    // :feature:chat 用自己的 debug 清单，它的同名节点由 app/src/debug/AndroidManifest.xml 删掉。
}
