package com.adsh.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Public
import androidx.compose.ui.graphics.vector.ImageVector
import com.adsh.app.seam.ptc.SubCall
import com.adsh.app.seam.tool.ToolImage
import com.adsh.app.ui.blocks.DiffRow
import com.adsh.app.ui.blocks.DshIcons
import com.adsh.app.ui.blocks.DshSidebarIcons
import com.adsh.app.ui.blocks.DshToolIcons
import com.adsh.app.ui.blocks.ToolArgs
import com.adsh.app.ui.blocks.diffRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * 工具行的展示模型（dsh 的 ToolRow 模型层）：变体 → 标题/图标、摘要、展开体的输入段与 diff。
 * 全部是参数原文 → 展示值的纯函数，渲染在 TurnRail.kt —— 这一层不认识 Compose。
 */

// ------------------------------------------------------------------ 工具行（dsh 的 ToolRow / ToolCallTree）

/** dsh 的 ToolRow 变体（TOOL_VARIANTS）：决定图标、标题与摘要取哪个参数 */
internal enum class ToolVariant { SEARCH, READ, BASH, WRITE, EDIT, CODE, JOBS, OTHERS }

/** 一行工具调用的展示模型 */
internal data class RailTool(
    val name: String,
    val arguments: String,
    val output: String? = null,
    val running: Boolean = false,
    val error: Boolean = false,
    /**
     * dsh 的 preparing 阶段：模型还在写这次调用的参数，工具还没开始跑。
     * 这一行只有标题 + 已写出来的 description，**不可展开**、没有子调用。
     */
    val preparing: Boolean = false,
    val children: List<SubCall> = emptyList(),
    /** 这次调用产出的图片（read_image）：行下面直接铺开画廊，点开是原图预览 */
    val images: List<com.adsh.app.seam.tool.ToolImage> = emptyList(),
    /**
     * todo_write 用的**上一次清单**（上一次 todo_write 的参数 JSON）：dsh 的会话级
     * todoHistory 投影，由 TurnBuilder 一路带下来（见 ProcessEntry.Call.todoBefore）
     */
    val todoBaseline: String? = null,
)

internal data class ToolSpec(val variant: ToolVariant, val title: String, val icon: ImageVector)

/**
 * 工具名 → 变体 + 标题 + 图标。
 * 标题逐字取自 dsh 的中文字典（tool.title.* / todo.rowTitle / ask.rowTitle），
 * 图标取自 dsh 的 VARIANT_ICONS（代码 = # 形 IconCodeOutline16）。
 */
internal fun specOf(name: String): ToolSpec = when (name) {
    "bash", "pwsh" -> ToolSpec(ToolVariant.BASH, "Bash", DshToolIcons.Api)
    "read" -> ToolSpec(ToolVariant.READ, "读取", DshToolIcons.Browse)
    "read_image" -> ToolSpec(ToolVariant.READ, "读取图片", DshToolIcons.Browse)
    "web_fetch" -> ToolSpec(ToolVariant.READ, "网页获取", DshToolIcons.Browse)
    // dsh 的 web_search 行图标是 IconGlobeOutlineRegular（地球）；ADSH 的 DshToolIcons 是生成物
    // （scripts/dsh-icons2.py，不手改），这里用 Material 的地球轮廓，图形等价
    "web_search" -> ToolSpec(ToolVariant.SEARCH, "网页搜索", Icons.Outlined.Public)
    // 标题逐字取 dsh 的中文字典：tool.title.grep = 搜索文件内容 / tool.title.glob = 查找文件
    "grep" -> ToolSpec(ToolVariant.SEARCH, "搜索文件内容", DshToolIcons.Search)
    "glob" -> ToolSpec(ToolVariant.SEARCH, "查找文件", DshToolIcons.Search)
    "write" -> ToolSpec(ToolVariant.WRITE, "写入", DshSidebarIcons.Edit)
    "edit" -> ToolSpec(ToolVariant.EDIT, "编辑", DshSidebarIcons.Edit)
    "run_code" -> ToolSpec(ToolVariant.CODE, "代码", DshToolIcons.Code)
    // dsh 的工具名是 todo_write（旧会话里可能还留着老的 todo）
    // 图标：dsh 是 IconChecklistOutlineRegular / IconDeliverDocRegular（DshToolIcons 是生成物，不手改）
    "todo_write", "todo" -> ToolSpec(ToolVariant.OTHERS, "更新任务清单", Icons.Outlined.Checklist)
    "ask_user_question" -> ToolSpec(ToolVariant.OTHERS, "提问", DshToolIcons.Sparkle)
    // dsh-client-ui-deliverables 的 row.title =「交付文件」
    "present" -> ToolSpec(ToolVariant.OTHERS, "交付文件", Icons.Outlined.Description)
    // 后台任务三件套（dsh-tool-jobs）：dsh 的 presentCall 把它们都做成 generic 卡 ——
    // 标题点名动作（列出 / 读取 / 停止），**rawInput 只有 job_id**（展开体因此不铺参数 JSON 原文），
    // 图标按 dsh 的 kind 分：job_list / job_output 是 read，job_kill 是 execute。
    // 这里标题用中文（与其余工具行同一套口径）、job_id 走摘要那一格 —— 信息与 dsh 逐条一致。
    "job_list" -> ToolSpec(ToolVariant.JOBS, "列出后台任务", DshToolIcons.Browse)
    "job_output" -> ToolSpec(ToolVariant.JOBS, "读取任务输出", DshToolIcons.Browse)
    "job_kill" -> ToolSpec(ToolVariant.JOBS, "停止后台任务", DshIcons.Stop)
    else -> ToolSpec(ToolVariant.OTHERS, "工具调用", DshToolIcons.Sparkle)
}

