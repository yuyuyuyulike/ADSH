// 工具运行时（Tool / ToolContext / ToolResult + 各工具实现 + 工作区管理）：Android library
// （ToolContext.appContext 是 android.content.Context，工具带文件 IO 与跨进程 PTC）。
// 公开签名带 seam.tool / seam.ptc / seam.exec / core.workspace 的类型，这四条走 api。
// core.workspace 的 WorkspaceManager 与 ToolContext.workspace 是同一份契约面（89 行、只有 :app 构造它），
// 并进本模块而不是继续单列一个迷你工程。
plugins {
    id("adsh.android.library")
}

android {
    namespace = "com.adsh.app.core.tools"
}

dependencies {
    // ToolResult.Ok 的 images / deliverables、ToolContext 的 runtime / ptcRunner 都带 seam 类型。
    api(project(":seam"))
    implementation(project(":core:data"))
    // Tool.execute(args: JsonObject) / ToolResult.Ok.value 暴露 kotlinx 的 Json 类型；
    // TodoStore.flow 暴露 StateFlow —— 两条都要让消费者看得见。
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
