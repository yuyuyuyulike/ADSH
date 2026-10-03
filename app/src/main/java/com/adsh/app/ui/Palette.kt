package com.adsh.app.ui

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 触发菜单（dsh 的 ui-input-trigger）的**模型与规则**。
 *
 * dsh 把这个流水线拆成两层：`src/core/` 是纯内核（触发词检测、候选归约、精确匹配，零 React/DOM），
 * `src/client/` 才接菜单快照与候选拉取。这里照抄这个分层：UI 在 Composer.kt 的 CommandPalette，
 * 规则全在本文件里 —— 它们只认字符串与一个小接口，所以能在 JVM 单测里逐条钉死
 * （见 app/src/test/java/com/adsh/app/ui/PaletteTest.kt）。
 *
 * dsh 的路径都相对 `packages/client/`。
 */

/** dsh 的 command.section.add：菜单第一段（文件、目标、计划、反馈…） */
const val PALETTE_SECTION_ADD = "添加"

/** dsh 的 command.section.commands：菜单第二段（压缩、权限、模型、下载日志…） */
const val PALETTE_SECTION_COMMANDS = "指令"

/**
 * 触发菜单一行要回答的问题。
 *
 * 抽出这个接口是为了让规则层不认识 Compose：菜单行的 UI（图标、点击回调）在 [PaletteCommand] 里，
 * 而 `candidates()` 真正用到的只有「叫什么名字」和「能不能和已有草稿共存」这两件事
 * （dsh 的 `InputTriggerCandidate` 同理：`name` / `label` 用于排序匹配，`hint` 用于 position 过滤）。
 */
interface PaletteEntry {
    /** 命令名（dsh 的候选 name，如 plan / compact…）：键入 `/` 后按它匹配，也是落库的写法 */
    val name: String

    /** 本地化标题（dsh 的 label.*）：与 [name] 不同时作为别名跟在标题后面显示 */
    val label: String

    /** 草稿里已经有文字时，这条命令还能不能用（见 [paletteUsableWith]） */
    val usableWithDraft: Boolean
}

/**
 * 触发菜单的一行（dsh 的触发候选项：`+` 与键入 `/` 打开的是**同一个菜单**）。
 *
 * 逐项对齐 dsh 的 candidate：
 *  - [name] 是命令名（compact / plan…），本地化标题与它不同时作为**别名**跟在标题后面；
 *  - [label] 是本地化标题（dsh 的 command 字典 label.*）；
 *  - [description] 右对齐显示（dsh 的 .itemDescription）；
 *  - [section] 决定它落在「添加」还是「指令」小节（dsh 的 SECTION_ROWS 顺序）。
 */
data class PaletteCommand(
    override val name: String,
    override val label: String,
    val description: String,
    val icon: ImageVector,
    val section: String,
    override val usableWithDraft: Boolean = true,
    val run: () -> Unit,
) : PaletteEntry

/**
 * 活着的斜杠触发词后面的查询（dsh 的 detectTrigger，`src/core/detect.ts:48-75`）。
 *
 * dsh 从光标往回扫，**碰到空白就返回 null**（`detect.ts:63`）——也就是说
 * `/plan 写一个页面` 里的 `/plan` 已经不是触发词，菜单不该再挂在那儿。
 * ADSH 只认行首的 `/`（菜单的候选都是「整条草稿」级的动作），所以规则简化成：
 * 去掉开头那个 `/` 之后只要还有空白，就没有活触发词。
 * `//` 那条 URL 例外也照抄：dsh 的 boundaryOk 里「紧跟在另一个 `/` 后面的斜杠」不算触发词
 * （`detect.ts:26-28`，注释写明它被测试钉死）。
 *
 * @return null = 没有活触发词；`""` = 刚键入 `/`（空查询，列出全部行）
 */
fun slashQueryOf(draft: String): String? {
    if (!draft.startsWith("/") || draft.startsWith("//")) return null
    val rest = draft.substring(1)
    return if (rest.any { it.isWhitespace() }) null else rest
}

/**
 * 查询是否已经**精确命中**一条命令 —— 命令行已经完整，菜单该让位了（用户第 80 轮的要求）。
 *
 * dsh 判「这条命令算数了」用的就是精确匹配：`resolveCommand` 只认
 * `descriptor.name === token`（`ui-commands/src/client/resolution.ts:50-58`，另有本地化写法的别名表），
 * 空格与回车都靠它裁决（`matchSpace` / `matchEnter`）。ADSH 把「打全了」这件事也用在菜单可见性上：
 * 打全命令名之后直接回车就能执行（见 ChatScreen 的发送分支），菜单再挡着只会挡住输入框。
 *
 * 只认**命令名**（`/plan`、`/compact`…，菜单里就显示在标题旁边当别名），不认中文标题：
 * 标题被当成完整命令的话，回车得跟着认识「/计划」才行，而 ADSH 的发送分支只认命令名
 * （dsh 靠 resolution.ts 的别名表两头都认；ADSH 没有别名表，就不给自己挖这个坑）。
 */
