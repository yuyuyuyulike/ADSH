package com.adsh.app.core.tools

import com.adsh.app.core.agent.REQUEST_IMAGE_MAX_PIXELS
import com.adsh.app.core.agent.admitToolImage
import com.adsh.app.core.agent.imageBounds
import com.adsh.app.core.agent.requestImageDimensions
import com.adsh.app.core.agent.toolImageEnvelope
import com.adsh.app.core.jobs.Jobs
import com.adsh.app.core.jobs.Spill
import com.adsh.app.core.workspace.Workspace
import com.adsh.app.runtime.termux.ExecResult
import com.adsh.app.runtime.termux.OutputCap
import com.adsh.app.runtime.termux.TermuxRuntime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

data class ToolContext(
    val workspace: Workspace,
    val runtime: TermuxRuntime,
    /**
     * 应用上下文（只给需要**跨进程**的工具用：run_code 要把 PTC 程序绑到 `:ptc` 进程上跑，
     * 见 com.adsh.app.core.ptc.PtcProcess）。显式传入而不是全局单例：这条依赖一眼看得见。
     */
    val appContext: android.content.Context? = null,
    /**
     * web_search 的后端（dsh 的 ctx.web 搜索提供方）：
     * deepseek-official → 走 Anthropic 兼容 Messages + web_search_20250305 服务端工具；
     * exa → 走 Exa 的 /search。工具本身的名字、参数与输出格式两边完全一样。
     */
    val webSearchProvider: String = com.adsh.app.core.data.SettingsStore.WEB_SEARCH_PROVIDER_DEEPSEEK,
    /** web_search 的接口地址（未配置/不可解析时工具返回结构化错误） */
    val webSearchBaseUrl: String = "",
    val webSearchApiKey: String = "",
    /**
     * web_search 单次请求的搜索上限：
     *  - DeepSeek：dsh 的 maxUses，作为 max_uses 发给服务端工具；
     *  - Exa：每个 query 的 numResults（dsh 的 provider 也是把请求里的 maxResults 当 numResults 发）。
     */
    val webSearchMaxUses: Int = com.adsh.app.core.data.SettingsStore.DEFAULT_WEB_SEARCH_MAX_USES,
    /**
     * 同一步内可并行的工具调用数（dsh 的 agent-loop.maxParallelToolCalls / tools.maxParallelSubCalls）：
     * 一个 run_code 程序里 Promise.all 包起来的子调用按这个上限并发。
     */
    val maxParallelSubCalls: Int = com.adsh.app.core.data.SettingsStore.DEFAULT_AGENT_MAX_PARALLEL,
    /** ask_user_question 等待上限（默认 10 分钟） */
    val askTimeoutMs: Long = 10 * 60 * 1000L,
    /** 审批弹窗的等待上限（dsh 没有固定超时：没有应答方就是 unavailable 失败关闭） */
    val approvalTimeoutMs: Long = 10 * 60 * 1000L,
    /**
     * 这次工具调用的 callId（dsh 的 exec.callId）：审批面板要显示它对应的是哪一次调用，
     * 也用于把审批结论记回这一条轨迹。AgentLoop 每执行一次调用就 copy 一份带 id 的上下文。
     */
    val callId: String? = null,
    /**
     * 这次顶层执行的唯一序号（= 会话日志里这条调用的 harnessId）。
     *
     * 为什么不能只靠 [callId] 认领 PTC 子调用的轨迹：模型的 tool_call id **不保证唯一**
     * （有些网关按每条响应从 call_0 重新编号），而这个序号是进程内单调、永不复用的。
     * 子调用回调把它当「父亲是谁」带回日志：`SubCall.id` = `<execToken>:ptc:<n>`。
     */
    val execToken: Long = 0L,
    /** 终端限额（设置 → 功能 → 终端），对齐 dsh 的 shell.* */
    val bashTimeoutMs: Long = TermuxRuntime.DEFAULT_TIMEOUT_MS,
    val bashMaxOutputBytes: Int = TermuxRuntime.DEFAULT_MAX_OUTPUT_BYTES,
    /** bash 的 timeoutMs 参数上限（dsh 的 shell.maxTimeoutMs，默认 600000 = 10 分钟） */
    val bashMaxTimeoutMs: Long = com.adsh.app.core.data.SettingsStore.DEFAULT_BASH_MAX_TIMEOUT_MS,
    /**
     * 权限预设（dsh 的 permission preset）：
     *  - read_only：只读，禁止写文件与执行命令
     *  - workspace_write：写操作限定在工作区内（Args.resolve 已强制），允许执行命令
     *  - full_access：不做额外限制
     */
    val permission: String = com.adsh.app.core.data.SettingsStore.PERMISSION_FULL_ACCESS,
    /**
     * 权限预设的**实时**读取器（第 66 轮）。dsh 的 sandboxPolicy.resolve 是每个受限调用现算的
     * ——「Takes effect on the session's next confined call」：用户在对话中途改了预设，下一次
     * bash / write / edit 就按新预设判。[permission] 是构造这一轮时的快照，长轮次里会过期。
     */
    val livePermission: (() -> String)? = null,
    /**
     * PTC 子调用的实时回调（dsh 的 tool/ptc-dispatch）：程序里每跑完一次
     * await tools.x() 就回调一次，界面据此把子调用逐行长出来。
     * 由工具实现所在的线程调用，实现必须线程安全。
     *
     * 身份在 [com.adsh.app.core.ptc.SubCall.id] 里：形如 `<顶层执行的 execToken>:ptc:<n>`
     * （dsh 的 `subCallId = callId + ':ptc:' + n`，见 spec-log §3）。**「属于哪一次顶层调用」
     * 就写在 id 里**，界面按前缀归属、不需要任何「谁先到」的判据 —— 第 108 轮那套
     * 归属状态机（owner / parent / 清空时机）因此整块删掉。
     */
    val onSubCall: ((sub: com.adsh.app.core.ptc.SubCall) -> Unit)? = null,
    /**
     * PTC 子调用「开始」的实时回调（dsh 的 tool/call 事件）。
     *
     * dsh 里每个子调用都是独立的一条 timeline 记录：先有 call（行上带运行扫光），
     * 再有 result（扫光停、摘要换成结果）。我们原来只在跑完时回调一次，
     * 于是组合工具下面的子行**从来不会处在运行态**，扫光也就永远不出现。
     * 两个回调用同一个 id（[com.adsh.app.core.ptc.SubCall.id]）配对，界面把「结束」贴回同一行。
     */
    val onSubCallStart: ((sub: com.adsh.app.core.ptc.SubCall) -> Unit)? = null,
    /** 当前轮次序号（dsh 的 present 输出里带 turn） */
    val turn: Int = 0,
    /**
     * 本次调用所属的会话 id（dsh 的 `exec.agent.session`）。
     *
     * todo_write 的输出目标是**会话级**的清单（dsh 的 session projection「todos」）：
     * 没有会话可写的调用方直接拒绝，界面上那份清单也只在自己的会话里显示。
     */
    val conversationId: Long? = null,
    /** 本轮是否处于计划模式（exit_plan_mode 只在计划模式里可用） */
    val planMode: Boolean = false,
    /** 计划被批准后的回调（写回会话的 planMode） */
    val onPlanModeChanged: ((Boolean) -> Unit)? = null,
    /**
     * 当前路由的模型 id（dsh 的 catalog model id）：read_image 的「模型没声明 image 输入」
     * 报错里要回显它，与 dsh 的 assertImageCapableRoute 文案逐字对应。
     */
    val modelId: String = "",
    /** 当前模型是否接受图片输入（dsh 的 catalog inputModalities 含 "image"） */
    val imageCapable: Boolean = false,
    /**
     * 会话附件目录（`<工作区>/.adsh/attachments/<会话 id>/`）：read_image 把读到的图片
     * 落一份只读副本在这里（dsh 的 attachments.saveImage 的等价物）。
     */
    val attachmentDir: File? = null,
)

sealed interface ToolResult {
    /**
     * 成功结果：
     *  - [text] 是「展示/日志」用的正文（对话里那一行工具的输出）；
     *  - [value] 是程序里 await tools.x() **真正拿到**的结构化值（dsh 的 output.schema）。
     *    为 null 时退化成 { result: text }（老工具/老会话兼容）。
     */
    data class Ok(
        val text: String,
        val value: kotlinx.serialization.json.JsonElement? = null,
        /**
         * 这次调用读到/产出的图片（dsh 的输出内容块里的 image 块）。
         *
         * PTC 里它是**子调用**的产物：run_code 的程序拿到结构化值，图片本身由 AgentLoop
         * 收走（SubCallTrace → 落库 → 装配时作为一条 deferred user 消息回灌模型，dsh 的
         * tools-ptc deferContext 就是这么做的），界面则在那一行下面渲染画廊。
         */
        val images: List<com.adsh.app.core.agent.ToolImage> = emptyList(),
        /**
         * 这次调用声明的交付物（只有 present 会产出）—— dsh 的 `deliverables/presented`：
         * 成功的最终结果追加一条会话事件，界面按轮把文件卡片画在轮尾、点开进文件预览。
         * 与 [images] 走同一条传递路径（SubCall → SubCallTrace → 工具行落库）。
         */
        val deliverables: List<com.adsh.app.core.agent.PresentedFile> = emptyList(),
    ) : ToolResult

    /**
     * 失败结果。
     * @param message 人可读的原文（dsh 的 WebError.message / 工具自身报错），**不带** Error: 前缀
     * @param retryable ADSH 自己的提示：同样的调用重试一次可能成功
     * @param code 可机读的原因码（dsh 的 WebError.code，如 WEB_BLOCKED_URL / WEB_PROVIDER_ERROR），
     *   由执行层以结构化错误字段暴露（dsh 的 "Tool execution exposes the code in structured error metadata"）
     */
    data class Error(
        val message: String,
        val retryable: Boolean = false,
        val code: String? = null,
    ) : ToolResult
}