/** dsh 的 SUMMARY_KEYS：每个变体优先取哪个参数当摘要 */
private val SUMMARY_KEYS: Map<ToolVariant, List<String>> = mapOf(
    ToolVariant.BASH to listOf("description", "command"),
    ToolVariant.READ to listOf("path", "file_path", "url"),
    ToolVariant.SEARCH to listOf("query", "pattern", "url"),
    ToolVariant.WRITE to listOf("path", "file_path"),
    ToolVariant.EDIT to listOf("path", "file_path"),
    ToolVariant.CODE to listOf("description"),
    // 任务工具的摘要是它操作的那个任务 id（dsh 的 presentCall 传的 rawInput 就是它）
    ToolVariant.JOBS to listOf("job_id"),
    ToolVariant.OTHERS to emptyList(),
)

/** 只有这三种变体的摘要可能是可打开的路径（dsh 的 FILE_PATH_VARIANTS） */
private val FILE_PATH_VARIANTS = setOf(ToolVariant.READ, ToolVariant.WRITE, ToolVariant.EDIT)

/**
 * 展开体里**没有输入段**的工具。两类：
 *
 *  - dsh 的 keyed 行只把结果交给 ToolRow、从不传 rawInput：SearchRow（grep / glob / web_search）、
 *    WebRow（web_fetch）、readFamilyRow（read / read_image）、FileMutationRow（write / edit）。
 *  - todo_write / present：报错时参数 JSON 没有信息量，卡片只留错误输出（与上面同一种形态）。
 *
 * 详情卡因此只有结果一侧：成功时是解析出来的卡片，解析不出来或报错时只剩那段输出。
 */
private val NO_INPUT_BODY_TOOLS = setOf(
    "grep", "glob", "web_search", "web_fetch", "read", "read_image", "write", "edit",
    "todo_write", "todo", "present",
)

internal fun firstLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/** 工具行的摘要：单测从这里进（[summaryOf] 本身是私有的） */
internal fun toolRowSummary(name: String, arguments: String): String = summaryOf(specOf(name), name, arguments)

/** 展开体的输入段：单测从这里进（[inputBodyOf] 本身是私有的） */
internal fun toolRowInputBody(name: String, arguments: String): String? =
    inputBodyOf(specOf(name), name, arguments)

private fun pickString(args: JsonObject?, keys: List<String>): String? = ToolArgs.pick(args, keys)

/**
 * 摘要（dsh 的 deriveSummary + toolRowModel）：
 * others 变体在没有专属标题时把工具名缀在前面（「present · xxx」）。
 *
 * 参数 JSON 可能**是被截断过的老数据**（见 [ToolArgs]）：那时也要能取出 path / command 这些
 * 摘要字段，而不是把一坨 JSON 原文显示在行上 —— 后者正是「同一个工具执行时一个样、执行完
 * 另一个样」的根因。真的什么都取不到时，退回一个**去掉 JSON 语法噪声**的单行短文本。
 */
internal fun summaryOf(spec: ToolSpec, name: String, arguments: String): String {
    val args = ToolArgs.parse(arguments)
    if (spec.variant == ToolVariant.SEARCH) {
        val queries = (args?.get("queries") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotEmpty() }
        if (!queries.isNullOrEmpty()) return queries.joinToString(", ") { firstLine(it) }
    }
    val picked = pickString(args, SUMMARY_KEYS[spec.variant].orEmpty())
    if (picked != null) return firstLine(picked)
    val anyString = ToolArgs.firstString(args)
    if (anyString != null) return firstLine(anyString)
    val base = if (args != null) "" else readableFallback(arguments)
    return if (spec.variant == ToolVariant.OTHERS && name.isNotEmpty()) {
        listOf(name, base).filter { it.isNotEmpty() }.joinToString(" · ")
    } else {
        base
    }
}

