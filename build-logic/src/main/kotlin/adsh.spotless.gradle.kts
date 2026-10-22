import com.diffplug.spotless.LineEnding

// 全仓库唯一一份格式规则：行尾空白 + 恰好一个末尾换行 + 钉死 LF（门禁 ./gradlew spotlessCheck，修复 spotlessApply）。
// 两个 Android 约定插件各自 apply 它，模块里不再各抄一份 —— 一个规则只有一个家。
// 不启用 ktfmt / ktlint 的 Kotlin 重排：实测（2026-10，254 个文件）它会给中文注释插 ASCII 空格、
// 给 when 体多缩进一层、把链式接收者拆行，产生 21k 行翻搅而多处比手写更差。
plugins {
    id("com.diffplug.spotless")
}

spotless {
    format("misc") {
        target("src/**/*.kt", "src/**/*.kts")
        trimTrailingWhitespace()
        endWithNewline()
        // Spotless 默认按平台换行（Windows = CRLF），而仓库里是 LF（git core.autocrlf=input）：
        // 不钉死 UNIX 的话每个文件都被判违规、门禁永远红。
        setLineEndings(LineEnding.UNIX)
    }
}
