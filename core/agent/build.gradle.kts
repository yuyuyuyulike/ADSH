// Agent 主循环与轮次装配（宿主侧）：Android library —— 公开面把 core.data（含 core.llm / core.session）/
// core.tools（含工作区类型）与 seam.model / seam.tool 的类型带了出去。
plugins {
    id("adsh.android.library")
}

android {
    namespace = "com.adsh.app.core.agent"
}

dependencies {
    // 公开签名带这些类型：AgentLoop 的公开构造与 send()、PromptAssembler.buildParts、mergeTurnFiles。
    api(project(":core:data"))      // TurnStore / TurnSettings / MessageEntity / TurnLlm / ChatEvent
    api(project(":core:tools"))     // ToolContext / Workspace
    api(project(":seam"))           // UserAttachment / FileChange / PresentedFile

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
