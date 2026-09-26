package com.adsh.app.core.tools

import com.adsh.app.core.agent.REQUEST_IMAGE_MAX_PIXELS
import com.adsh.app.core.agent.admitToolImage
import com.adsh.app.core.agent.imageBounds
import com.adsh.app.core.agent.requestImageDimensions
import com.adsh.app.core.agent.toolImageEnvelope
import com.adsh.app.core.workspace.Workspace
import com.adsh.app.runtime.termux.ExecResult
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
     */
    val onSubCall: ((com.adsh.app.core.ptc.SubCall) -> Unit)? = null,
    /**
     * PTC 子调用「开始」的实时回调（dsh 的 tool/call 事件）。
     *
     * dsh 里每个子调用都是独立的一条 timeline 记录：先有 call（行上带运行扫光），
     * 再有 result（扫光停、摘要换成结果）。我们原来只在跑完时回调一次，
     * 于是组合工具下面的子行**从来不会处在运行态**，扫光也就永远不出现。
     * 两个回调用同一个 id（[com.adsh.app.core.ptc.SubCall.id]）配对，界面把「结束」贴回同一行。
     */
    val onSubCallStart: ((com.adsh.app.core.ptc.SubCall) -> Unit)? = null,
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

/** 工具共用的小工具方法 */
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
     * workspace-write 下**除了工作区之外**还可以写的根：临时目录 + $ADSH_SCRATCH。
     *
     * dsh 的 writableRoots 是「工作区 + /tmp + os.tmpdir()」（dsh-sandbox/roots）。
     * ADSH 多一个 scratch（$HOME/scratch）：系统提示词的 android-termux 段明确让模型把
     * 装依赖 / 构建 / git 这些「要真实文件系统」的活放到 $ADSH_SCRATCH 下做，而它不在
     * dsh 的那三个根里 —— 于是围栏（bash 的 shim 与 write/edit 的 gateWrite）会把模型
     * 按提示词做的事全部拒掉（实测：写 $HOME/scratch 报 file access denied）。
     * 提示词承诺可写、围栏就必须可写，两边口径只能有一个；这条已写进报告。
     */
    fun writableRoots(ctx: ToolContext): List<File> = listOfNotNull(
        runCatching { ctx.runtime.tmpDir() }.getOrNull(),
        runCatching { File(System.getProperty("java.io.tmpdir") ?: "/tmp") }.getOrNull(),
        runCatching { ctx.runtime.scratchDir() }.getOrNull(),
    )

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
        if (ctx.workspace.shellRoot == null) {
            return ToolResult.Error("当前工作区为 SAF 引用形态，bash 不可用（请绑定真实路径工作区）")
        }
        // dsh 的 clampTimeout：模型没给 timeoutMs 就用设置里的默认值，给了就夹在 (0, maxTimeoutMs]
        val ceiling = ctx.bashMaxTimeoutMs.coerceAtLeast(1L)
        val timeout = (timeoutArg?.toLong() ?: ctx.bashTimeoutMs).coerceIn(1L, ceiling)
        // dsh 的参数名是 workdir；老会话里的 cwd 继续认
        val workdirArg = Args.str(args, "workdir") ?: Args.str(args, "cwd")
        val workdir = if (workdirArg != null) Args.resolve(ctx, workdirArg) else ctx.workspace.shellRoot
        // 写围栏（dsh 的 fs-sandbox fence）：workspace-write 下把「工作区外只读」交给
        // LD_PRELOAD 的 libadshfence.so 判决（原生方案，没有 proot 的 ptrace 开销）。
        // 拒绝时 shim 往 stderr 打 dsh 的两行标记，模型据此带 sandbox_permissions 申请一次升级，
        // App 就弹审批卡。read-only 挂的是**空白名单**（命令照跑、任何写都被拒）；
        // danger-full-access 不挂（连模式都不设，fenceEnv 返回空）。
        val fenceMark = File(ctx.runtime.tmpDir(), ".adsh-fence-alive")
        // 可写根：workspace-write 才是「工作区 + 临时目录 + $ADSH_SCRATCH」；read-only 一个都不给。
        val fence = ctx.runtime.fenceEnv(
            mode = mode,
            roots = if (mode == Escalation.READ_ONLY) emptyList() else listOfNotNull(
                ctx.workspace.shellRoot,
                runCatching { ctx.runtime.tmpDir() }.getOrNull(),
                runCatching { File(System.getProperty("java.io.tmpdir") ?: "/tmp") }.getOrNull(),
                runCatching { ctx.runtime.scratchDir() }.getOrNull(),
            ),
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
        val result = ctx.runtime.run(
            command,
            workspaceRoot = workdir,
            timeoutMs = timeout,
            maxOutputBytes = ctx.bashMaxOutputBytes,
            separateStreams = true,
            extraEnv = fence.takeIf { it.isNotEmpty() },
        )
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
        val stdout = clean(streamText(result.output, result.truncated), ctx)
        val stderr = clean(streamText(result.stderr, result.stderrTruncated), ctx)
        val value = buildJsonObject {
            put("kind", "foreground")
            put("exitCode", if (result.timedOut) JsonNull else JsonPrimitive(result.exitCode))
            put("signal", JsonNull)
            put("timedOut", result.timedOut)
            put("aborted", false)
            put("timeoutMs", timeout)
            put("stdout", buildJsonObject {
                put("text", result.output)
                put("truncated", result.truncated)
            })
            put("stderr", buildJsonObject {
                put("text", result.stderr)
                put("truncated", result.stderrTruncated)
            })
        }
        // dsh 的 bash：非零退出**不是**工具错误（isError = false），模型自己看 [exit code: N] 决定怎么办
        return ToolResult.Ok(renderBash(result, stdout, stderr), value)
    }
}