fun paletteQueryComplete(query: String, commands: List<PaletteEntry>): Boolean =
    query.isNotEmpty() && commands.any { it.name.equals(query, ignoreCase = true) }

/**
 * 这条命令能不能和当前草稿共存（dsh 的 `candidates()` position 过滤）。
 *
 * `ui-commands/src/client/service.ts:247`：
 * `const visible = rows.filter(c => req.position === 'leading' || c.hint === undefined)`
 * —— 触发词不在行首（也就是输入框里已经有别的内容）时，**带 hint 的行**（要占用输入框的
 * leadingInput 命令，只有它带 hint，如 /plan、/goal）整条不列。ADSH 的「计划」正是这一类：
 * 它把 `/plan ` 写进草稿，会把用户已经打的字顶掉。
 *
 * 「权限」也跟着不列：它是模式切换（改的是沙箱与审批策略，不是这条草稿），
 * 输入框左下角本来就有常驻的权限入口（dsh 同样是 `conversation.input.permission` 槽位），
 * 草稿非空时不必在这个菜单里再出现一次。
 *
 * dsh 判的是「光标之前」的文本（`ui-conversation/src/client/apply.ts:514`）；ADSH 的输入框没有
 * 「行内命令 token」模型，判据就是整个草稿是否为空。
 */
fun paletteUsableWith(draft: String, command: PaletteEntry): Boolean =
    draft.isBlank() || command.usableWithDraft

/**
 * 查询是否命中这一行：命令名与本地化标题都匹配，不区分大小写（dsh 的 rankByName 也是两者都匹配，
 * 前缀优先；ADSH 只要「命中就显示」，顺序仍按 SECTION_ROWS）。
 * 空查询 = 全量（dsh：`req.query === ''` 时按小节列出全部）。
 */
fun paletteMatches(query: String, command: PaletteEntry): Boolean =
    query.isEmpty() ||
        command.name.contains(query, ignoreCase = true) ||
        command.label.contains(query, ignoreCase = true)

/**
 * 输入框回车那一下的**意图**（dsh 的 matchEnter + resolveCommand：目录解析之后再派发）。
 *
 * 判定与派发分开：这里只回答「这条草稿想干什么」，真正调 onSend / onSetPlan / 打开权限弹层 /
 * 导出 ZIP 的动作在 ChatScreen 的 onSend 里 —— 那里才有那些回调。搬出来之前这段 when 长在一个
 * lambda 里，一个分支都测不了。
 *
 * 两条 dsh 口径要留着：
 *  - `/plan` 是 **claim token**（[claimTokenOf]）：`/plan 写一个页面` 里 token 之后的内容是**参数**，
 *    切完计划模式要把它当一条消息发出去；`/plan off` 是退出计划模式；
 *  - 其余三条要求草稿**整个等于**命令名（dsh 的 resolveCommand：`descriptor.name === token`），
 *    所以 `/compact 一下` 不是命令，只是一条普通消息。
 */
internal sealed interface CommandIntent {
    /** `/plan`（[rest] 为空）或 `/plan <任务>`（[rest] 非空）：先切模式，再把 rest 当消息发 */
    data class Plan(val on: Boolean, val rest: String) : CommandIntent

    /** `/compact`：压缩以上对话 */
    object Compact : CommandIntent

    /** `/permission`：打开权限弹层（权限是模式切换，改的是沙箱与审批策略） */
    object Permission : CommandIntent

    /** `/export`：把当前会话导出为 ZIP */
    object Export : CommandIntent

    /** 其余一律当消息发出去（[text] 已 trim；空串也照样发 —— 与搬出来之前一致） */
    data class Send(val text: String) : CommandIntent
}

/** 见 [CommandIntent]。 */
internal fun commandIntentOf(draft: String): CommandIntent {
    val text = draft.trim()
    val token = claimTokenOf(text)
    val rest = if (token == null) text else text.removePrefix(token).trim()
    return when {
        token == "/plan" -> if (rest == "off") {
            CommandIntent.Plan(on = false, rest = "")
        } else {
            CommandIntent.Plan(on = true, rest = rest)
        }
        text == "/compact" -> CommandIntent.Compact
        text == "/permission" -> CommandIntent.Permission
        text == "/export" -> CommandIntent.Export
        else -> CommandIntent.Send(text)
    }
}