interface Tool {
    val name: String
    val description: String
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

/**
 * 工具共用的小工具方法。**只管「模型传进来的工具入参」。**
 *
 * 第 2 阶段熵减核实过一条边界（审计把三类东西都算成了「绕开 Args 的裸 jsonPrimitive」）：
 *  - **工具入参** —— 必须走这里（Approval 的 sandbox_permissions / justification 已收回）；
 *  - **供应商返回的 JSON** —— 搜索 / 抓取的响应（WebTools 里 item["url"]、data["path"] 那批）
 *    与 ripgrep 的结构化输出（Tools.parseGrepMatches），**故意不走这里**：那些键是别人定的方言，
 *    和工具入参无关，硬套会让「参数读取」这一层的含义糊掉；
 *  - **回环演示流**里手搓的参数（ExtraTools 的 questions 解析）走的是测试路径，同理。
 *
 * bool 也由此分两套语义，都不是笔误：这里的 [bool] 读**字符串** "true"/"false"
 * （模型写 JSON 参数时会写成字符串），`booleanOrNull` 那侧读**原生 bool**（供应商响应里就是真 bool）。
 */
internal object Args {
    /** dsh 的 file_path：新名字；老的 path 继续认，老会话的历史调用不会失效 */
    fun filePath(args: JsonObject): String? = str(args, "file_path") ?: str(args, "path")

    fun str(args: JsonObject, key: String): String? = args[key]?.jsonPrimitive?.contentOrNull
    fun int(args: JsonObject, key: String): Int? = args[key]?.jsonPrimitive?.intOrNull
    fun bool(args: JsonObject, key: String): Boolean? =
        args[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

    /**
     * 把工具入参路径解析成绝对路径（相对路径相对工作区）。
     *
     * **读路径不做围栏**：dsh 的读观察（fs-observation-policy）不限制路径，
     * 工作区外的绝对路径（/sdcard、$PREFIX…）照读；只有**写**才由沙箱围栏管
     * （见 [resolveMutation]）。以前这里对读写一视同仁地拒绝越界，结果是
     * 「完全权限」预设下连 /sdcard 里的一张图片都读不出来。
     */
    fun resolve(ctx: ToolContext, path: String?): File {
        val root = ctx.workspace.shellRoot
            ?: throw IllegalArgumentException("当前工作区为 SAF 引用形态，shell 类工具不可用")
        if (path.isNullOrBlank()) return root
        val mapped = mapTmp(ctx, path)
        val file = File(mapped)
        val resolved = if (file.isAbsolute) file else File(root, mapped)
        return runCatching { resolved.canonicalFile }.getOrElse { resolved.absoluteFile }
    }

    /**
     * /tmp → $TMPDIR 的映射：**与 shell 层同一条规则**。
     *
     * Android 没有可写的 /tmp（属主 shell、0711），所以 shim（fence.c 的 redirect 规则 2）
     * 在 libc 层把 /tmp/... 透明改写到 $TMPDIR（= $PREFIX/tmp）；工具层以前没跟上，
     * 于是出现「shell 里 /tmp 能用、write "/tmp/x" 却被沙箱拒」的分裂（测试 agent 实测）。
     * 这里补上同一层映射，读 / 写 / 编辑 / glob / grep / bash 的 workdir 全部一致 ——
     * 映射完的路径落在 $PREFIX/tmp 下，正好也在写围栏的临时目录白名单里，不需要额外放行。
     *
     * 只认绝对路径的 /tmp（相对路径是相对工作区解析的，跟 /tmp 无关）。
     */
    private fun mapTmp(ctx: ToolContext, path: String): String {
        if (path != "/tmp" && !path.startsWith("/tmp/")) return path
        val tmp = runCatching { ctx.runtime.tmpDir().absolutePath }.getOrNull() ?: return path
        return tmp + path.removePrefix("/tmp")
    }

    /** 路径是否在工作区内（含工作区根自身）；canonical 之后比较 */
    fun inside(ctx: ToolContext, file: File): Boolean {
        val root = ctx.workspace.shellRoot ?: return false
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val canonical = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        return canonical == canonicalRoot ||
            canonical.path.startsWith(canonicalRoot.path + File.separator)
    }

    /** 写围栏的判决：放行（含解析后的路径）或拒绝（含给模型看的 marker + hint） */
    sealed interface WriteGate {
        data class Allowed(val file: File) : WriteGate
        data class Denied(val message: String) : WriteGate
    }

    /**
     * 写路径的围栏（dsh 的 fs-sandbox fence：模式 = 可写根白名单，dsh-sandbox 的 writableRoots）：
     *  - read-only：任何写都拒绝（白名单为空）；
     *  - workspace-write：工作区 + 临时目录（dsh 的白名单是 workspaceRoot / /tmp / os.tmpdir()）；
     *  - danger-full-access：不限制。
     *
     * 拒绝时给的是 dsh 的两行标记，模型可以照 hint 重试一次升级（那就走审批弹窗）。
     */
    fun gateWrite(mode: String, ctx: ToolContext, path: String?): WriteGate {
        val file = resolve(ctx, path)
        val subject = "operation"
        if (mode == Escalation.READ_ONLY) return WriteGate.Denied(Escalation.denial(mode, subject))
        if (mode == Escalation.FULL_ACCESS) return WriteGate.Allowed(file)
        val tempRoots = writableRoots(ctx)
        val allowed = inside(ctx, file) || tempRoots.any { root ->
            val canonical = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
            val target = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
            target == canonical || target.path.startsWith(canonical.path + File.separator)
        }
        return if (allowed) WriteGate.Allowed(file) else WriteGate.Denied(Escalation.denial(mode, subject))
    }

    /**
     * workspace-write 下**除了工作区之外**还可以写的根：临时目录 + 应用私有 cache + $ADSH_SCRATCH。
     *
     * dsh 的 writableRoots 是「工作区 + /tmp + os.tmpdir()」（dsh-sandbox/roots）。
     * ADSH 多一个 scratch（$HOME/scratch）：系统提示词的 android-termux 段明确让模型把
     * 装依赖 / 构建 / git 这些「要真实文件系统」的活放到 $ADSH_SCRATCH 下做，而它不在
     * dsh 的那三个根里 —— 于是围栏（bash 的 shim 与 write/edit 的 gateWrite）会把模型
     * 按提示词做的事全部拒掉（实测：写 $HOME/scratch 报 file access denied）。
     * 提示词承诺可写、围栏就必须可写，两边口径只能有一个；这条已写进报告。
     *
     * **第九十一轮**（审查报告 F3）：cache 目录收 [TermuxRuntime.cacheRoots] 给的**两种拼写**
     * （`/data/user/0/<pkg>/cache` 与 `/data/data/<pkg>/cache`）—— 围栏按字面前缀判定，
     * 只收一侧时 apt 那一侧（/data/data）照样被拒。
     *
     * 系统提示词那段可写根清单（[com.adsh.app.core.agent.PromptAssembler.androidEnvText]）
     * 说的就是这里返回的东西，两边改一处要一起改。
     */
    fun writableRoots(ctx: ToolContext): List<File> = listOfNotNull(
        runCatching { ctx.runtime.tmpDir() }.getOrNull(),
        runCatching { File(System.getProperty("java.io.tmpdir") ?: "/tmp") }.getOrNull(),
        runCatching { ctx.runtime.scratchDir() }.getOrNull(),
    ) + runCatching { ctx.runtime.cacheRoots() }.getOrDefault(emptyList())

    /**
     * dsh 的 displayPath：工作区内的文件用相对路径（模型看到的 <path>、报错里的路径都用它），
     * 工作区外（例如 /tmp 下的临时文件）才回落到绝对路径。
     */
    fun display(ctx: ToolContext, file: File): String {
        val root = ctx.workspace.shellRoot ?: return file.path
        val base = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return file.path
        val target = runCatching { file.canonicalFile.toPath() }.getOrNull() ?: return file.path
        val rel = runCatching { base.relativize(target).toString() }.getOrElse { return file.path }
        if (rel.isEmpty() || rel.startsWith("..")) return file.path
        return rel.replace(File.separatorChar, '/')
    }
}

private val toolJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** dsh-tool-fs-search 的 GLOB_VCS_EXCLUDES（逐字）：glob 不看版本库元数据目录 */
private val VCS_EXCLUDES = listOf(".git", ".svn", ".hg", ".bzr", ".jj", ".sl")

/** dsh 的 globMaxResults 默认值（内联返回的路径条数上限） */
private const val GLOB_MAX_RESULTS = 100

/** dsh 的 grepMaxMatches 默认值 */
private const val GREP_MAX_MATCHES = 250

/** dsh 的 grepMaxLineBytes 默认值（单行预览上限，按字节） */
private const val GREP_MAX_LINE_BYTES = 2000

/** read 一次最多返回的行数（dsh 的 readMaxLines 默认值，同时也是 limit 的上限） */
private const val READ_MAX_LINES = 2000

/** 整文件读入的设备侧安全上限（dsh 是流式窗口，Android 上直接读整文件，超过就拒绝） */
private const val READ_MAX_FILE_BYTES = 4L * 1024 * 1024

/** dsh 的 readMaxLineLength 默认值：单行超过这么多**字符**就截断（截完带一句标注） */
private const val READ_MAX_LINE_CHARS = 2000

/** dsh 的 readMaxBytes 默认值：一次 read 的正文预算（UTF-8 字节；行号前缀按 dsh 不计入） */
private const val READ_MAX_BYTES = 50 * 1024

/**
 * dsh 的 search-core RAW_OUTPUT_MAX_BYTES：ripgrep 的**原始 stdout** 超过这么多字节，
 * 整次调用失败（SEARCH_RAW_OUTPUT_OVERFLOW），而不是截断 —— 那说明 pattern/path 太宽。
 */
private const val GREP_RAW_MAX_BYTES = 20_000_000

/** dsh-fs-local 的 BINARY_SAMPLE_BYTES：只看开头这么多字节判二进制 */
private const val BINARY_SAMPLE_BYTES = 8192

/**
 * dsh-fs-local 的「这段字节能当文本读吗」判断（readWholeText 与 edit 的写前检查共用）：
 *  - 前 [BINARY_SAMPLE_BYTES] 字节里出现 NUL → 二进制；
 *  - 严格 UTF-8 解码失败 → 无效 UTF-8 文本。
 * 返回 null 表示能读；否则是 dsh 的 FS_NOT_TEXT 文案（read / edit 只差动词）。
 *
 * 第 66 轮修的是这里：以前 read 直接 file.readLines()，二进制（PDF 之类）会被解码成一堆
 * 替换字符照发（实测一份 PDF 吐出 7495 行乱码，既不报错也不提示）。
 */
internal fun notTextReason(bytes: ByteArray): String? {
    if (bytes.take(BINARY_SAMPLE_BYTES).any { it == 0.toByte() }) return "binary file"
    return try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes))
        null
    } catch (e: java.nio.charset.CharacterCodingException) {
        "invalid UTF-8 text"
    }
}

