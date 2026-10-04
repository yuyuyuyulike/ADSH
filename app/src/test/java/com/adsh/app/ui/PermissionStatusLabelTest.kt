package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 权限卡那一格状态文字的三态（第 182 轮从设置页搬进 PermissionActions.kt 的纯函数）。
 *
 * 为什么值得一条测试：这三句话是「界面不许撒谎」这条规矩的**全部**输出口 ——
 * 读到 true 写「已授权」、读到 false 写「未授权」、读不到（系统没有这个开关）写「去设置」。
 * 谁要是把 null 那条并成「未授权」，用户就会以为「系统没给读取口」等于「没授权」，
 * 于是反复去设置页找一个根本不存在的开关。
 */
class PermissionStatusLabelTest {

    @Test
    fun threeStatesHaveThreeLabels() {
        assertEquals("已授权", permissionStatusLabel(true))
        assertEquals("未授权", permissionStatusLabel(false))
        assertEquals("去设置", permissionStatusLabel(null))
    }
}
