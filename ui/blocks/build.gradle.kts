// 界面渲染基础（设计系统 + 消息块）：调色板 / 主题 / 间距 / 图标 / 交互常量 +
// Markdown / LaTeX / 代码块 / 工具结果块 / 文件预览。Android library + Compose。
// 只依赖 :seam；不许知道 :core:*（业务）、:feature:chat 或 :app —— 分层门禁盯着。
plugins {
    id("adsh.android.library.compose")
}

android {
    namespace = "com.adsh.app.ui.blocks"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    // 弹层返回键处理（BackHandler）与 LocalActivity：OverlayDismiss 用。
    implementation(libs.androidx.activity.compose)
    // FileProvider：「用其他应用打开」的受控 URI（OpenWithApp.kt）。
    implementation(libs.androidx.core.ktx)
    // ToolArgs.parse 的返回值是 kotlinx Json 类型（工具行渲染的公共口径）。
    api(libs.kotlinx.serialization.json)
    // 公开签名带 seam 的渲染类型（PresentedFile / ToolImage / FileChange / ThemeMode …）。
    api(project(":seam"))

    // JVM 测试底座（与 :app 同口径）：JUnit4 + Robolectric + Compose ui-test + Turbine，全部跑在 JVM 上。
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // createComposeRule 需要宿主 Activity 的清单条目，只挂到 debug 变体
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
