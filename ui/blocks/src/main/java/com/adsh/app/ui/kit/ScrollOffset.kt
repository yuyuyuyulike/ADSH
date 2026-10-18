package com.adsh.app.ui.kit

/**
 * 「把视口钉到内容末端」用的请求偏移。
 *
 * **绝不能用 `Int.MAX_VALUE`**（从真机诊断日志里挖出来的根因）。`requestScrollToItem`
 * 只是把 `(index, offset)` 记进滚动状态，真正的解析在 `LazyListMeasure` 里，而它算的是
 * `toScrollBack = maxOffset - currentMainAxisOffset`，其中 `currentMainAxisOffset` 初值就是
 * `-offset`；`offset` 取 `Int.MAX_VALUE` 时这一步**加法溢出成负数**，后面一串加减按模 2³² 走 ——
 * 最终落点成了「最后一项 / 前一项的高度」的函数：行高凑巧时正好落在底部（所以这个写法一直被
 * 当成「能用」），不凑巧时偏出几百像素。
 *
 * 真机证据（`turn-92-call:0` 那一帧，items=18）：
 * ```
 * pin req items=18 live=2 appended=false tail=true gap=2147483647 first=17+2147483647
 * ```
 * 滚动状态里留下的是 `offset = 2147483647` —— 接下来那一帧解析到哪里全看行高，子行就落在
 * 「靠下、不对」的位置，等下一次内容变化才跳回底部（用户报的「bash 子行位置不对、卡一下才到位」）。
 *
 * 换成一个**大到不可能有任何内容超过、又不会让加法溢出**的偏移之后，同一段算术不再溢出，
 * 落点唯一确定：内容末端贴在视口末端（减掉底部内边距），也就是真正的最大滚动位置。
 */
const val BOTTOM_REQUEST_OFFSET = 1 shl 28
