package com.adsh.app.core.data

/**
 * 认领收件箱时的那一次 name 改写。
 *
 * 为什么单独成文件：这条规则原先在**两处各写一遍** —— 生产的 [ConversationRepository.claimInjected]
 * 与测试替身 `TurnFakes`（后者注释还写着「与 ConversationRepository.claimInjected 的 name 口径
 * 逐字一致」，也就是「两处必须一起改」的那种），而它决定插话与任务通知落在会话的哪个位置。
 * 现在两边都调这一个函数，规则只有一份。
 *
 * 规则（三个 name 见 [ConversationRepository.STEERING] / [ConversationRepository.JOB_NOTICE] /
 * [ConversationRepository.JOB_NOTICE_INJECTED]）：
 *  - [openTurn] = false（**正在跑的那一轮接下了这批行**）：name **原样保留** —— 插话就是
 *    steering 节点、任务通知就是注入形态，它们都落在别人的轮次里；
 *  - [openTurn] = true（那一轮已经收尾、**没人接**）：这批行就是下一轮的内容 ——
 *    插话去掉 steering 标记（没被认领就不是 steering 节点，而是普通 user 节点）；
 *    **这批行的第一条**任务通知换成唤醒形态 [ConversationRepository.JOB_NOTICE]
 *    （它就是那一轮的开头，界面据此为它开一轮，见 `TurnBuilder`）；
 *    后面几条任务通知保持注入形态。
 *
 * 为什么只换第一条：dsh 的 steer 目标是 next-step，空闲时它唤醒一个新 turn，
 * 节点落在那个 turn 的第一步之前 —— 一个轮次只能有一个开头。所以「这批行的第一条」是
 * **整批的下标 0**，不是「第一条任务通知」：如果下标 0 是插话（它变成普通 user 节点、自己
 * 开了这一轮），后面那条任务通知就保持注入形态（这一轮已经有开头了）。有金用例钉住这条。
 *
 * [index] 是**这批待认领行里的顺序**（0 基），不是库里的位置。
 * 纯函数：不碰库、不碰 Android（android.util.Log 在纯 JVM 单测里会抛 not mocked）。
 */
internal fun injectedName(name: String?, index: Int, openTurn: Boolean): String? = when {
    !openTurn -> name
    name == ConversationRepository.STEERING -> null
    index == 0 && name == ConversationRepository.JOB_NOTICE_INJECTED -> ConversationRepository.JOB_NOTICE
    else -> name
}
