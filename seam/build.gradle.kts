// 服务定义：五个能力缝（llm / exec / model / tool / ptc）的契约与数据类型。
// Android library：model 用 BitmapFactory / ExifInterface，tool 用 Bitmap / Base64，
// ptc 的端口签名带 android.content.Context —— 这三处让整块只能是 Android library。
plugins {
    id("adsh.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.adsh.app.seam"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
