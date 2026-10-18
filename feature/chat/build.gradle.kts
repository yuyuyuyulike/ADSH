// 对话 feature（单 Activity 的主界面）：会话宿主 / 会话屏 / 输入区 / 轮次渲染 / 会话级面板。
// Android library + Compose。依赖 :ui:blocks 与 :core:*；不许知道 :app（组合根）或灵动岛服务实现。
plugins {
    id("adsh.android.library.compose")
}

android {
    namespace = "com.adsh.app.ui.chat"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)

    // 公开面（ChatScreen / ChatViewModel / ChatUiState）带这些模块的类型，消费者是 :app -> api。
    api(project(":ui:blocks"))
    api(project(":seam"))
    api(project(":core:data"))
    api(project(":core:agent"))
    api(project(":core:tools"))

    // ChatViewModel 的公开构造签名带 TermuxRuntime（引导进度 / shell 启动），消费者 :app 自己也依赖它。
    api(project(":platform:termux"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // JVM 测试底座（与 :app 同口径）：JUnit4 + Robolectric + Compose ui-test + Turbine，全部跑在 JVM 上。
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
