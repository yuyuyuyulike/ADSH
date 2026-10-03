package com.adsh.app.core.phone

/**
 * `phone` 命令的**线协议**（纯逻辑，能在 JVM 单测里打表；Android 那一侧在
 * [com.adsh.app.runtime.phone.PhoneChannel]）。
 *
 * 为什么要有它：bash 层要能"喊一声 ADSH，让它用**前台身份**做一件我做不到的事"
 * （实测：app UID 下 `am start` / `input` / `screencap` / `settings get` 全被系统拒绝 ——
 * 那些是 shell 专属工具）。通道用**目录里的请求/应答文件**实现，不用 socket：
 * 任何 shell 都能读写文件，没有 nc/socat 依赖，也不怕 ADSH 那边没在读（写文件不会阻塞）。
 *
 * 请求文件（由脚本写、ADSH 读，写完 rename 过去所以不会读到半个）：
 *   第 1 行 = 命令，之后每行一个参数
 * 应答文件（ADSH 写、脚本读）：
 *   第 1 行 = 退出码（0 成功 / 1 业务失败 / 2 用法错），之后是正文
 */
internal sealed interface PhoneRequest {
    /** `open <网址|包名|域名>`：用 ADSH 的前台身份拉起 */
    data class Open(val target: String) : PhoneRequest
    /** `info`：设备与应用的只读状态 */
    data object Info : PhoneRequest
    /** `clip get` / `clip set <文本>`：剪贴板（Android 10+ 读剪贴板要求应用在前台） */
    data object ClipGet : PhoneRequest
    data class ClipSet(val text: String) : PhoneRequest
    /** `help`：用法（**这就是发现入口** —— 不往提示词里加一个字） */
    data object Help : PhoneRequest
}

/** 解析请求文件的内容；命令不认识 / 参数不够 → null（调用方回用法错） */
internal fun parsePhoneRequest(text: String): PhoneRequest? {
    val lines = text.split('\n').map { it.trimEnd('\r') }
    val command = lines.firstOrNull()?.trim().orEmpty()
    val args = lines.drop(1).filter { it.isNotEmpty() }
    return when (command) {
        "" -> null
        "open" -> args.firstOrNull()?.let { PhoneRequest.Open(it) }
        "info" -> PhoneRequest.Info
        "clip" -> when (args.firstOrNull()) {
            "get" -> PhoneRequest.ClipGet
            "set" -> args.drop(1).takeIf { it.isNotEmpty() }?.let { PhoneRequest.ClipSet(it.joinToString("\n")) }
            else -> null
        }
        "help" -> PhoneRequest.Help
        else -> null
    }
}

/** 应答文件的编码（与 `phone` 脚本里的 head/tail 约定一一对应） */
internal fun encodePhoneResponse(code: Int, text: String): String =
    code.toString() + "\n" + text.trimEnd('\n') + "\n"

/** 带 scheme 的直接当网址（其它的先按包名试，再按域名试） */
internal fun looksLikeUrl(target: String): Boolean =
    target.startsWith("http://") || target.startsWith("https://")

/** 看着像域名（含点、没有空白与斜杠）—— 没 scheme 时用它兜底成 https://<target> */
internal fun looksLikeDomain(target: String): Boolean =
    target.contains('.') && target.none { it.isWhitespace() || it == '/' }

/**
 * `phone open` 到底要开什么（**判定提纯**：[com.adsh.app.runtime.phone.PhoneChannel] 只负责把它变成
 * 一个 Intent，不再自己排分支）。
 *
 * 三档优先级：带 scheme → 网址；**包已安装** → 拉起应用；看着像域名 → 补 https:// 再当网址；
 * 都不像 → 报错（文案带上原目标）。中间那档要问 PackageManager，所以用一个惰性探针传进来
 * （[packageInstalled]）：网址那条路一次都不该去查包管理器。
 */
internal sealed interface PhoneOpenTarget {
    data class Url(val url: String) : PhoneOpenTarget
    data class Package(val name: String) : PhoneOpenTarget
    data class Domain(val url: String) : PhoneOpenTarget
    data class Unsupported(val target: String) : PhoneOpenTarget
}

internal fun resolveOpenTarget(target: String, packageInstalled: (String) -> Boolean): PhoneOpenTarget = when {
    looksLikeUrl(target) -> PhoneOpenTarget.Url(target)
    packageInstalled(target) -> PhoneOpenTarget.Package(target)
    looksLikeDomain(target) -> PhoneOpenTarget.Domain("https://" + target)
    else -> PhoneOpenTarget.Unsupported(target)
}

/**
 * `phone info` 的正文（**文案纯化**）：四个事实进、一段文本出，Android 那边只负责取事实。
 *
 * 以前这段写在 channel 里、还带一条提前 return 的分支把前两行抄了一遍 —— 现在只有一处，
 * 而且能在 JVM 单测里打表（网络「无」也照样把屏幕/电量/版本报全）。
 */
internal fun phoneInfoText(
    screenOn: Boolean,
    batteryPercent: Int,
    networkConnected: Boolean,
    versionName: String,
    sdk: Int,
): String = buildString {
    append("屏幕：").append(if (screenOn) "亮" else "灭").append('\n')
    append("电量：").append(batteryPercent).append("%\n")
    append("网络：").append(if (networkConnected) "已连接" else "无").append('\n')
    append("ADSH：").append(versionName).append("（SDK ").append(sdk).append("）")
}

