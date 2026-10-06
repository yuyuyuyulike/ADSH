package com.adsh.app.core.data

/**
 * 启动时该回到哪条会话（用户第 191 轮口径：**不再每次启动都开一条新会话**）。
 *
 * 三级回落，每一级都是「用户上一次真正在看的那个东西」：
 *  1. [lastId]：上次关掉时在看的那条（[SettingsStore.lastConversationId]），它还在就用它；
 *  2. 最近一条**非空**会话：上次那条被删了。空会话（一条消息都没有的那种「新会话」占位）跳过
 *     —— 回到那里等于又开了一条新的；
 *  3. null：一条会话都没有 —— **不建**，界面停在首页（发第一条消息时才建，见 ChatViewModel.send）。
 *
 * 纯函数：会话列表与空白集合都由调用方读好传进来，桌面上直接测（SessionResumeTest）。
 *
 * @param conversations 全部会话；Repository.conversations 已按 updatedAt 倒序，顺序就是「最近」
 * @param blankIds 一条消息都没有的会话 id（Repository.blankIds）
 */
internal fun resumeConversationId(
    conversations: List<ConversationEntity>,
    blankIds: Set<Long>,
    lastId: Long?,
): Long? = conversations.firstOrNull { it.id == lastId }?.id
    ?: conversations.firstOrNull { it.id !in blankIds }?.id
