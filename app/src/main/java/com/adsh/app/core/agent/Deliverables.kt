package com.adsh.app.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * present 声明的一份交付物（dsh-tool-present 的 `PresentedFile`，字段逐字同形）。
 *
 * **为什么单独落库**（dsh 的 `deliverables/presented` 事件，第八十三轮补上）：
 * PTC 里 present 是 run_code 程序内的一个子调用，它的参数只活在子调用轨迹里 ——
 * 而轨迹是有上限的展示件（[com.adsh.app.core.tools.SubCallTrimmer] 只留最近 60 条、参数还要裁剪）。
 * 交付物是**用户要点的东西**，不能寄存在那儿：dsh 专门为它追加一条会话事件
 * （`{ turn, callId, files }`，log-only、不进模型请求），界面按轮把卡片画在轮尾。
 * ADSH 落在工具行上（`messages.deliverablesJson`），形状与用途与 dsh 一致。
 *
 * @param path 工作区相对路径（工作区外是绝对路径）—— 就是 dsh 的 `file.path`
 * @param description 给用户看的一句说明（dsh 的 `file.description`，可空）
 */
@Serializable
data class PresentedFile(
    val path: String,
    val description: String? = null,
)

private val deliverablesJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun encodeDeliverables(list: List<PresentedFile>): String? =
    if (list.isEmpty()) null else runCatching {
        deliverablesJson.encodeToString(ListSerializer(PresentedFile.serializer()), list)
    }.getOrNull()

fun decodeDeliverables(raw: String?): List<PresentedFile> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        deliverablesJson.decodeFromString(ListSerializer(PresentedFile.serializer()), raw)
    }.getOrDefault(emptyList())
}

/**
 * 交付物在卡片里**显示**的路径（dsh 没有这一步：它的列表条目直接画 `file.path`，
 * 而 dsh 的 present 参数本来就写成相对路径）。
 *
 * 用户第 85 轮点名的规则：**保留路径，但前面那段工作区的路径要去掉**（省屏幕）。
 * 手机上的模型很喜欢把路径写成 `/storage/emulated/0/1/App/测试.md`，整段前缀在手机上
 * 占掉大半行，于是这里按工作区根裁一次：
 *  - 路径在工作区内 → 只留 `sub/dir/name.md` 这一截；
 *  - 路径已经是相对的（模型直接写 `测试.md`）→ 原样；
 *  - 路径在工作区外（`/tmp/...`）→ 原样保留（那是它真实的落地位置，不能瞎裁）。
 *
 * @param workspaceRoot 当前会话的工作区根（没有绑定时为 null）
 * @param path present 声明时的原始路径
 */
fun deliverableDisplayPath(workspaceRoot: String?, path: String): String {
    val root = workspaceRoot?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return path
    val trimmed = path.trim()
    if (!trimmed.startsWith(root + "/")) return trimmed
    return trimmed.removePrefix(root + "/").ifEmpty { trimmed }
}

/**
 * 一轮里声明过的交付物：按工具行在轮内的顺序收集，同一个路径只留第一次
 * （dsh 的 `presentedForClosing` 也是「一轮里所有 presented 事件按序铺开」）。
 */
fun collectDeliverables(all: List<List<PresentedFile>>): List<PresentedFile> {
    val seen = HashSet<String>()
    val out = ArrayList<PresentedFile>()
    all.forEach { group ->
        group.forEach { file -> if (seen.add(file.path)) out += file }
    }
    return out
}
