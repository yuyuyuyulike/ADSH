package com.adsh.app.core.data

/**
 * 侧栏里会话行的顺序：置顶行排最前，置顶组与非置顶组内部都按最近更新倒序。
 *
 * [ConversationEntity.pinned] 是置顶标记在库里的落点；本函数只把它稳定地分成两段，
 * [ConversationEntity.updatedAt] 决定段内先后 —— updatedAt 相同时保持入参相对顺序（稳定排序）。
 * 置顶动作本身不刷新 updatedAt。
 *
 * 空白占位行（一条消息都没有的「新会话」）由抽屉另行提前，不在本函数职责内。
 *
 * 跨模块可见：消费者是抽屉与会话宿主（:app / :feature:chat），所以它是 public。
 */
fun sidebarConversationOrder(conversations: List<ConversationEntity>): List<ConversationEntity> {
    val (pinned, rest) = conversations.partition { it.pinned }
    return pinned.sortedByDescending { it.updatedAt } + rest.sortedByDescending { it.updatedAt }
}