/** bash：在工作区执行命令。正文渲染逐字对齐 dsh-tool-bash 的 renderResult。 */
object BashTool : Tool {
    override val name = "bash"
    override val description: String get() = ToolSdk.description("bash")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = Args.str(args, "command") ?: ""
        if (command.isBlank()) return ToolResult.Error("invalid command: expected a non-empty string")
        if ((Args.str(args, "description") ?: "").isBlank()) {
            return ToolResult.Error("invalid description: expected a non-empty string")
        }
        val timeoutArg = Args.int(args, "timeoutMs")
        if (timeoutArg != null && timeoutArg <= 0) {
            return ToolResult.Error("invalid timeoutMs: expected a positive number, got " + timeoutArg)
        }
        // 沙箱模式 = 权限预设，可能被这一次调用的 sandbox_permissions 升级（要过审批弹窗）。
        // dsh 的 approveBashEscalation 也是在**任何东西执行之前**先解析，然后才落 shell。
        val mode = try {
            resolveCallMode(
                args,
                ctx,
                EscalationAsk(toolName = "bash", callId = ctx.callId, subject = "command", detail = command),
            )
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return ToolResult.Error(t.message ?: "sandbox escalation failed")
        }
        // read-only：**命令照跑**，写效果由围栏拒绝（白名单为空 = 没有任何可写根）。
        // dsh 就是这个语义：bash 在 read-only 下仍然执行，内核沙箱拒绝它的写，工具结果里才出现
        // denialMarker + hintMarker。以前这里整条拒绝，于是连 `ls` 这种纯读命令都回一句
        // 「可以提权」—— 噪音，而且与 dsh 的行为不一致（dsh-sandbox-local 的 read-only 只是不放行写）。
        val shellRoot = ctx.workspace.shellRoot
        if (shellRoot == null) {
            return ToolResult.Error("当前工作区为 SAF 引用形态，bash 不可用（请绑定真实路径工作区）")
        }
        // dsh 的 clampTimeout：模型没给 timeoutMs 就用设置里的默认值，给了就夹在 (0, maxTimeoutMs]
        val ceiling = ctx.bashMaxTimeoutMs.coerceAtLeast(1L)
        val timeout = (timeoutArg?.toLong() ?: ctx.bashTimeoutMs).coerceIn(1L, ceiling)
        // dsh 的参数名是 workdir；老会话里的 cwd 继续认
        val workdirArg = Args.str(args, "workdir") ?: Args.str(args, "cwd")
        val workdir = if (workdirArg != null) Args.resolve(ctx, workdirArg) else shellRoot
        // 解析出来的必须**真的是个目录**：dsh 那边由 Node 的 spawn 兜底（cwd 不存在直接 ENOENT），
        // 而 Java 的 ProcessBuilder 会把子进程丢在 `/` 上照跑 —— 退出码 0、stderr 空，
        // 模型以为自己在工作区里（实测报告第 4 点：pwd 返回 `/`，用绝对路径的命令"成功"得很自然）。
        if (!workdir.isDirectory) {
            return ToolResult.Error("working directory does not exist: " + workdir.absolutePath)
        }
        // 写围栏（dsh 的 fs-sandbox fence）：workspace-write 下把「工作区外只读」交给
        // LD_PRELOAD 的 libadshfence.so 判决（原生方案，没有 proot 的 ptrace 开销）。
        // 拒绝时 shim 往 stderr 打 dsh 的两行标记，模型据此带 sandbox_permissions 申请一次升级，
        // App 就弹审批卡。read-only 挂的是**空白名单**（命令照跑、任何写都被拒）；
        // danger-full-access 不挂：fenceEnv 返回空，模式仍是 environment() 给的
        // danger-full-access，shim 据此一次写判决都不做（也**不会**导出 ADSH_FENCE_ACTIVE，
        // 见 fenceEnv 里那段注释 —— 第 113 轮点名的假信号）。
        val fenceMark = File(ctx.runtime.tmpDir(), ".adsh-fence-alive")
        // 可写根：workspace-write 才是「工作区 + 临时目录 + 应用 cache + $ADSH_SCRATCH」；
        // read-only 一个都不给。清单只此一处（[Args.writableRoots]），提示词那份读的也是它。
        val fence = ctx.runtime.fenceEnv(
            mode = mode,
            roots = if (mode == Escalation.READ_ONLY) emptyList()
            else listOfNotNull(ctx.workspace.shellRoot) + Args.writableRoots(ctx),
            mark = fenceMark,
        )
        if (fence.isNotEmpty()) runCatching { fenceMark.delete() }
        // 完全权限（fence 为空）是唯一「什么都不做」的分支：dsh 的 danger-full-access 也是
        // 完全不约束，围栏在这里本来就不挂。
        if (fence.isEmpty() && mode == Escalation.READ_ONLY) {
            // **围栏整个挂不上时必须失败关闭**（dsh 的 SandboxUnavailableError：refusing to run
            // the command unconfined）。以前只看哨兵文件，shim 缺失时 fence 就是空的，哨兵检查
            // 根本不会触发 —— 等于在只读预设下放行了完整写权限。
            return ToolResult.Error(
                "sandbox mode \"read-only\" cannot be enforced on this device: " +
                    TermuxRuntime.FENCE_LIB + " is missing from the app's native libraries; refusing " +
                    "to run the command unconfined.",
            )
        } else if (fence.isEmpty()) {
            // workspace-write 但可写根一个都解析不出来（或 shim 缺失）：只能记日志、照跑 ——
            // 不能因为环境坏了就把 bash 整个拒掉（终端页、apt 这些本来也不挂围栏）。
            android.util.Log.w(
                "ADSH",
                "写围栏未挂载：可写根为空或 " + TermuxRuntime.FENCE_LIB + " 缺失，这次命令不受围栏约束",
            )
        }
        // dsh 的 bash 缝：**每条命令一开始就注册成任务**，前台只是对同一个任务的有界 wait ——
        // 超时不再是「杀掉」，而是「转后台继续跑」（promote，第 118 轮）。注册被拒（owner 的活跃
        // 任务满）才退回下面的前台直跑路径（那条路 dsh 也有：没有注册表时只有前台）。
        val started = try {
            startBashJob(ctx, command, workdir, fence, foreground = Args.bool(args, "run_in_background") != true)
        } catch (t: Throwable) {
            android.util.Log.w("ADSH", "bash: 后台任务注册被拒，退回前台直跑：" + (t.message ?: t.toString()))
            null
        }
        if (started != null) {
            // 围栏哨兵：shim 一加载就写这个文件。任务在**开始时**就注册，所以「启动之后、等待之前」
            // 是唯一能确认这次 LD_PRELOAD 真的生效的窗口（read-only 拿不到就失败关闭，与老路径一致）。
            if (fence.isNotEmpty() && !fenceLoaded(fenceMark, started.id, ctx.conversationId)) {
                android.util.Log.w("ADSH", "写围栏未生效：LD_PRELOAD 没有加载 " + TermuxRuntime.FENCE_LIB)
                if (mode == Escalation.READ_ONLY) {
                    // 失败关闭：杀掉刚起的命令，等它结算（这次结算算 awaited，不会发通知），再丢记录
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching { Jobs.kill(started.id, ctx.conversationId, "sandbox unavailable") }
                        runCatching { Jobs.wait(started.id, KILL_SETTLE_MS, ctx.conversationId) }
                        runCatching { Jobs.remove(started.id, ctx.conversationId) }
                    }
                    return ToolResult.Error(
                        "sandbox mode \"read-only\" cannot be enforced on this device: the file-access " +
                            "fence (" + TermuxRuntime.FENCE_LIB + ") was not loaded; refusing to run the " +
                            "command unconfined.",
                    )
                }
            }
            if (Args.bool(args, "run_in_background") == true) {
                val value = buildJsonObject {
                    put("kind", "background")
                    put("jobId", started.id)
                }
                return ToolResult.Ok("started background job " + started.id, value)
            }
            return waitForeground(ctx, started, timeout)
        }
        // 可中断执行：用户按「停止」时这一步要**立刻**杀掉命令（探针问的是本协程还活着吗）。
        // 没有它的话 Process.waitFor 是阻塞的 —— 停止要等命令自己跑完（最长 timeoutMs = 120s）。
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val result = ctx.runtime.run(
            command,
            workspaceRoot = workdir,
            timeoutMs = timeout,
            maxOutputBytes = ctx.bashMaxOutputBytes,
            separateStreams = true,
            extraEnv = fence.takeIf { it.isNotEmpty() },
            cancel = { job?.isActive == false },
        )
        // 被中断的命令：命令树已经杀掉了，这里直接把取消抛出去（不要变成一条工具错误 ——
        // 那会让「停止」退化成「工具失败 + 继续下一轮」）。已经产出的 stdout/stderr 照样带走，
        // 落库那条 interrupted 消息里能看到它跑到哪儿了。
        if (result.cancelled) {
            throw kotlinx.coroutines.CancellationException("bash interrupted by the user")
        }
        if (fence.isNotEmpty() && !fenceMark.exists()) {
            // 哨兵没出现 = 这次调用的 LD_PRELOAD 没被动态链接器采纳，写围栏没有生效。
            //  - workspace-write：只能留痕。不能因为「围栏没生效」就把命令整个拒掉，
            //    那会让 workspace-write 下的 bash 完全不可用（终端页、apt 这些本来也不挂围栏）。
            //  - read-only：**失败关闭**（dsh 的 SandboxUnavailableError：「refusing to run the
            //    command unconfined」）。放它跑等于在只读预设下给了完整写权限 —— 比拒绝危险得多。
            android.util.Log.w("ADSH", "写围栏未生效：LD_PRELOAD 没有加载 " + TermuxRuntime.FENCE_LIB)
            if (mode == Escalation.READ_ONLY) {
                return ToolResult.Error(
                    "sandbox mode \"read-only\" cannot be enforced on this device: the file-access " +
                        "fence (" + TermuxRuntime.FENCE_LIB + ") was not loaded; refusing to run the " +
                        "command unconfined.",
                )
            }
        }
        // 与注册表那条路同一份渲染：只留尾部，丢过字节就补 dsh 那行「全文在哪」
        val stdout = streamText(clean(result.output, ctx), result.truncated, result.spillPath)
        val stderr = streamText(clean(result.stderr, ctx), result.stderrTruncated, result.stderrSpillPath)
        val value = buildJsonObject {
            put("kind", "foreground")
            put("exitCode", if (result.timedOut) JsonNull else JsonPrimitive(result.exitCode))
            put("signal", JsonNull)
            put("timedOut", result.timedOut)
            put("aborted", false)
            put("timeoutMs", timeout)
            put("stdout", streamValue(stdout, result.truncated, result.spillPath))
            put("stderr", streamValue(stderr, result.stderrTruncated, result.stderrSpillPath))
        }
        // dsh 的 bash：非零退出**不是**工具错误（isError = false），模型自己看 [exit code: N] 决定怎么办
        // 正文渲染（含状态标记的互斥规则）在 BashRender.kt
        return ToolResult.Ok(renderBash(result, stdout, stderr), value)
    }
}