/**
 * dsh 的 streamText：输出被截断时补一行说明（dsh 会把完整输出落盘并给出路径，
 * 这里没有落盘，所以路径写 (unavailable)，与 dsh 缺 spill 时逐字一致）。
 */
private fun streamText(text: String, truncated: Boolean): String =
    if (!truncated) text else text + "\n[output truncated; full output: (unavailable)]"

/**
 * dsh 的 renderResult：stdout，然后是有 [stderr] 头的 stderr 段，最后是状态标记
 * （超时 / 退出码），没有任何输出时正文是 (no output)。
 */
private fun renderBash(result: ExecResult, stdout: String, stderr: String): String {
    var body = stdout
    if (stderr.isNotEmpty()) {
        if (body.isNotEmpty() && !body.endsWith("\n")) body += "\n"
        body += "[stderr]\n" + stderr
    }
    if (body.isEmpty()) body = "(no output)"
    val markers = ArrayList<String>()
    if (result.timedOut) markers += "[timed out after " + result.timeoutMs + "ms]"
    if (markers.isEmpty() && result.exitCode != 0) markers += "[exit code: " + result.exitCode + "]"
    if (markers.isEmpty()) return body
    if (!body.endsWith("\n")) body += "\n"
    return body + markers.joinToString("\n")
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
        val slice = lines.drop(offset - 1).take(limit)
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
        return ToolResult.Ok(readEnvelope(display, offset, slice, lines.size), value)
    }
}

/** dsh 的 formatReadOutput：编号行 + 空行 + 页脚，整段包在 <content> 里 */
internal fun readEnvelope(display: String, offset: Int, slice: List<String>, totalLines: Int): String {
    val endLine = if (slice.isEmpty()) maxOf(0, offset - 1) else offset + slice.size - 1
    val footer = if (endLine < totalLines) {
        "(Showing lines " + offset + "-" + endLine + " of " + totalLines + ". Use offset=" + (endLine + 1) + " to continue.)"
    } else {
        "(End of file - total " + totalLines + " lines)"
    }
    val body = if (slice.isEmpty()) {
        footer
    } else {
        slice.mapIndexed { i, text -> (offset + i).toString() + ": " + text }.joinToString("\n") + "\n\n" + footer
    }
    return "<path>" + display + "</path>\n<type>file</type>\n<content>\n" + body + "\n</content>"
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
        val extension = if (dot > slash && dot >= 0 && dot < raw.length - 1) raw.substring(dot).lowercase() else ""
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
    },
)