/**
 * 连补全解析都失败时的兜底显示：去掉 `{` `}` `"key":` 这类 JSON 噪声，只留能读的第一段。
 * **绝不把原文整段（或它的 JSON 骨架）当摘要**。
 */
private fun readableFallback(arguments: String): String {
    val cleaned = arguments
        .replace(Regex("\"[A-Za-z_][A-Za-z0-9_]*\"\\s*:"), " ")
        .replace(Regex("[{}\\[\\]\"]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.take(120)
}


/**
 * dsh 的 argumentSummary：**参数还在流式生成**时，行右侧显示什么。
 *
 * dsh 只有文件类工具等路径字段**完整**才显示它（半截路径不显示）；其余工具显示 description
 * 参数**已经流出来的那一段**（dsh 的 PartialArguments.text），空串 = 这一行只有标题。
 * 这段文本随参数逐字增长，所以 dsh 的 preparing 行是「右侧内容随后到」，而不是落日志时整段蹦出。
 */
internal fun preparingSummaryOf(name: String, arguments: String): String {
    val spec = specOf(name)
    if (spec.variant in FILE_PATH_VARIANTS) {
        // 完整 JSON 才认路径（dsh 的 args.complete('file_path')）：ToolArgs.parse 是给「库里被截断的
        // 老数据」用的容错解析，会把半截路径也解析出来 —— 这里要的是严格解析，半截就不显示。
        val parsed = completeArgsOf(arguments) ?: return ""
        return pickString(parsed, listOf("path", "file_path"))?.let { firstLine(it) }.orEmpty()
    }
    return firstLine(partialStringValue(arguments, "description").orEmpty())
}

/** 严格 JSON 解析（参数写完了才成功）：只给「完整才认」的判定用，与容错的 [ToolArgs.parse] 分开 */
private fun completeArgsOf(raw: String): JsonObject? =
    runCatching { strictJson.decodeFromString(JsonObject.serializer(), raw) }.getOrNull()

/**
 * 从**还没写完**的 JSON 原文里取出某个字符串键已经写出来的那一段（dsh 的 PartialArguments.text）。
 *
 * 只做展示用：遇到转义就按 JSON 语义还原（换行 / 引号 / \\uXXXX），读到结束引号或原文尽头就停。
 * 键没出现、冒号还没到、值不是字符串时返回 null（这一行先显示标题）。**不抛异常**。
 */
internal fun partialStringValue(raw: String, key: String): String? {
    val marker = "\"" + key + "\""
    val at = raw.indexOf(marker)
    if (at < 0) return null
    var i = at + marker.length
    while (i < raw.length && raw[i].isWhitespace()) i++
    if (i >= raw.length || raw[i] != ':') return null
    i++
    while (i < raw.length && raw[i].isWhitespace()) i++
    if (i >= raw.length || raw[i] != '"') return null
    i++
    val out = StringBuilder()
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\') {
            i++
            if (i >= raw.length) break
            when (val escape = raw[i]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'u' -> {
                    if (i + 4 < raw.length) {
                        val code = raw.substring(i + 1, i + 5).toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            i += 4
                        } else {
                            out.append(escape)
                        }
                    } else {
                        out.append(escape)
                    }
                }
                else -> out.append(escape)
            }
            i++
            continue
        }
        if (c == '"') break
        out.append(c)
        i++
    }
    return out.toString()
}

/**
 * 展开后的「输入」体（dsh 的 formatToolBody）：
 * 代码行取 code 原文，其余是格式化后的 JSON；带路径的文件类工具不铺输入（dsh 的 singleFile）。
 *
 * [NO_INPUT_BODY_TOOLS] 里的工具**一律没有输入段**，详情卡只有结果一侧
 * （成功时是解析出来的卡片，报错时只剩那段红色输出）。
 */
internal fun inputBodyOf(spec: ToolSpec, name: String, arguments: String): String? {
    if (name in NO_INPUT_BODY_TOOLS) return null
    if (arguments.isBlank()) return null
    val args = ToolArgs.parse(arguments) ?: return arguments
    if (spec.variant == ToolVariant.CODE) {
        val code = (args["code"] as? JsonPrimitive)?.contentOrNull
            ?: (args["program"] as? JsonPrimitive)?.contentOrNull
        if (!code.isNullOrEmpty()) return code
    }
    if (spec.variant == ToolVariant.BASH) {
        return pickString(args, listOf("command")) ?: jsonPretty(args)
    }
    // 任务工具（job_output / job_kill）：展开体只铺 job_id 这一行，不铺参数 JSON 原文 ——
    // dsh 的 presentCall 给它们传的 rawInput 就是 job_id（job_list 没有 rawInput ⇒ 没有输入段）
    if (spec.variant == ToolVariant.JOBS) return pickString(args, listOf("job_id"))
    return jsonPretty(args)
}