/**
 * 会话页触发菜单的五条命令（dsh 的 input.commands 里 ADSH 支持的这几条，顺序照 dsh 的 SECTION_ROWS：
 * 添加 file / plan；指令 compact / permission / export）。
 *
 * R19 从 `ChatScreen` 搬出来：这里原本只有纯粹的**字典**，却长在 480 行的主函数里 —— 搬过来之后
 * 「哪些命令、怎么分组、哪些不能与草稿共存」和匹配 / 可用性判定（本文件下半部分）在同一处。
 * 动作仍是回调（选文件的 launcher、写草稿、压缩、打开权限弹层、导出 ZIP 都在调用方）。
 */
internal fun sessionPaletteCommands(
    onPickFile: () -> Unit,
    onPlan: () -> Unit,
    onCompact: () -> Unit,
    onPermission: () -> Unit,
    onExport: () -> Unit,
): List<PaletteCommand> = listOf(
        // 「文件」= dsh 的 input.file（回形针图标）：把系统文件选择器搬进菜单里
        PaletteCommand(
            name = "file",
            label = "文件",
            description = "",
            icon = DshIcons.Paperclip,
            section = PALETTE_SECTION_ADD,
        ) { onPickFile() },
        PaletteCommand(
            name = "plan",
            label = "计划",
            description = "进入或退出计划模式",
            icon = DshSettingIcons.ListPen,
            section = PALETTE_SECTION_ADD,
            // dsh 判的是「触发词在不在行首」（service.ts:247）：不在行首就滤掉带 hint 的行，
            // 也就是要占用输入框的 leadingInput 命令 —— 计划正是这一类（它把 `/plan ` 写进草稿）。
            // dsh 的 leadingInput：/plan 把 token 写进输入框（着色），用户在后面直接输入内容
            usableWithDraft = false,
        ) { onPlan() },
        PaletteCommand(
            name = "compact",
            label = "压缩",
            description = "压缩以上对话内容",
            icon = DshMenuIcons.Compact,
            section = PALETTE_SECTION_COMMANDS,
        ) { onCompact() },
        PaletteCommand(
            name = "permission",
            label = "权限",
            description = "切换权限预设（沙箱模式与审批策略）",
            icon = DshIcons.ShieldAlert,
            section = PALETTE_SECTION_COMMANDS,
            // 模式切换类：草稿非空时不在这个菜单里出现（输入框左下角本来就有常驻的权限入口）。
            // 详见 Palette.paletteUsableWith 的注释。
            usableWithDraft = false,
        ) { onPermission() },
        PaletteCommand(
            name = "export",
            label = "下载日志",
            description = "将当前会话内容导出为 ZIP",
            icon = DshMenuIcons.Download,
            section = PALETTE_SECTION_COMMANDS,
        ) { onExport() },
    )

/** 触发菜单要画的东西：可见的行 + 要不要显示（可见性算式的输出，见 [paletteViewOf]）。 */
internal data class PaletteView<T : PaletteEntry>(val rows: List<T>, val visible: Boolean)

/**
 * 触发菜单的可见性（从 ChatScreen 的主函数里搬出来的 10 行判定）。三条 dsh 规则：
 *  1) 触发词得是**活的**：dsh 从光标往回扫，碰到空白就没有活触发词（core/detect.ts:63）——
 *     所以 `/plan 写一个页面` 不再挂着菜单（见 [slashQueryOf]）；
 *  2) 查询**精确命中**一条命令时命令已经完整，菜单让位，直接回车就能执行
 *     （dsh 的 resolveCommand：`descriptor.name === token`，见 [paletteQueryComplete]）；
 *  3) 一条候选都不剩时自动关闭（dsh 的 menuReduce：allReadyEmpty → closed，core/menu.ts:118）。
 *
 * 两条路径的**优先级**：「+」打开的全量菜单（查询固定为空，只按能不能与草稿共存过滤）赢过
 * 「键入 /」那条 —— 两条同时成立时走 launcher（表在 PaletteTest）。
 *
 * 泛型到 [PaletteEntry]（不是 PaletteCommand）：后者带 ImageVector，纯 JVM 单测加载不了。
 */
internal fun <T : PaletteEntry> paletteViewOf(
    overlays: ChatOverlays,
    draft: String,
    commands: List<T>,
): PaletteView<T> {
    val query = slashQueryOf(draft)
    val complete = query != null && paletteQueryComplete(query, commands)
    val typed = overlays.typedOpen(query, complete, draft)
    val launcher = overlays.launcherOpen(draft)
    val rows = when {
        launcher -> commands.filter { paletteUsableWith(draft, it) }
        typed -> commands.filter { paletteMatches(query.orEmpty(), it) }
        else -> emptyList()
    }
    return PaletteView(rows, rows.isNotEmpty() && (launcher || typed))
}
