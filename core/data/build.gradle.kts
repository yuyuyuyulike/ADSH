// 持久化与会话仓储（Room/KSP + 设置 + 标题/搜索/恢复规则）+ LLM 客户端与模型层（core.llm）+
// 会话事件日志（core.session）：Android library。core.llm 原本单列一个 1.3k 行的迷你工程，
// 它的公开面（LlmClient / TurnLlm / ChatEvent）只被 :core:agent 与 :feature:chat 经 :core:data 看见 —— 并进来。
plugins {
    id("adsh.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.adsh.app.core.data"
}

// 导出的 schema 跟着模块走（数据库类 FQN 不变，schema 身份不变）；目录见 core/data/schemas。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":seam"))
    // AdshDatabase 是公开类、RoomDatabase 是它的父类型 —— 消费者要看得见，所以是 api。
    api(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
