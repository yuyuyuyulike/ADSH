// 平台实现：Termux 进程 / PTY / 原生 shim / bootstrap 安装（:seam 的 Shell 端口提供者）+
// PTC 程序宿主（:ptc 独立进程 / QuickJS 引擎 / wire 协议，:seam 的 PtcRunner 端口提供者）。
// 只有这里知道 Termux 的目录布局、原生库与 QuickJS；它只认 :seam 的端口类型，不知道 core / ui / 业务。
plugins {
    id("adsh.android.library")
}

android {
    namespace = "com.adsh.app.platform.termux"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // libadshfence.so（LD_PRELOAD 写围栏）与 libadshpty.so（终端 PTY 桥）都由这里产出；
    // 打包与应用清单里的 useLegacyPackaging 归 :app（APK 级配置）。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // TermuxRuntime 实现 :seam 的 Shell、PtcProcess 实现 :seam 的 PtcRunner，公开签名带它们
    // （PtySession / ShellLaunch / PtcProcess 由 :app 消费）。
    api(project(":seam"))
    implementation(libs.quickjs.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
