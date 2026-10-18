// ADSH 根构建脚本：插件版本集中在 gradle/libs.versions.toml
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Spotless 的 SpotlessTaskService 是共享 build service：约定插件按项目 classloader 加载时，
    // 同一个类在兄弟项目里是两份、服务对不上。在根脚本 apply false 一次，插件类进根 classpath，
    // 所有子项目共享同一份（与上面 AGP / kotlin.compose 同理）。
    alias(libs.plugins.spotless) apply false
}
