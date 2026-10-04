package com.adsh.app.core.tools

import com.adsh.app.core.jobs.Jobs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 后台任务的三个模型侧工具（dsh-tool-jobs 的 job_output / job_list / job_kill）。
 *
 * 注册表在 [Jobs] 里；这里只做「模型面」：参数校验、把一次读渲染成模型看的正文、把投影裁成
 * 公开形状（owner / 环坐标 / limit 都不进模型 —— dsh 的 PUBLIC_JOB_SCHEMA 就是这两样）。
 */

/** job_output 的等待默认与上限（dsh-tool-jobs 的 waitTimeoutMs / maxWaitTimeoutMs） */
private const val WAIT_DEFAULT_MS = 30_000L
private const val WAIT_MAX_MS = 600_000L

/**
 * 一次消费读的正文（dsh 的 renderModelDelta）：与前台结果**同形** —— stdout、一个 [stderr] 段；
 * 掉字节（游标落在保留窗口之前，或生产者在块上打了 gap）时补 dsh 的丢弃提示，
 * 并把**全文 spill 文件的路径**一起给出来（第 185 轮补上 spill 之前这里永远是 (unavailable)）。
 */
internal fun renderJobDelta(
    chunks: List<Jobs.Chunk>,
    lossy: Boolean,
    spillPaths: List<String> = emptyList(),
): String {
    val visible = chunks.filter { it.channel != Jobs.Channel.LOG }
    val out = visible.filter { it.channel != Jobs.Channel.STDERR }.joinToString("") { it.text }
    val err = visible.filter { it.channel == Jobs.Channel.STDERR }.joinToString("") { it.text }
    val separator = if (out.isNotEmpty() && !out.endsWith("\n")) "\n" else ""
    var body = out + if (err.isNotEmpty()) separator + "[stderr]\n" + err else ""
    if (!lossy && visible.none { it.gapBefore }) return body
    if (body.isNotEmpty() && !body.endsWith("\n")) body += "\n"
    // dsh 原文：没有路径时才是 (unavailable)，有 spill 就把路径列出来（多路用逗号连）
    val where = if (spillPaths.isEmpty()) "(unavailable)" else spillPaths.joinToString(", ")
    return body + "[some output was dropped from memory; full output: " + where + "]"
}

/** 前台命令超时转后台时模型看到的正文（dsh-tool-bash 的 renderPromoted，逐字） */
internal fun renderPromoted(jobId: String, timeoutMs: Long, output: String): String {
    val body = when {
        output.isEmpty() -> ""
        output.endsWith("\n") -> output
        else -> output + "\n"
    }
    return body + "[still running after " + timeoutMs + "ms; moved to background job " + jobId + "]\n" +
        "The command keeps running in the background. You will be notified when it finishes; " +
        "read newer output with job_output, stop it with job_kill."
}

/** dsh 的 PublicJobSnapshot：模型能看到的字段（没有 owner、没有环坐标） */
internal fun publicJob(job: Jobs.View): JsonObject = buildJsonObject {
    put("id", job.id)
    put("kind", job.kind)
    put("label", job.label)
    put("status", job.status.wire)
    job.detail?.let { put("detail", it) }
    put("startedAt", job.startedAt)
    job.finishedAt?.let { put("finishedAt", it) }
}

/** dsh 的 validateJobId：非空字符串，报错带 JSON.stringify 的原值 */
private fun invalidJobId(args: JsonObject): ToolResult.Error {
    val raw = Args.str(args, "job_id")
    val shown = if (raw == null) "undefined" else JsonPrimitive(raw).toString()
    return ToolResult.Error("invalid job_id: expected a non-empty string, got " + shown)
}

/**
 * job_output：读一个后台任务。
 *
 *  - 流式任务只返回**上次读取之后**的新输出（单一消费者游标）；
 *  - `wait: true` 是有界等待（默认 30s、上限 10min）：超时**不是**错误，任务继续跑；
 *  - 正文末尾永远带一行 `[status: ...]`；没有新输出时是 `(no new output)`。
 */
object JobOutputTool : Tool {
    override val name = "job_output"
    override val description: String get() = ToolSdk.description("job_output")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = Args.str(args, "job_id")
        if (id.isNullOrEmpty()) return invalidJobId(args)
        if (Args.bool(args, "wait") == true) {
            val requested = Args.int(args, "timeout_ms")?.toLong() ?: WAIT_DEFAULT_MS
            if (requested <= 0) {
                return ToolResult.Error(
                    "invalid wait timeout: expected a positive number of milliseconds, got " + requested,
                )
            }
            try {
                Jobs.wait(id, minOf(requested, WAIT_MAX_MS), ctx.conversationId)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                return ToolResult.Error(t.message ?: "job wait failed")
            }
        }
        val read = try {
            Jobs.read(id, ctx.conversationId)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return ToolResult.Error(t.message ?: "job read failed")
        }
        val delta = renderJobDelta(read.chunks, read.lossy, read.job.spillPaths)
        // dsh 的 readBody：终局结果（值型任务）接在 delta 后面，只给一次
        val text = if (read.result == null) {
            delta
        } else {
            delta + (if (delta.isNotEmpty() && !delta.endsWith("\n")) "\n" else "") + read.result
        }
        val body = if (text.isEmpty()) "(no new output)" else text
        val separator = if (body.endsWith("\n")) "" else "\n"
        val value = buildJsonObject {
            put("text", text)
            put("job", publicJob(read.job))
        }
        return ToolResult.Ok(body + separator + read.job.statusLine, value)
    }
}

/** job_list：本会话的全部任务（含已结束的），一行一个：`<id> [<kind>] <status> — <label>` */
object JobListTool : Tool {
    override val name = "job_list"
    override val description: String get() = ToolSdk.description("job_list")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val jobs = Jobs.list(ctx.conversationId)
        val value = buildJsonArray { jobs.forEach { add(publicJob(it)) } }
        val text = if (jobs.isEmpty()) {
            "(no background jobs)"
        } else {
            jobs.joinToString("\n") { it.id + " [" + it.kind + "] " + it.status.wire + " — " + it.label }
        }
        return ToolResult.Ok(text, value)
    }
}

/**
 * job_kill：请求取消一个还在跑的任务。
 *
 * dsh 的账本：**模型自己 kill 的任务结算时不再发完成通知**（这次工具结果就是投递）；
 * 人从界面上按的停止走的是另一条路（不认领投递，通知照发）。
 */
object JobKillTool : Tool {
    override val name = "job_kill"
    override val description: String get() = ToolSdk.description("job_kill")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = Args.str(args, "job_id")
        if (id.isNullOrEmpty()) return invalidJobId(args)
        val reason = Args.str(args, "reason")?.takeIf { it.isNotBlank() }
        val requested = try {
            Jobs.kill(id, ctx.conversationId, reason)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return ToolResult.Error(t.message ?: "job kill failed")
        }
        if (requested) Jobs.markKilledByModel(id)
        val job = try {
            Jobs.get(id, ctx.conversationId)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return ToolResult.Error(t.message ?: "job lookup failed")
        }
        val text = if (requested) {
            "requested cancellation of job " + job.id
        } else {
            "job " + job.id + " had already finished " + job.statusLine
        }
        val value = buildJsonObject {
            put("outcome", if (requested) "cancellation-requested" else "already-finished")
            put("job", publicJob(job))
        }
        return ToolResult.Ok(text, value)
    }
}