// ------------------------------------------------------ 后台任务（dsh 的 tool-bash/background.ts）

/** 注册表里的一条 bash 任务：id + 进程句柄 + 外部 kill 的原因（dsh 的 StartedJob） */
private class StartedBash(
    val id: String,
    private val processOf: () -> TermuxRuntime.ShellProcess?,
    private val stoppedOf: () -> String?,
) {
    // 名字必须与属性不同：`fun process() = process()` 会把函数体解析成**自己**（无限递归，
    // 真机上就是 StackOverflowError: stack size 1037KB —— 第 118 轮的现场教训）。
    fun process(): TermuxRuntime.ShellProcess? = processOf()
    fun stopped(): String? = stoppedOf()
}

/** 读线程的缓冲（字符数；UTF-8 跨读边界由 InputStreamReader 负责） */
private const val PUMP_BUFFER_CHARS = 8192

/** 前台 wait 被中止后等它真的死掉的上限（SIGTERM 3s + SIGKILL 300ms 之后还有余量） */
private const val KILL_SETTLE_MS = 10_000L

/** 围栏哨兵的等待上限与轮询间隔（正常几毫秒就出现） */
private const val FENCE_MARK_WAIT_MS = 1_500L
private const val FENCE_MARK_POLL_MS = 20L

/**
 * 把一次 bash 进程接成后台任务（dsh 的 tool-bash/background.ts）：
 * 进程在 starter 里同步起（起不来就立刻交一个 failed 终局），两条流各一根读线程往环里写，
 * 等待线程在进程退出、两条流都排空之后交出退出码；外部 kill 的原因并进终局。
 */
private fun startBashJob(
    ctx: ToolContext,
    command: String,
    workdir: File?,
    fence: Array<String>,
    /** true = 这次调用随后会 wait 它（前台）：注册那一刻就把「有人等」记上，见 Jobs.start 的 hold */
    foreground: Boolean,
): StartedBash {
    val runtime = ctx.runtime
    val processRef = java.util.concurrent.atomic.AtomicReference<TermuxRuntime.ShellProcess?>()
    val stoppedRef = java.util.concurrent.atomic.AtomicReference<String?>()
    // 环的上限跟着 bash 的输出上限走（默认 64 KB ⇒ 256 KB，正好是 dsh 的 retainBytes 默认值）：
    // 正文还会被 OutputCap 再做一次「头 + 尾」的截断，所以环只要兜住截断之前的那份原文。
    val retain = (ctx.bashMaxOutputBytes * 4).coerceAtLeast(Jobs.RETAIN_BYTES)
    val id = Jobs.start(
        kind = "bash",
        label = command,
        owner = ctx.conversationId,
        retainBytes = retain,
        hold = foreground,
        // 全文 spill（dsh 的 OutputCollector）：越过内存上限就落盘，模型可以自己 read 回来
        spillDir = Spill.directory(ctx.runtime.tmpDir()),
        outputLimitBytes = ctx.bashMaxOutputBytes,
    ) { handle ->
        val spawned = runCatching {
            runtime.spawn(command, workdir, fence.takeIf { it.isNotEmpty() }, separateStreams = true)
        }
        val shell = spawned.getOrNull()
        if (shell == null) {
            val why = spawned.exceptionOrNull() ?: RuntimeException("spawn failed")
            android.util.Log.w("ADSH", "bash: 起进程失败", why)
            object : Jobs.Hooks {
                override fun cancel(reason: String?) = Unit
                override val done = kotlinx.coroutines.CompletableDeferred(
                    Jobs.Outcome(Jobs.Status.FAILED, why.message ?: why.toString()),
                )
            }
        } else {
            processRef.set(shell)
            val outPump = pumpStream(handle, shell.stdout, Jobs.Channel.STDOUT)
            val errPump = pumpStream(handle, shell.stderr, Jobs.Channel.STDERR)
            val done = kotlinx.coroutines.CompletableDeferred<Jobs.Outcome>()
            Thread({
                val exit = runCatching { shell.waitFor() }.getOrDefault(-1)
                // 排空：进程退出之后管道里剩下的字节先落进环，再结算（dsh 的「settle 前最后一次 drain」）
                outPump.join(1000)
                errPump.join(1000)
                shell.dispose()
                done.complete(
                    if (stoppedRef.get() != null) {
                        Jobs.Outcome(Jobs.Status.KILLED, "killed before exit")
                    } else {
                        Jobs.Outcome(Jobs.Status.COMPLETED, "exit code: " + exit)
                    },
                )
            }, "adsh-job-wait").apply { isDaemon = true }.start()
            object : Jobs.Hooks {
                override fun cancel(reason: String?) {
                    stoppedRef.compareAndSet(null, reason ?: "cancelled")
                    shell.terminate()
                }

                override val done = done
            }
        }
    }
    return StartedBash(id, { processRef.get() }, { stoppedRef.get() })
}

/** 一条流的读线程（dsh 的 pull 源 + 注册表泵，在这一层合成一根线程） */
private fun pumpStream(handle: Jobs.Handle, stream: java.io.InputStream, channel: Jobs.Channel): Thread {
    val reader = java.io.InputStreamReader(stream, Charsets.UTF_8)
    return Thread({
        val buffer = CharArray(PUMP_BUFFER_CHARS)
        try {
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                if (n > 0) handle.append(String(buffer, 0, n), channel)
            }
        } catch (_: Throwable) {
            // 流断了就到此为止（dsh 的 guardSource：源读失败只记日志，任务照常结算）
        }
    }, "adsh-job-pump").apply { isDaemon = true; start() }
}

/** 围栏哨兵最多等一会儿；任务先结算的极短命令也算通过（那时 shim 已经加载过了） */
private suspend fun fenceLoaded(mark: File, id: String, owner: Long?): Boolean {
    val deadline = System.currentTimeMillis() + FENCE_MARK_WAIT_MS
    while (!mark.exists()) {
        if (System.currentTimeMillis() >= deadline) return false
        if (Jobs.get(id, owner).status.terminal) return mark.exists()
        kotlinx.coroutines.delay(FENCE_MARK_POLL_MS)
    }
    return true
}

/**
 * 前台 = 对同一个任务的有界 wait（dsh 的 waitOnJob）：
 *  - wait 内结算：模型从没见过这个 id → remove，按普通前台结果渲染；
 *  - 超时还在跑：做一次**消费读**当结果种子（job_output 从那里接着读，不重复），返回 promoted；
 *  - 调用被中止：kill + 等它结算（这次结算算 awaited，不会再发通知），再把取消抛出去。
 */
private suspend fun waitForeground(ctx: ToolContext, started: StartedBash, timeoutMs: Long): ToolResult {
    val owner = ctx.conversationId
    val view = try {
        Jobs.wait(started.id, timeoutMs, owner)
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching { Jobs.kill(started.id, owner, "tool call aborted") }
            runCatching { Jobs.wait(started.id, KILL_SETTLE_MS, owner) }
            runCatching { Jobs.remove(started.id, owner) }
        }
        throw cancel
    }
    if (!view.status.terminal) {
        val read = Jobs.read(started.id, owner)
        val output = renderJobDelta(read.chunks, read.lossy, read.job.spillPaths)
        val value = buildJsonObject {
            put("kind", "promoted")
            put("jobId", started.id)
            put("timeoutMs", timeoutMs)
            put("output", output)
        }
        return ToolResult.Ok(renderPromoted(started.id, timeoutMs, output), value)
    }
    // 本次调用内就结算了：模型从没见过这个 id，记录随这次结果离开注册表（dsh 的 remove）
    val read = Jobs.read(started.id, owner)
    runCatching { Jobs.remove(started.id, owner) }
    val shell = started.process()
        ?: run {
            // 进程压根没起来（起不来 / 起完就没）：终局原因就是模型该看到的错误
            android.util.Log.w("ADSH", "bash: 任务 " + started.id + " 没有进程，detail=" + view.detail)
            return ToolResult.Error(view.detail ?: "command failed to start")
        }
    val exitCode = runCatching { shell.exitValue() }.getOrDefault(-1)
    return foregroundResult(ctx, read, exitCode, started.stopped(), timeoutMs)
}

/**
 * 把任务环里读到的输出渲染成普通前台结果（dsh 的 renderResult + canonicalBashResult）：
 * stdout、一个 [stderr] 段、状态标记（外部停止 / 退出码），值里 stdout/stderr 分开。
 */
private fun foregroundResult(
    ctx: ToolContext,
    read: Jobs.Read,
    exitCode: Int,
    stopped: String?,
    timeoutMs: Long,
): ToolResult {
    val rawOut = read.chunks.filter { it.channel != Jobs.Channel.STDERR }.joinToString("") { it.text }
    val rawErr = read.chunks.filter { it.channel == Jobs.Channel.STDERR }.joinToString("") { it.text }
    // 只留尾部（dsh 的 tail-keep）：环里保留的是上限的 4 倍，模型看到的是最后 bashMaxOutputBytes
    val (outTail, outCapped) = OutputCap.tail(clean(rawOut, ctx), ctx.bashMaxOutputBytes)
    val (errTail, errCapped) = OutputCap.tail(clean(rawErr, ctx), ctx.bashMaxOutputBytes)
    // 每一路各自判「有没有丢过字节」：环里被淘汰的（droppedOf）或这一步截掉的（capped）
    val outTruncated = outCapped || Jobs.Channel.STDOUT in read.droppedChannels
    val errTruncated = errCapped || Jobs.Channel.STDERR in read.droppedChannels
    val stdout = streamText(outTail, outTruncated, read.job.stdoutSpillPath)
    val stderr = streamText(errTail, errTruncated, read.job.stderrSpillPath)
    val result = ExecResult(
        exitCode = exitCode,
        output = stdout,
        timedOut = false,
        truncated = outTruncated || errTruncated,
        durationMs = 0,
        stderr = stderr,
        stderrTruncated = errTruncated,
        timeoutMs = timeoutMs,
    )
    val value = buildJsonObject {
        put("kind", "foreground")
        put("exitCode", JsonPrimitive(exitCode))
        put("signal", JsonNull)
        put("timedOut", false)
        put("aborted", false)
        put("timeoutMs", timeoutMs)
        put("stdout", streamValue(stdout, outTruncated, read.job.stdoutSpillPath))
        put("stderr", streamValue(stderr, errTruncated, read.job.stderrSpillPath))
    }
    return ToolResult.Ok(renderBash(result, stdout, stderr, stopped), value)
}