/** dsh 的 renderGlobPaths：一条一行；空结果 No files found；超上限给出分页与找回说明 */
internal fun renderGlobPaths(paths: List<String>): String {
    if (paths.isEmpty()) return "No files found"
    if (paths.size <= GLOB_MAX_RESULTS) return paths.joinToString("\n")
    val page = paths.take(GLOB_MAX_RESULTS)
    val recovery = "The complete result could not be saved; narrow pattern or path to see more."
    return page.joinToString("\n") + "\n\n(Showing " + page.size + " of " + paths.size + " paths. " + recovery + ")"
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
 * dsh 的原始分工是：
 *  - `execute` 返回 `{ matches: all }` —— 值是全量、不截条数、也不切行；
 *  - 250 条 / 单行 2000 字节的上限只写在 `render` 里（`retainGrepMatches` / `previewLine`）。
 *
 * ADSH 在**条数**上刻意与 dsh 不同：值也封顶在 250。理由有两条 ——
 *  1. dsh 的页脚是「完整结果已存到 <路径>，用 read/grep 去取」，它背后有 spill 服务；
 *     ADSH 没有落盘能力，页脚只能写「完整结果无法保存」—— 也就是说超过 250 条的部分
 *     **本来就取不回来**，留在值里只会让程序把它整个 return 出去、灌满上下文；
 *  2. 上一轮的实测反馈就是「250 的上限实测没生效」：在 PTC 模式下，正文根本不进模型上下文，
 *     只有程序 return 的东西进 —— 上限只写在正文上等于没有上限。
 *
 * 单行仍然是「值里完整、正文里截断」：值给程序做判断，正文给人看，
 * 这一条与 dsh 一致（dsh 的 previewLine 也只在 render / spill 里用）。
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
                        put("line", match.line)
                    },
                )
            }
        })
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
    if (matches.isEmpty()) return "No matches found"
    val retained = matches.take(GREP_MAX_MATCHES)
    val header = if (retained.size < matches.size) {
        "Found " + retained.size + " of " + matches.size + " matches"
    } else {
        "Found " + matches.size + " " + (if (matches.size == 1) "match" else "matches")
    }
    val sections = ArrayList<String>()
    val byFile = LinkedHashMap<String, MutableList<GrepMatch>>()
    retained.forEach { match -> byFile.getOrPut(match.path) { ArrayList() }.add(match) }
    byFile.forEach { (path, group) ->
        sections += path + "\n" + group.joinToString("\n") { "Line " + it.lineNumber + ": " + it.line }
    }
    val body = header + "\n\n" + sections.joinToString("\n\n")
    if (retained.size == matches.size) return body
    return body + "\n\n(The complete result could not be saved; narrow pattern, path, or include to see more.)"
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
        ProcessBuilder(cmd).directory(cwd).redirectErrorStream(false).start()
    } catch (e: Exception) {
        return RipgrepRun("", "ripgrep 启动失败：" + e::class.java.simpleName + "：" + (e.message ?: "unknown"))
    }
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val finished = process.waitFor(30, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return RipgrepRun("", "grep 超时（30s）")
    }
    val exit = process.exitValue()
    // rg 的退出码：0 = 有命中，1 = 没有命中，2 = 出错
    if (exit > 1) return RipgrepRun("", "ripgrep 失败：\n" + stderr.trim())
    return RipgrepRun(stdout, null)
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
        RunCodeTool,
    )

    /** run_code 程序内可调用的工具（不含 run_code 自身，避免递归） */
    val bindings: List<Tool> by lazy { all.filter { it.name != "run_code" } }

    fun byName(name: String): Tool? = all.firstOrNull { it.name == name }
    val names: List<String> get() = all.map { it.name }
}
