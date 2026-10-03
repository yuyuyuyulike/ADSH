package com.adsh.app.core.jobs

/**
 * 注册表里那几处**纯判定**（原先散在 [Jobs] 的读写路径与 `settle` 里）。
 *
 * 挑它们的理由都一样：**判错都不崩，只是静默做错事** ——
 * 栅栏判松了 = 一个会话能看到/杀掉另一个会话的后台任务；`detail` 拼错了 = 模型与界面看到的
 * 终局原因少一半（"是谁杀的"就没了）。
 */

/**
 * owner 栅栏（dsh 的 JobRegistry 同款）：带 owner 的任务**只有同会话的调用者**能看/读/杀；
 * `owner == null`（unowned，例如没有会话上下文的调用）**对谁都开**。
 *
 * 这条规则原先在本文件里写了两遍 —— `list` 的过滤条件与 `expect` 的拒绝条件（一个肯定式、
 * 一个否定式），改成两处都调它，免得哪天只改一处。
 *
 * [caller] 为 null = 没有会话的调用者：它看得到 unowned 的，看不到任何有主的。
 */
internal fun visibleTo(owner: Long?, caller: Long?): Boolean = owner == null || owner == caller

/**
 * 结算时记录上的 `detail`：**killed 的终局**把记录里那条 kill 原因并在生产者给的 detail
 * **后面**（生产者的事实在前，"被谁杀的"在后）；其它终局一律原样用生产者给的。
 *
 * "跑赢了 kill"指的就是这里：生产者自己正常退出（completed / failed）时，即使这期间有人
 * 请求过 kill（`killReason` 有值），终局也不带它 —— 那次 kill 没有真的决定结果。
 */
internal fun mergedDetail(status: Jobs.Status, producerDetail: String?, killReason: String?): String? = when {
    status == Jobs.Status.KILLED && killReason != null ->
        if (producerDetail != null) producerDetail + "; " + killReason else killReason
    else -> producerDetail
}
