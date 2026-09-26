import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // 方案 A：applicationId 直接就是 com.termux，于是官方前缀 /data/data/com.termux/files/usr
        // 真实存在，官方 .deb 的 shebang 与硬编码路径全部天然可用（详见 docs/NOTES.md）。
        applicationId = "com.termux"
        minSdk = 26
        /**
         * targetSdk **故意钉在 28**（与官方 Termux 一样），这是内嵌 Termux 能做「直接 exec」的唯一前提。
         *
         * 安卓 10 起，`targetSdk >= 29` 的应用不能执行自己数据目录里的文件（AOSP sepolicy
         * "Enforce execve() restrictions for API > 28"）。官方 Termux 的绕法是 termux-exec 的
         * `system_linker_exec`：改成 exec 系统 linker，由它加载目标。这条路的**官方文档化副作用**是
         * `/proc/<pid>/exe` 指向 linker64 而不是真实程序 —— 于是所有「靠自身路径定位资源」的程序
         * （Chromium/Electron 的 ICU、任何 `uv_exepath` 的用法）启动即崩，静态链接的二进制也跑不了，
         * 直接调 `execve()` 的程序同样要打补丁（第 72 轮实测：headless_shell 报
         * `Invalid file descriptor to ICU data received`，strace 显示它去找
         * `/apex/com.android.runtime/bin/icudtl.dat`）。
         *
         * 设备的 seapp_contexts 把 targetSdk 映射成 SELinux 域（`minTargetSdkVersion=28 →
         * untrusted_app_27`），而 termux-exec 的 `is-enabled` 正是按域判断的（它豁免
         * `untrusted_app_25` / `untrusted_app_27`）：钉在 28 就自动什么改写都不做 = stock Termux
         * 的行为，`/proc/self/exe`、静态 PIE、裸 `execve`、脚本 shebang 全部恢复正常。
         *
         * 代价（都是可接受/可验证的）：Google Play 要求 targetSdk 34+，本项目只走 GitHub Release
         * 侧载；Android 15/16 的安装下限是 24，28 仍然装得上；旧 targetSdk 会拿回一批「旧版行为」
         * （legacy 外部存储、后台启动放宽等），对自用侧载应用没有坏处。
         */
        targetSdk = 28
        // versionCode 只往前加：手机上的 0.1.3（code 5）不能降级安装
        // （release 不可 debuggable，-d 也不放行），退回就是「卸载重装 = 丢数据」。
        versionCode = 6
        versionName = "0.1.4"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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
         * 并存版：独立签名 + 与 debug 相同的包名（com.termux）。
         *
         * 注意：这里没有 applicationIdSuffix，所以它**不是**另一个应用 —— 与 debug 版互斥
         * （同包名、不同签名，装不进同一台设备）。方案 A 之前它靠独立包名 + 等长别名与
         * debug 共存，那套机制已经删除（见 docs/NOTES.md 的『平台与环境』一节）；现在它的用途
         * 只剩「用另一把密钥出一个与 debug 等价的包」。
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
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // 可执行文件必须解压成真实文件才会落到 nativeLibraryDir
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 这里以前有一条 pickFirsts "lib/*/libtermux.so"：它是为 terminal-view 那个 AAR
        // 带的 JNI 去重的。那个依赖已经删掉（见第五十四轮），规则随之变成空转，一并去掉。
    }

    lint {
        // targetSdk 钉在 28 是**有意的**（内嵌 Termux 直接 exec 的唯一前提，见 defaultConfig 里
        // 那段注释）。lint 的 ExpiredTargetSdkVersion 默认是 error，会让 lintVitalRelease 直接
        // 失败；这里显式关掉它，其余检查照旧。
        disable += "ExpiredTargetSdkVersion"
    }

    // execLibs 直接进各变体的 jniLibs（src/main/execLibs/arm64-v8a/lib*.so）。
    //
    // 历史上这里有个 patchExecLibs* 任务：包名还不是 com.termux 时，要把 ELF 与脚本里的官方
    // 前缀就地换成同长度的别名（那些文件运行期从 nativeLibraryDir 执行，而 APK 的 lib 目录只读，
    // 只能在构建期改）。方案 A（包名 = com.termux）之后官方前缀**就是**真前缀，一个字节都
    // 不用改，任务与别名算法一起删除（见 docs/NOTES.md 的『平台与环境』一节）。
    //
    // 路径只能在这里静态给出：AGP 9 的 SourceSet API 不收 Provider，Variant API 的
    // addStaticSourceDirectory 又不生效（实测产物没进 APK），所以按变体名写死 ——
    // 变体集合在 buildTypes 里就固定了（debug / release / side）。
    sourceSets {
        getByName("debug").jniLibs.srcDir(layout.projectDirectory.dir("src/main/execLibs"))
        getByName("release").jniLibs.srcDir(layout.projectDirectory.dir("src/main/execLibs"))
        if (hasSideSigning) {
            getByName("side").jniLibs.srcDir(layout.projectDirectory.dir("src/main/execLibs"))
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.quickjs.android)

    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.material3)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