/**
 * 把运行时内部路径从输出里抹掉：bash 是以 nativeLibraryDir/libbash.so 起的，
 * 报错会写成 /data/app/.../lib/arm64/libbash.so: line 1: python3: command not found，
 * 看着像环境损坏。dsh 里就是 bash: line 1: ...，这里等价地换成 bash。
 */
private fun clean(text: String, ctx: ToolContext): String = text.replace(ctx.runtime.bashPath, "bash")

/**
 * read：带行号读取文本。
 * 正文是 dsh 的 formatReadOutput：<path>/<type>/<content> 信封 + 「行号: 内容」+ 续读页脚。
 */
object ReadTool : Tool {
    override val name = "read"
    override val description: String get() = ToolSdk.description("read")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = Args.filePath(args)
        if (raw.isNullOrBlank()) return ToolResult.Error("file_path must be a non-empty string")
        val file = try {
            Args.resolve(ctx, raw)
        } catch (e: Exception) {
            return ToolResult.Error(e.message ?: "bad path")
        }
        val display = Args.display(ctx, file)
        if (!file.exists()) return ToolResult.Error("cannot read \"" + display + "\": not found")
        if (!file.isFile) return ToolResult.Error("cannot read \"" + display + "\": not a regular file")
        if (file.length() > READ_MAX_FILE_BYTES) {
            return ToolResult.Error(
                "cannot read \"" + display + "\": file is " + file.length() + " bytes, larger than this device can load (" + READ_MAX_FILE_BYTES + " bytes)",
            )
        }
        val offsetArg = Args.int(args, "offset")
        if (offsetArg != null && offsetArg < 1) return ToolResult.Error("offset must be a positive integer")
        val limitArg = Args.int(args, "limit")
        if (limitArg != null && limitArg < 1) return ToolResult.Error("limit must be a positive integer")
        if (limitArg != null && limitArg > READ_MAX_LINES) {
            return ToolResult.Error("limit must be less than or equal to " + READ_MAX_LINES)
        }
        val offset = offsetArg ?: 1
        val limit = limitArg ?: READ_MAX_LINES
        // dsh 的 readWholeText：先看 NUL（二进制）再严格校验 UTF-8，这两步不过就回 cannot read 错误，
        // 而不是把解码出来的替换字符当正文发出去（见 notTextReason 的注释）。
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return ToolResult.Error("cannot read \"" + display + "\": " + (it.message ?: "read failed"))
        }
        notTextReason(bytes)?.let { return ToolResult.Error("cannot read \"" + display + "\": " + it) }
        val lines = String(bytes, Charsets.UTF_8).lines()
        // dsh 的 offset 越界判据（FS_NOT_FOUND）：只有「空文件 + offset=1」是合法首页。
        // 以前这里安静返回 0 行，调用方分不清「文件是空的」与「offset 写错了」。
        if (offset > lines.size && !(lines.size == 0 && offset == 1)) {
            return ToolResult.Error(
                "offset " + offset + " is out of range for \"" + display + "\" (" + lines.size + " lines)",
            )
        }
        val shaped = readSlice(lines, offset, limit)
        val slice = shaped.lines
        val value = buildJsonObject {
            put("path", display)
            put("offset", offset)
            put(
                "lines",
                buildJsonArray {
                    slice.forEachIndexed { i, line ->
                        add(
                            buildJsonObject {
                                put("number", offset + i)
                                put("text", line)
                            },
                        )
                    }
                },
            )
            put("totalLines", lines.size)
        }
        return ToolResult.Ok(readEnvelope(display, offset, slice, lines.size, shaped.truncatedByBytes), value)
    }
}

/** dsh 的 formatReadOutput：编号行 + 空行 + 页脚，整段包在 <content> 里 */
internal fun readEnvelope(
    display: String,
    offset: Int,
    slice: List<String>,
    totalLines: Int,
    truncatedByBytes: Boolean = false,
): String {
    val endLine = if (slice.isEmpty()) maxOf(0, offset - 1) else offset + slice.size - 1
    val footer = when {
        // dsh 的三条页脚：字节预算用完 / 还有后续行 / 到底了
        truncatedByBytes ->
            "(Output capped. Showing lines " + offset + "-" + endLine + ". Use offset=" + (endLine + 1) + " to continue.)"
        endLine < totalLines ->
            "(Showing lines " + offset + "-" + endLine + " of " + totalLines + ". Use offset=" + (endLine + 1) + " to continue.)"
        else -> "(End of file - total " + totalLines + " lines)"
    }
    val body = if (slice.isEmpty()) {
        footer
    } else {
        slice.mapIndexed { i, text -> (offset + i).toString() + ": " + text }.joinToString("\n") + "\n\n" + footer
    }
    return "<path>" + display + "</path>\n<type>file</type>\n<content>\n" + body + "\n</content>"
}

/**
 * dsh 的 truncateLine：单行超过 [READ_MAX_LINE_CHARS] 个字符就截断并标注。
 *
 * 为什么必须有（第 197 轮真机复现的 OOM）：ADSH 的 read 原来只限**行数**，一行可以有几 MB
 * —— 那个字符串会一路进日志、进上下文、进 Compose 的文本排版（Android 的文本测量按字符数
 * 分配数组），实测把 app 的 256MB Java 堆打爆。dsh 的 read 一直是 2000 字符 + 50 KiB 两条上限。
 */
internal fun truncateReadLine(line: String): String =
    if (line.length > READ_MAX_LINE_CHARS) {
        line.substring(0, READ_MAX_LINE_CHARS) + "... (line truncated to " + READ_MAX_LINE_CHARS + " chars)"
    } else {
        line
    }

/** 一次 read 整形后的正文行 + 有没有因为字节预算被截 */
internal data class ReadSlice(val lines: List<String>, val truncatedByBytes: Boolean)

/**
 * dsh 的 read 输出整形：先按 [limit] 截行数，再按 [READ_MAX_BYTES] 的字节预算逐行累加
 * （dsh 的 scan：预算用完就停，剩下的行只计数不输出），单行先过 [truncateReadLine]。
 *
 * 行号前缀按 dsh 不计入字节预算（它是渲染期才拼上的）。
 */
internal fun readSlice(lines: List<String>, offset: Int, limit: Int): ReadSlice {
    val out = ArrayList<String>(minOf(limit, 256))
    var used = 0L
    var capped = false
    var index = offset - 1
    while (index < lines.size && out.size < limit) {
        val text = truncateReadLine(lines[index])
        val size = text.toByteArray(Charsets.UTF_8).size + 1L
        if (used + size > READ_MAX_BYTES) {
            capped = true
            break
        }
        used += size
        out += text
        index++
    }
    return ReadSlice(out, capped)
}

/**
 * read_image：把一张图片交给模型（dsh-tool-fs 的 read_image）。
 *
 * 与 dsh 的差别（有意，写在报告里）：
 *  - dsh 的图片由 attachments 服务归一化（16-bit PNG → 8-bit sRGB、超限则缩放）；ADSH 落的是
 *    **逐字节相同的副本**（不做格式归一化），请求装配时再按 dsh 的预算缩到 640k 像素 / 1 MiB；
 *  - dsh 的副本存在自己的附件仓库里；ADSH 存在 `<工作区>/.adsh/attachments/<会话 id>/`，
 *    与用户附件同一个位置（随会话删除）。
 * 其余逐字对齐：扩展名白名单、魔数嗅探、字节/边长/像素上限的报错文案、信封格式与
 * 「模型必须声明 image 输入」的闸门。
 */
object ReadImageTool : Tool {
    override val name = "read_image"
    override val description: String get() = ToolSdk.description("read_image")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = Args.filePath(args)
        if (raw.isNullOrBlank()) return ToolResult.Error("file_path must be a non-empty string")
        // dsh 的 extname(args.file_path).toLowerCase()：没有扩展名（extname 为空串）是允许的 —— 走魔数嗅探
        val dot = raw.lastIndexOf('.')
        val slash = raw.lastIndexOf('/')
        // 结尾是点（"shot."）不算扩展名，与 Node 的 extname 一致（那种路径走魔数嗅探）
        val extension = if (dot > slash && dot < raw.length - 1) raw.substring(dot).lowercase() else ""
        val declared = READ_IMAGE_EXTENSIONS[extension]
        if (declared == null && extension.isNotEmpty()) {
            return ToolResult.Error(
                "cannot read \"" + raw + "\": the " + extension + " extension does not declare a supported " +
                    "image format; read_image accepts PNG/JPEG/WebP/GIF files, including extension-less files " +
                    "in those formats",
            )
        }
        val file = try {
            Args.resolve(ctx, raw)
        } catch (e: Exception) {
            return ToolResult.Error(e.message ?: "bad path")
        }
        val display = Args.display(ctx, file)
        if (!file.exists()) return ToolResult.Error("cannot read \"" + display + "\": not found")
        if (!file.isFile) return ToolResult.Error("cannot read \"" + display + "\": not a regular file")
        // dsh 的 assertImageCapableRoute：模型没声明 image 输入时**不静默降级**，直接报错让模型换模型
        if (!ctx.imageCapable) {
            return ToolResult.Error(
                "cannot read \"" + display + "\" as an image: model \"" + ctx.modelId +
                    "\" does not declare image input; switch to an image-capable model to read images",
            )
        }
        val directory = ctx.attachmentDir
            ?: return ToolResult.Error(
                "cannot read \"" + display + "\" as an image: no attachment store is available",
            )
        // dsh 的 byteCap = min(maxImageBytes, maxMessageImageBytes)
        if (file.length() > READ_IMAGE_MAX_BYTES) {
            return ToolResult.Error(
                "cannot read \"" + display + "\": the image cannot be stored within the deployment's byte " +
                    "limits; downscale the image and read the smaller copy",
            )
        }
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return ToolResult.Error("cannot read \"" + display + "\": " + (it.message ?: "read failed"))
        }
        // 不用 imageMediaTypeOf：那是**用户附件**的口径（BMP/HEIC/AVIF 也认，因为设备解得出）。
        // read_image 按 dsh 只认 PNG/JPEG/WebP/GIF 四种 —— 说明里也是这么写的。
        val sniffed = sniffSupportedImage(bytes)
        val mediaType = declared ?: sniffed
            ?: return ToolResult.Error(
                "cannot read \"" + display + "\": the file content is not a supported image format; " +
                    "read_image accepts PNG/JPEG/WebP/GIF",
            )
        if (declared != null && sniffed != null && sniffed != declared) {
            return ToolResult.Error(
                "cannot read \"" + display + "\": the " + extension + " extension declares " + declared +
                    ", but the bytes use a different image format; rename the file to match its actual format " +
                    "if it is PNG/JPEG/WebP/GIF, or convert it to one of those formats",
            )
        }
        val bounds = imageBounds(file)
            ?: return ToolResult.Error(
                "cannot read \"" + display + "\": the bytes do not decode as a supported PNG/JPEG/WebP/GIF " +
                    "image; the file may be truncated or corrupt",
            )
        val (width, height) = bounds
        if (width > READ_IMAGE_MAX_DIMENSION || height > READ_IMAGE_MAX_DIMENSION) {
            return ToolResult.Error(
                "cannot read \"" + display + "\": at least one image side exceeds the " +
                    READ_IMAGE_MAX_DIMENSION + "px limit; downscale the image and read the smaller copy",
            )
        }
        if (width.toLong() * height.toLong() > READ_IMAGE_MAX_PIXELS) {
            return ToolResult.Error(
                "cannot read \"" + display + "\": the image exceeds the " + READ_IMAGE_MAX_PIXELS +
                    "-pixel decoded-size limit; downscale the image and read the smaller copy",
            )
        }
        val attachment = admitToolImage(file, mediaType, directory, file.name)
            ?: return ToolResult.Error(
                "cannot read \"" + display + "\": the image could not be copied into the session " +
                    "attachment store",
            )
        // 模型实际收到的是请求变体（dsh 的 requestImageDimensions / REQUEST_IMAGE_MAX_PIXELS）：
        // 信封里的尺寸写它，缩过就把源尺寸当 originalDimensions ——「坐标要乘多少」才是对的。
        val (requestWidth, requestHeight) = requestImageDimensions(width, height, REQUEST_IMAGE_MAX_PIXELS)
        val envelope = toolImageEnvelope(
            display = display,
            mediaType = mediaType,
            bytes = attachment.bytes,
            width = requestWidth,
            height = requestHeight,
            originalWidth = width,
            originalHeight = height,
        )
        val value = buildJsonObject {
            put("path", display)
            put(
                "image",
                buildJsonObject {
                    // dsh 的 attachmentId = "sha256:<hex>"
                    put("attachmentId", "sha256:" + attachment.sha256)
                    put("mediaType", attachment.mediaType)
                    put("bytes", attachment.bytes)
                    put("width", width)
                    put("height", height)
                    put("name", attachment.name)
                },
            )
        }
        return ToolResult.Ok(envelope, value, listOf(com.adsh.app.core.agent.ToolImage(attachment, envelope)))
    }
}

