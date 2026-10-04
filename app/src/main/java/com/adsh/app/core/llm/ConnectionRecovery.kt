package com.adsh.app.core.llm

import kotlinx.coroutines.delay

/**
 * 掉线自动重连的策略 —— **逐条照搬 dsh 的 ConnectionController**（dsh-client 的
 * `lib/types/recovery-config.js` + `lib/types/client/connection.js`）。
 *
 * dsh 那边管的是「浏览器到 Host 的 Gateway 连接」：丢了就以指数退避重开一代，
 * 状态机 `connecting → connected`，UI 由 ConnectionIndicator 呈现（断线 / 连接中 / 已恢复）。
 * ADSH 这边同构的对象是「一次模型请求的 HTTP/SSE 流」：流断了就按同样的退避重发这一步，
 * 状态同样三态。**两者的差别**在恢复语义上，这里必须说清楚：
 *
 *  - dsh 的重连是「新的一代以完整快照开场」（reconnect's first frame is already the whole
 *    truth），断线期间的增量由 Host 侧的 journal 补齐，客户端不保留任何非持久状态；
 *  - ADSH 的模型流没有宿主可以补：重连 = **重新生成这一步**。所以重连时上一次尝试已经
 *    收到的增量必然作废（[ChatEvent.StreamReset]），界面上那半句回答会消失并重新长出来。
 *
 * 数值也逐条取自 dsh 的默认值（`ConnectionRecoveryConfigSchema`）与界面时序
 * （SettingsRoot 的 RECOVERY_CONFIRMATION_MS / CONNECTING_MIN_VISIBLE_MS）：
 *
 * | 名称 | dsh | 这里 |
 * |---|---|---|
 * | backoffBaseMs | 500 | [BACKOFF_BASE_MS] |
 * | backoffFactor | 2 | [BACKOFF_FACTOR] |
 * | backoffMaxMs | 10000 | [BACKOFF_MAX_MS] |
 * | 单次退避 | `cap/2 + random*(cap/2)`（cap = `min(max, base*factor^(attempt-1))`） | [backoffDelayMs] |
 * | 「连接中」最短可见 | 800ms | [CONNECTING_MIN_VISIBLE_MS] |
 * | 「已恢复」停留 | 2000ms | [RECOVERY_CONFIRMATION_MS] |
 *
 * dsh 的客户端循环**没有尝试次数上限**（`maxAttempts = 10` 是 Host 插件那条 SSE 的配置）：
 * 只要这一轮还在跑就无限重试，用户随时可以停。这里也照此办理。
 */
object ConnectionRecovery {

    /** dsh 的 `backoffBaseMs` */
    const val BACKOFF_BASE_MS = 500L

    /** dsh 的 `backoffFactor` */
    const val BACKOFF_FACTOR = 2.0

    /** dsh 的 `backoffMaxMs` */
    const val BACKOFF_MAX_MS = 10_000L

    /** dsh 的 `CONNECTING_MIN_VISIBLE_MS`：比这更短的一次重连看起来就是「闪一下」 */
    const val CONNECTING_MIN_VISIBLE_MS = 800L

    /** dsh 的 `RECOVERY_CONFIRMATION_MS`：「已恢复」停留多久 */
    const val RECOVERY_CONFIRMATION_MS = 2_000L

    /** 退避上限（dsh 的 `backoffCap`）：`min(max, base * factor^(attempt-1))` */
    fun backoffCapMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val grown = BACKOFF_BASE_MS * Math.pow(BACKOFF_FACTOR, exponent.toDouble())
        return if (grown >= BACKOFF_MAX_MS.toDouble()) BACKOFF_MAX_MS else grown.toLong().coerceAtLeast(1L)
    }

    /**
     * 这一次要等多久（dsh 的 `backoffDelay`）：`cap/2 + random*(cap/2)` ——
     * 半个区间固定、半个区间随机（多台设备同时掉线时不会同时回来打服务端）。
     *
     * @param random 0..1 的随机数（单测注入常数用）
     */
    fun backoffDelayMs(attempt: Int, random: Double = Math.random()): Long {
        val half = backoffCapMs(attempt) / 2
        return half + (random.coerceIn(0.0, 1.0) * half).toLong()
    }

    /**
     * 退避等待。**dsh 的 abort 语义**：等待期间用户点了「重试」就立刻返回（`true`），
     * 不等剩下的时间（dsh 的 `retryDelay?.abort(MANUAL_RECONNECT)`）。
     *
     * 切片等待而不是一次性 delay：手动重试信号是一个计数器，切片到了就比对一次。
     */
    suspend fun awaitRetry(delayMs: Long, generationAtStart: Int): Boolean {
        var remaining = delayMs
        while (remaining > 0) {
            val slice = minOf(remaining, RETRY_POLL_MS)
            delay(slice)
            if (ManualReconnect.generation() != generationAtStart) return true
            remaining -= slice
        }
        return false
    }

    /** 手动重试的轮询粒度：点下去到真的重发之间最多迟这么久 */
    private const val RETRY_POLL_MS = 100L

    /** 等网络回来的轮询粒度（系统回调那侧是即时的，这里只是「醒来看看」） */
    private const val NETWORK_POLL_MS = 200L

    /**
     * 断网时挂起，等到「网络回来」或「用户点了重试」为止 —— dsh 的 `ConnectionController.loop()`
     * 里那个等 abort 的分支，以及 `setNetworkAvailable` 的两条出口（网络恢复 → connecting；
     * 手动 → immediateRetry）。
     *
     * 两种出口对调用方是同一件事：**立刻重试，且退避序列归零**（dsh 在两条路径上都写
     * `attempt = 0`），所以这里不返回值。
     */
    suspend fun awaitNetwork(generationAtStart: Int) {
        while (!NetworkAvailability.isAvailable()) {
            delay(NETWORK_POLL_MS)
            if (ManualReconnect.generation() != generationAtStart) return
        }
    }
}

/**
 * 网络可用性（dsh 的 `networkAvailable`）：断网时 [ConnectionRecovery.awaitNetwork] 挂起，
 * 网络一回来立刻放行。
 *
 * 谁写它：Android 那侧的系统回调（[watchNetwork]，在 `AdshApp` 里注册）。
 * 默认 **true** —— 没有回调信息时按「可用」处理，否则不回调的设备会永远停在「断开」。
 */
object NetworkAvailability {
    private val available = java.util.concurrent.atomic.AtomicBoolean(true)

    fun isAvailable(): Boolean = available.get()

    /** 只由网络回调调用（dsh 的 `setNetworkAvailable`） */
    fun set(value: Boolean) {
        available.set(value)
    }
}

/**
 * 「立刻重连」的信号（dsh 的 `connection.reconnect()`：attempt 归零 + 打断退避 + 马上重发）。
 *
 * 指示器本身在 dsh 里就是一个按钮（`ConnectionIndicator`：断开 / 连接中两个状态都可以点，
 * 点了立刻重连）。这里用一个进程级计数器传递这个意图 —— 界面在另一个线程上，而
 * [ConnectionRecovery.awaitRetry] 只需知道「有没有人按过」。
 *
 * 计数器而不是布尔：连按两次也必须各算一次「立刻」（`attempt` 每次都要归零重来）。
 */
object ManualReconnect {
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    fun request() {
        generation.incrementAndGet()
    }

    fun generation(): Int = generation.get()
}