/** 代码行要渲染的源码（dsh 的 variant === "code" 走 CodeBlock，lang = typescript） */
internal fun codeBodyOf(arguments: String): String? {
    val args = ToolArgs.parse(arguments) ?: return null
    return (args["code"] as? JsonPrimitive)?.contentOrNull
        ?: (args["program"] as? JsonPrimitive)?.contentOrNull
}

/**
 * 文件类工具（read / write / edit）摘要里那条**可打开的路径**（dsh 的 deriveFilePath）：
 * 只认 `path` / `file_path`，绝不认 `url`（web_fetch 也归 read 变体，但它的摘要是网址、不是文件）。
 * 返回 null 时摘要就按普通文字画（不加下划线、不可点）。
 */
internal fun filePathOf(spec: ToolSpec, arguments: String): String? {
    if (spec.variant !in FILE_PATH_VARIANTS) return null
    val args = ToolArgs.parse(arguments) ?: return null
    return pickString(args, listOf("path", "file_path"))?.let { firstLine(it) }?.takeIf { it.isNotEmpty() }
}

/**
 * 「展开之后有没有 diff 可画」的**便宜**判据：只看参数里那几个键在不在，
 * 不真的算 diff（[diffRowsOf] 才是贵的那个 —— 行级 Myers diff，`write` 还要按整篇正文铺行）。
 *
 * 它必须与 [diffRowsOf] 的判据**逐条一致**，否则会出现「徽标说有内容、展开却是空的」：
 * 两者都要求 path 非空，write 要求 content 非 null，edit 要求 old_string / new_string 都在。
 */
internal fun hasDiffArgs(name: String, arguments: String): Boolean {
    if (name != "write" && name != "edit") return false
    val args = ToolArgs.parse(arguments) ?: return false
    if (pickString(args, listOf("path", "file_path"))?.let { firstLine(it) }?.isEmpty() != false) return false
    return when (name) {
        "write" -> (args["content"] as? JsonPrimitive)?.contentOrNull != null
        else -> (args["old_string"] as? JsonPrimitive)?.contentOrNull != null &&
            (args["new_string"] as? JsonPrimitive)?.contentOrNull != null
    }
}

/**
 * write / edit 展开体的 diff 行（dsh 的 diffCardModel 的 **intended diff**：直接从参数推）。
 *
 * ADSH 的工具行只拿得到参数与输出正文（结构化的 before/after 没有单独落库），所以走 dsh 在
 * 「结果没有 diffs 元数据」时的同一条退路 —— 用参数现推：
 *  - write：oldText = null、newText = content（整篇都是新增）；
 *  - edit：oldText = old_string、newText = new_string。
 * 返回 null（不是 write/edit、或参数被截断取不到字段）时退回普通的 ioCard。
 */
internal fun diffRowsOf(name: String, arguments: String): List<DiffRow>? {
    val args = ToolArgs.parse(arguments) ?: return null
    val path = pickString(args, listOf("path", "file_path"))?.let { firstLine(it) }?.takeIf { it.isNotEmpty() }
        ?: return null
    return when (name) {
        "write" -> {
            val content = (args["content"] as? JsonPrimitive)?.contentOrNull ?: return null
            diffRows(path, oldText = null, newText = content)
        }
        "edit" -> {
            val old = (args["old_string"] as? JsonPrimitive)?.contentOrNull ?: return null
            val new = (args["new_string"] as? JsonPrimitive)?.contentOrNull ?: return null
            diffRows(path, oldText = old.ifEmpty { null }, newText = new)
        }
        else -> null
    }
}

/**
 * 参数原文 → 对象（**容错**：库里的老数据可能是被截断的 JSON，见 [ToolArgs]）。
 * 只在 RailToolRow 的 remember 里用一次，解析结果按参数原文缓存。
 */
internal fun parseArgs(raw: String): JsonObject? = ToolArgs.parse(raw)

private val strictJson = Json { ignoreUnknownKeys = true }

private fun jsonPretty(args: JsonObject): String =
    runCatching {
        railJson.encodeToString(JsonObject.serializer(), args)
    }.getOrElse { args.toString() }


// dsh 的 formatToolBody 是 JSON.stringify(parsed, null, 2)：输入段缩进**两个空格**，不是 kotlinx 默认的四个
private val railJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
    prettyPrintIndent = "  "
}