/** dsh-tool-fs 的 IMAGE_EXTENSIONS（大写键在工具里 lowercase 之后再查） */
private val READ_IMAGE_EXTENSIONS = mapOf(
    ".png" to "image/png",
    ".jpg" to "image/jpeg",
    ".jpeg" to "image/jpeg",
    ".webp" to "image/webp",
    ".gif" to "image/gif",
)

/** dsh 的 attachments.imageLimits 默认值：maxImageBytes = 20971520 */
private const val READ_IMAGE_MAX_BYTES = 20L * 1024 * 1024

/** dsh 的 attachments.imageLimits 默认值：maxImagePixels = 64e6 */
private const val READ_IMAGE_MAX_PIXELS = 64_000_000L

/** dsh 的 attachments.imageLimits 默认值：maxImageDimension = 8192 */
private const val READ_IMAGE_MAX_DIMENSION = 8192

/**
 * dsh 的 sniffImageMediaType（只认签名，不看扩展名）：
 * PNG / JPEG / GIF87a·GIF89a / RIFF….WEBP，其余返回 null。
 */
internal fun sniffSupportedImage(data: ByteArray): String? {
    fun ascii(offset: Int, text: String): Boolean {
        if (offset + text.length > data.size) return false
        text.forEachIndexed { i, c -> if (data[offset + i] != c.code.toByte()) return false }
        return true
    }
    fun bytes(offset: Int, values: IntArray): Boolean {
        if (offset + values.size > data.size) return false
        values.forEachIndexed { i, v -> if (data[offset + i] != v.toByte()) return false }
        return true
    }
    return when {
        bytes(0, intArrayOf(0x89, 0x50, 0x4E, 0x47)) -> "image/png"
        bytes(0, intArrayOf(0xFF, 0xD8)) -> "image/jpeg"
        ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
        ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
        else -> null
    }
}

/** write：创建或整体替换文件（正文是 dsh 的 formatWriteOutput 信封） */
object WriteTool : Tool {
    override val name = "write"
    override val description: String get() = ToolSdk.description("write")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = Args.filePath(args)
        if (raw.isNullOrBlank()) return ToolResult.Error("file_path must be a non-empty string")
        val content = Args.str(args, "content") ?: return ToolResult.Error("content must be a string")
        val mode = try {
            resolveCallMode(
                args,
                ctx,
                EscalationAsk(toolName = "write", callId = ctx.callId, subject = "operation", detail = raw),
            )
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return ToolResult.Error(t.message ?: "sandbox escalation failed")
        }
        val file = when (val gate = Args.gateWrite(mode, ctx, raw)) {
            is Args.WriteGate.Denied -> return ToolResult.Error(gate.message)
            is Args.WriteGate.Allowed -> gate.file
        }
        val display = Args.display(ctx, file)
        val existed = file.isFile
        val before = if (existed) runCatching { file.readText() }.getOrNull() else null
        // 轮尾的「已编辑 N 个文件」卡片要按轮统计：**改动之前**留一份底（同一路径一轮只留一次）
        com.adsh.app.core.agent.TurnChangeTracker.captureBefore(file.absolutePath)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            val value = buildJsonObject {
                put("path", display)
                put("operation", if (existed) "update" else "create")
                put("before", before?.let { JsonPrimitive(it) } ?: JsonNull)
                put("after", content)
            }
            val head = if (existed) "Updated" else "Created"
            ToolResult.Ok("<path>" + display + "</path>\n<type>file</type>\n<content>\n" + head + " file\n</content>", value)
        }.getOrElse { ToolResult.Error("cannot write \"" + display + "\": " + (it.message ?: "write failed")) }
    }
}


/** edit：字面量替换。校验、报错与成功文案逐字取自 dsh-tool-fs 的 edit + dsh-fs-local。 */
object EditTool : Tool {
    override val name = "edit"
    override val description: String get() = ToolSdk.description("edit")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = Args.filePath(args)
        if (raw.isNullOrBlank()) return ToolResult.Error("file_path must be a non-empty string")
        val old = Args.str(args, "old_string") ?: return ToolResult.Error("old_string must be a non-empty string")
        if (old.isEmpty()) return ToolResult.Error("old_string must be a non-empty string")
        val new = Args.str(args, "new_string") ?: return ToolResult.Error("new_string must be a string")
        if (old == new) return ToolResult.Error("old_string and new_string must differ")
        val replaceAll = Args.bool(args, "replace_all") ?: false
        val mode = try {
            resolveCallMode(
                args,
                ctx,
                EscalationAsk(toolName = "edit", callId = ctx.callId, subject = "operation", detail = raw),
            )
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return ToolResult.Error(t.message ?: "sandbox escalation failed")
        }
        val file = when (val gate = Args.gateWrite(mode, ctx, raw)) {
            is Args.WriteGate.Denied -> return ToolResult.Error(gate.message)
            is Args.WriteGate.Allowed -> gate.file
        }
        val display = Args.display(ctx, file)
        if (!file.isFile) return ToolResult.Error("cannot read \"" + display + "\": not found")
        val rawBytes = runCatching { file.readBytes() }.getOrElse {
            return ToolResult.Error("cannot read \"" + display + "\": " + (it.message ?: "read failed"))
        }
        notTextReason(rawBytes)?.let { return ToolResult.Error("cannot edit \"" + display + "\": " + it) }
        val text = String(rawBytes, Charsets.UTF_8)
        val hits = Regex(Regex.escape(old)).findAll(text).map { it.range.first }.toList()
        if (hits.isEmpty()) return ToolResult.Error("old_string was not found in \"" + display + "\"")
        if (hits.size > 1 && !replaceAll) {
            return ToolResult.Error(
                "old_string matched " + hits.size + " times in \"" + display +
                    "\"; provide a more specific old_string or set replace_all to true",
            )
        }
        val after = if (replaceAll) text.replace(old, new) else text.replaceFirst(old, new)
        // 同上：改动之前留底（本轮第一次碰到这个路径时才真读一次）
        com.adsh.app.core.agent.TurnChangeTracker.captureBefore(file.absolutePath)
        file.writeText(after)
        val value = buildJsonObject {
            put("path", display)
            put("before", text)
            put("after", after)
        }
        val done = if (replaceAll) {
            "The file " + display + " has been updated. All occurrences were successfully replaced."
        } else {
            "The file " + display + " has been updated successfully."
        }
        return ToolResult.Ok(done, value)
    }
}

/**
 * glob：rg --files（dsh 的 buildGlobCommand 逐字）。
 * 顺序是 ripgrep 的 --sort=modified（修改时间），包含隐藏文件、不理会 ignore 文件，
 * 排除版本库元数据目录；路径相对搜索根。
 */
object GlobTool : Tool {
    override val name = "glob"
    override val description: String get() = ToolSdk.description("glob")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = Args.str(args, "pattern") ?: ""
        if (pattern.isBlank()) return ToolResult.Error("pattern must be a non-empty string")
        val pathArg = Args.str(args, "path")
        if (pathArg != null && pathArg.isBlank()) {
            return ToolResult.Error("path must be a non-empty string when given")
        }
        // 默认连目录一起返回（这条是 ADSH 与 dsh 的**有意差异**，
        // 见 ToolSdk 里 glob 的说明：dsh 只给文件，想列目录只能绕回 bash）
        val directories = Args.bool(args, "directories") ?: true
        val base = try {
            Args.resolve(ctx, pathArg)
        } catch (e: Exception) {
            return ToolResult.Error(e.message ?: "bad path")
        }
        if (!File(ctx.runtime.ripgrepPath).isFile) return ToolResult.Error("ripgrep 未随包（librg.so 缺失）")
        // 不再用 rg 的 --sort=modified：顺序改在客户端做（**字典序**）。
        // dsh 用的是 --sort=modified，实测「既不直观也不稳定」——两次调用之间顺序会变，
        // 也没法按路径定位；字典序是确定的、可复现的、和 read 的路径能对上。
        val cmd = mutableListOf(
            ctx.runtime.ripgrepPath, "--files", "--glob=" + pattern,
            "--no-ignore", "--hidden",
        )
        VCS_EXCLUDES.forEach { name ->
            cmd += "--glob=!**/" + name
            cmd += "--glob=!**/" + name + "/**"
        }
        // dsh 的 buildGlobCommand：模型给的路径一律跟在 -- 后面，不会被当成选项
        if (pathArg != null) {
            cmd += "--"
            cmd += base.absolutePath
        }
        // cwd 固定在工作区根：rg 打印的路径跟着 cwd 走，而 dsh 用 toWorkdirRelative 把
        // 结果统一换算成**工作区根相对**路径（dsh-tool-fs-search 的 run.workdir 就是 workdir）。
        // 以前 cwd = 被搜索的那个目录，于是 glob({path:"app/src"}) 返回 "main/java/..." ——
        // 那个路径直接喂给 read 是解析不到的。
        val cwd = ctx.workspace.shellRoot ?: base
        val run = runRipgrep(cmd, cwd)
        if (run.error != null) return ToolResult.Error(run.error)
        val root = pathArg ?: "."
        val files = run.stdout.split("\n").mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            relativeTo(cwd, line)
        }
        // 目录要单独走一遍：rg 的 --files 只列文件（dsh 的 glob 也一样，sdk 里那句
        // "Returns matching file paths — never directories" 就是它的原文），
        // 而「列目录还得绕回 bash」正是要修的那一条。
        val dirs = if (directories) collectMatchingDirectories(base, cwd, pattern) else emptyList()
        val paths = (files + dirs).distinct().sorted()
        val result = globResult(paths, root)
        return ToolResult.Ok(result.text, result.value)
    }
}

/** 目录遍历的访问上限（防止在超大目录树上把一次工具调用拖死） */
private const val GLOB_MAX_WALK = 20_000

/**
 * 收集与 [pattern] 匹配的目录，返回**工作区根相对**路径。
 *
 * rg 的 `--files` 只列文件，目录得自己走一遍。返回的是相对 [cwd]（工作区根）的路径，
 * 与文件那一批口径一致；匹配用的相对路径则是相对 [base]（搜索根）的 ——
 * 这一点与 rg 的 `--glob` 相同：ripgrep 也是把 glob 匹配在「搜索根的相对路径」上。
 *
 * 只排除版本库元数据目录（与 rg 那批的排除项对齐），其它隐藏目录照列（rg 那边开了 --hidden）。
 */
internal fun collectMatchingDirectories(base: File, cwd: File, pattern: String): List<String> {
    val basePath = runCatching { base.canonicalFile.toPath() }.getOrNull() ?: return emptyList()
    val out = ArrayList<String>()
    val queue = ArrayDeque<File>()
    queue.add(base)
    var visited = 0
    while (queue.isNotEmpty() && visited < GLOB_MAX_WALK) {
        val dir = queue.removeFirst()
        visited++
        val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
        for (child in children) {
            if (!child.isDirectory || child.name in VCS_EXCLUDES) continue
            queue.add(child)
            val target = runCatching { child.canonicalFile.toPath() }.getOrNull() ?: continue
            val relToBase = runCatching { basePath.relativize(target).toString() }.getOrNull() ?: continue
            if (matchesGlob(pattern, relToBase.replace(File.separatorChar, '/'))) {
                out += relativeTo(cwd, child.absolutePath)
            }
        }
    }
    return out
}

/**
 * 把一个 glob 模式编译成正则（只覆盖本工具会用到的语法，与 rg 的 --glob 对齐）：
 *  - 模式里**没有 "/"** ⇒ 匹配任意深度的基名（rg 的同名规则，sdk 里也这么写）；
 *  - `*` 段内任意字符、`**` 跨段、`?` 单字符、`[abc]` 字符集（`[!abc]` 取反）、`{a,b}` 择一；
 *  - 双星号后面紧跟一个斜杠时，这一段可以整段消失（所以「双星号 + 斜杠 + *.kt」
 *    也能匹配根目录下的 a.kt）。
 */
internal fun matchesGlob(pattern: String, relativePath: String): Boolean {
    val anchored = pattern.contains('/')
    val body = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                    body.append("(?:.*/)?")
                    i += 3
                    continue
                }
                body.append(".*")
                i += 2
                continue
            }
            c == '*' -> body.append("[^/]*")
            c == '?' -> body.append("[^/]")
            c == '[' -> {
                val end = pattern.indexOf(']', i + 2)
                if (end < 0) {
                    body.append("\\[")
                } else {
                    val set = pattern.substring(i + 1, end)
                    body.append('[').append(if (set.startsWith('!')) "^" + set.substring(1) else set).append(']')
                    i = end
                }
            }
            c == '{' -> {
                val end = pattern.indexOf('}', i + 1)
                if (end < 0) {
                    body.append("\\{")
                } else {
                    val parts = pattern.substring(i + 1, end).split(',').map { Regex.escape(it) }
                    body.append("(?:").append(parts.joinToString("|")).append(')')
                    i = end
                }
            }
            else -> body.append(Regex.escape(c.toString()))
        }
        i++
    }
    val prefix = if (anchored) "^" else "^(?:.*/)?"
    val regex = runCatching { Regex(prefix + body + "$") }.getOrNull() ?: return false
    return regex.matches(relativePath)
}

/** glob 的结果：正文（超上限时带 (Showing N of M paths ...) 页脚）与结构化值 */
internal data class GlobResult(val text: String, val value: JsonObject)

/**
 * glob / grep 结果正文里的**稳定片段**。
 *
 * 渲染与界面解析**共用同一份**：ADSH 的工具行只落库「人看的正文」（QuickJsRuntime 把
 * SubCall.result 存成 outcome.text），界面上的搜索卡是从这段正文**解析回来**的 ——
 * 两处各写一份字面量迟早漂移，那时页脚会被当成一条路径画进卡片里。
 */
internal object SearchOutputText {
    const val NO_FILES = "No files found"
    const val NO_MATCHES = "No matches found"

    /** glob 页脚前缀（后面跟 "N of M paths. " + [GLOB_RECOVERY] + ")"） */
    const val GLOB_PAGE_PREFIX = "(Showing "

    /** glob 截断时那句「完整结果没能保存」（dsh 的恢复说明，ADSH 没有 spill 服务后的等价物） */
    const val GLOB_RECOVERY = "The complete result could not be saved; narrow pattern or path to see more."

    /** grep 头部前缀（"Found N matches" / "Found N of M matches"） */
    const val GREP_FOUND_PREFIX = "Found "

    /** grep 每一行命中前缀（"Line N: 正文"） */
    const val GREP_LINE_PREFIX = "Line "

    /** grep 截断时那句「完整结果没能保存」 */
    const val GREP_RECOVERY = "The complete result could not be saved; narrow pattern, path, or include to see more."
}

/**
 * dsh 的 glob 结果整形：正文是**完整清单**（超上限时补页脚），
 * **结构化值是同一页**（dsh-tool-fs-search 的 glob execute 返回 page.items =
 * paths.slice(0, caps.maxResults)，dsh 的 output.schema 里 paths 就是这一页）。
 *
 * 以前正文截到 100 条、值里却是全量：文档写着的 100 上限在程序里看着没生效 ——
 * 正是「按文档直接信不住」的那一类。
 */
internal fun globResult(paths: List<String>, root: String): GlobResult = GlobResult(
    text = renderGlobPaths(paths),
    value = buildJsonObject {
        put("root", root)
        put("paths", buildJsonArray { paths.take(GLOB_MAX_RESULTS).forEach { add(JsonPrimitive(it)) } })
        // 截断必须**在结构化值里也说得出来**（第 98 轮，测试报告 2.4）：正文里那句
        // "(Showing 100 of 245 paths…)" 在 PTC 模式下根本进不了模型上下文 —— 程序只拿到
        // 这个值。实测反馈就是「调用方无法从返回值判断是否被截断，只能自己记住 100 这个上限」。
        put("truncated", paths.size > GLOB_MAX_RESULTS)
        put("totalPaths", paths.size)
    },
)

/** dsh 的 renderGlobPaths：一条一行；空结果 No files found；超上限给出分页与找回说明 */
internal fun renderGlobPaths(paths: List<String>): String {
    if (paths.isEmpty()) return SearchOutputText.NO_FILES
    if (paths.size <= GLOB_MAX_RESULTS) return paths.joinToString("\n")
    val page = paths.take(GLOB_MAX_RESULTS)
    return page.joinToString("\n") +
        "\n\n" + SearchOutputText.GLOB_PAGE_PREFIX + page.size + " of " + paths.size + " paths. " +
        SearchOutputText.GLOB_RECOVERY + ")"
}

/**
 * grep：rg --json（dsh 的 buildGrepCommand 逐字），按文件分组渲染。
 * 用 --json 而不是靠冒号切分，路径里带冒号也不会解析错。
 */
object GrepTool : Tool {
    override val name = "grep"
    override val description: String get() = ToolSdk.description("grep")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = Args.str(args, "pattern") ?: ""
        if (pattern.isEmpty()) return ToolResult.Error("pattern must be a non-empty string")
        val pathArg = Args.str(args, "path")
        if (pathArg != null && pathArg.isBlank()) {
            return ToolResult.Error("path must be a non-empty string when given")
        }
        val include = Args.str(args, "include")
        if (include != null) {
            if (include.isBlank()) return ToolResult.Error("include must be a non-empty glob when given")
            if (include.startsWith("!")) {
                return ToolResult.Error("include must be a positive glob filter; negated patterns (\"!…\") are not supported")
            }
        }
        val base = try {
            Args.resolve(ctx, pathArg)
        } catch (e: Exception) {
            return ToolResult.Error(e.message ?: "bad path")
        }
        if (!File(ctx.runtime.ripgrepPath).isFile) return ToolResult.Error("ripgrep 未随包（librg.so 缺失）")
        val cmd = mutableListOf(ctx.runtime.ripgrepPath, "--json", "--regexp=" + pattern)
        if (include != null) cmd += "--glob=" + include
        if (pathArg != null) {
            cmd += "--"
            cmd += base.absolutePath
        }
        // 同 glob：cwd 固定在工作区根，路径统一换算成工作区根相对
        val cwd = ctx.workspace.shellRoot ?: base
        val run = runRipgrep(cmd, cwd)
        if (run.error != null) return ToolResult.Error(run.error)
        // **正文与值是两份不同的东西**（dsh-tool-fs-search 的 render 与 execute 分开做）：
        //  - 值给程序用，是完整的行（dsh 的 execute 直接返回 { matches: all }，不截断、也不切行）；
        //  - 正文给人看，单行按 grepMaxLineBytes 截到 2000 字节、整体按 250 条截断
        //    （dsh 只在 render 里调 retainGrepMatches / previewLine）。
        // 以前把 previewLine 也套在值上，程序拿到的是被切过的行 —— 与 dsh 不一致。
        val matches = parseGrepMatches(run.stdout).map { it.copy(path = relativeTo(cwd, it.path)) }
        val result = grepResult(matches)
        return ToolResult.Ok(result.text, result.value)
    }
}

/** 一条 grep 命中（dsh 的 GrepMatch） */
internal data class GrepMatch(val path: String, val lineNumber: Int, val line: String)

/** grep 的结果：正文（截条数 + 截单行）与结构化值 */
internal data class GrepResult(val text: String, val value: JsonObject)

/**
 * dsh 的 grep 结果整形。**条数上限两边都生效**，单行截断只作用在正文上。
 *
 * dsh 的原始分工是：execute 返回全部匹配（值是全量、不截条数、也不切行）；
 * 250 条 / 单行 2000 字节的两条上限只写在它的 render 里（retainGrepMatches / previewLine）。
 *
 * ADSH 在**条数**上刻意与 dsh 不同：值也封顶在 250。理由有两条 ——
 *  1. dsh 的页脚是「完整结果已存到 <路径>，用 read/grep 去取」，它背后有 spill 服务；
 *     ADSH 没有落盘能力，页脚只能写「完整结果无法保存」—— 也就是说超过 250 条的部分
 *     **本来就取不回来**，留在值里只会让程序把它整个 return 出去、灌满上下文；
 *  2. 上一轮的实测反馈就是「250 的上限实测没生效」：在 PTC 模式下，正文根本不进模型上下文，
 *     只有程序 return 的东西进 —— 上限只写在正文上等于没有上限。
 *
 * **单行也一起截（第 197 轮改的，与 dsh 有意不同）**：值原来保留完整行（dsh 的口径），
 * 但那条完整行要跨进程交给 :ptc 里的程序，而手机主进程的 Java 堆只有 256MB —— 真机实测：
 * 一个 5MB 单行文件的 grep，值在两端各留几份，把堆顶到 256MB 的 growth limit、app 闪退
 * （同一份程序在 dsh 桌面端跑得动：V8 的堆大得多，而且它的值只在自己进程里）。
 * 现在值与正文用**同一个** previewLine（2000 字节 + 「 (line truncated)」），并给被截过的命中
 * 补一个 truncated 标记 —— 程序据此知道「这一行没看全」，需要全文就自己用 bash 处理文件。
 * 顺带的好处：值与正文不再各说各话（dsh 那边正文截、值不截，模型与程序看到的是两份东西）。
 */
internal fun grepResult(matches: List<GrepMatch>): GrepResult = GrepResult(
    text = renderGrep(matches.map { it.copy(line = previewLine(it.line)) }),
    value = buildJsonObject {
        put("matches", buildJsonArray {
            matches.take(GREP_MAX_MATCHES).forEach { match ->
                add(
                    buildJsonObject {
                        put("path", match.path)
                        put("lineNumber", match.lineNumber)
                        put("line", previewLine(match.line))
                        // 只在这条真的被截了时才写：程序用它区分「行本来就短」与「被截过」
                        if (match.line.toByteArray(Charsets.UTF_8).size > GREP_MAX_LINE_BYTES) {
                            put("truncated", true)
                        }
                    },
                )
            }
        })
        // 同 glob：`matches.size == 250` 与「正好只有 250 条命中」在值上分不开，
        // 只有把截断与总数写出来，程序才知道该不该收窄（第 98 轮，测试报告 2.4）
        put("truncated", matches.size > GREP_MAX_MATCHES)
        put("totalMatches", matches.size)
    },
)

/** dsh 的 parseGrepMatches：只吃 rg --json 的 match 记录 */
internal fun parseGrepMatches(stdout: String): List<GrepMatch> {
    val out = ArrayList<GrepMatch>()
    stdout.lineSequence().forEach { line ->
        if (line.isEmpty()) return@forEach
        val record = runCatching { toolJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
        if (record["type"]?.jsonPrimitive?.contentOrNull != "match") return@forEach
        val data = record["data"]?.jsonObject ?: return@forEach
        val path = data["path"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return@forEach
        val number = data["line_number"]?.jsonPrimitive?.intOrNull ?: return@forEach
        val lines = data["lines"]?.jsonObject ?: return@forEach
        val text = lines["text"]?.jsonPrimitive?.contentOrNull
        val body = text?.replace(Regex("\\r?\\n$"), "") ?: "(line is not valid UTF-8)"
        out += GrepMatch(path, number, body)
    }
    return out
}

/** dsh 的 previewLine：单行超过 grepMaxLineBytes 就截断并标注 */
internal fun previewLine(line: String): String {
    val bytes = line.toByteArray(Charsets.UTF_8)
    if (bytes.size <= GREP_MAX_LINE_BYTES) return line
    var cut = GREP_MAX_LINE_BYTES
    while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
    return String(bytes, 0, cut, Charsets.UTF_8) + " (line truncated)"
}

/** dsh 的 formatRetainedGrep + formatGrepMatches：Found N matches + 按文件分组 */
internal fun renderGrep(matches: List<GrepMatch>): String {
    if (matches.isEmpty()) return SearchOutputText.NO_MATCHES
    val retained = matches.take(GREP_MAX_MATCHES)
    val header = if (retained.size < matches.size) {
        SearchOutputText.GREP_FOUND_PREFIX + retained.size + " of " + matches.size + " matches"
    } else {
        SearchOutputText.GREP_FOUND_PREFIX + matches.size + " " + (if (matches.size == 1) "match" else "matches")
    }
    val sections = ArrayList<String>()
    val byFile = LinkedHashMap<String, MutableList<GrepMatch>>()
    retained.forEach { match -> byFile.getOrPut(match.path) { ArrayList() }.add(match) }
    byFile.forEach { (path, group) ->
        sections += path + "\n" + group.joinToString("\n") { SearchOutputText.GREP_LINE_PREFIX + it.lineNumber + ": " + it.line }
    }
    val body = header + "\n\n" + sections.joinToString("\n\n")
    if (retained.size == matches.size) return body
    return body + "\n\n(" + SearchOutputText.GREP_RECOVERY + ")"
}

/** 一次 ripgrep 调用（dsh 走 subprocess seam，这里直接起进程） */
internal data class RipgrepRun(val stdout: String, val error: String?)

internal fun runRipgrep(cmd: List<String>, workdir: File): RipgrepRun {
    // 工作目录必须是**目录**：模型可以把 path 指到一个文件（rg 本身允许，dsh 的 subprocess seam 也是
    // 直接起进程），这时 ProcessBuilder.directory(文件) 会直接抛 IOException
    // （Cannot run program …: Not a directory），看着像「ripgrep 启动失败」。
    // 落到父目录即可，被搜的路径本身仍然是那个文件。
    val cwd = if (workdir.isDirectory) workdir else (workdir.parentFile ?: workdir)
    val process = try {
        // stdin = /dev/null：rg 没给路径时会去读 stdin，接上 App 那根永不关闭的管道就是 30s 超时
        ProcessBuilder(cmd).directory(cwd).redirectErrorStream(false)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
    } catch (e: Exception) {
        return RipgrepRun("", "ripgrep 启动失败：" + e::class.java.simpleName + "：" + (e.message ?: "unknown"))
    }
    // stdout 边读边记账（dsh 的 RAW_OUTPUT_MAX_BYTES = 20_000_000）：超了整次失败，不截断 ——
    // 那说明 pattern/path 太宽。**读的时候就设卡**：先 readText() 把几十 MB 读进内存再判断，
    // 手机主进程的 Java 堆就已经吃不消了（第 197 轮：5MB 单行的 grep 值把 app 推到 OOM）。
    val stdout = StringBuilder()
    val buffer = CharArray(8192)
    var stdoutBytes = 0L
    var overflow = false
    runCatching {
        process.inputStream.bufferedReader().use { reader ->
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                stdout.append(buffer, 0, n)
                stdoutBytes += String(buffer, 0, n).toByteArray(Charsets.UTF_8).size
                if (stdoutBytes > GREP_RAW_MAX_BYTES) {
                    overflow = true
                    break
                }
            }
        }
    }
    val stderr = process.errorStream.bufferedReader().readText()
    if (overflow) {
        process.destroyForcibly()
        // dsh 的 SEARCH_RAW_OUTPUT_OVERFLOW 文案逐字
        return RipgrepRun(
            "",
            "grep produced " + stdoutBytes + " bytes of raw output, over the " + GREP_RAW_MAX_BYTES +
                "-byte cap; narrow pattern, path, or include and retry",
        )
    }
    val finished = process.waitFor(30, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return RipgrepRun("", "grep 超时（30s）")
    }
    val exit = process.exitValue()
    // rg 的退出码：0 = 有命中，1 = 没有命中，2 = 出错
    if (exit > 1) return RipgrepRun("", "ripgrep 失败：\n" + stderr.trim())
    return RipgrepRun(stdout.toString(), null)
}

/** 把 rg 打印的路径换算成搜索根下的相对路径（dsh 的 toWorkdirRelative） */
internal fun relativeTo(root: File, raw: String): String {
    val text = raw.removePrefix("./")
    val file = File(text)
    if (!file.isAbsolute) return text.replace(File.separatorChar, '/')
    val base = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return text
    val target = runCatching { file.canonicalFile.toPath() }.getOrNull() ?: return text
    val rel = runCatching { base.relativize(target).toString() }.getOrElse { return text }
    return rel.replace(File.separatorChar, '/')
}

object ToolRegistry {
    val all: List<Tool> = listOf(
        BashTool, ReadTool, ReadImageTool, WriteTool, EditTool, GlobTool, GrepTool,
        TodoTool, PresentTool, WebSearchTool, WebFetchTool, AskUserTool,
        ExitPlanModeTool,
        JobOutputTool, JobListTool, JobKillTool,
        RunCodeTool,
    )

    /** run_code 程序内可调用的工具（不含 run_code 自身，避免递归） */
    val bindings: List<Tool> by lazy { all.filter { it.name != "run_code" } }

    fun byName(name: String): Tool? = all.firstOrNull { it.name == name }
}
