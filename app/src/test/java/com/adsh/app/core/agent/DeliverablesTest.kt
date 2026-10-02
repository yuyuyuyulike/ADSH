package com.adsh.app.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交付物（dsh 的 `deliverables/presented`）的落库形态与轮内收集规则。
 *
 * 第八十三轮：present 的文件卡片要画在**一轮的最下方**（dsh 的 turnTail 槽），
 * 数据源是工具行上的 deliverablesJson —— 这两条不变量在这里钉住。
 */
class DeliverablesTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val files = listOf(
            PresentedFile(path = "dist/app.apk", description = "构建产物"),
            PresentedFile(path = "/sdcard/out/report.pdf"),
        )
        val raw = encodeDeliverables(files)
        assertTrue(raw != null && raw.contains("dist/app.apk"))
        assertEquals(files, decodeDeliverables(raw))
    }

    @Test
    fun emptyListStoresNull() {
        // 没有交付物时列必须是 null：工具行上多一个 "[]" 会让「这一轮有没有交付物」变得含糊
        assertNull(encodeDeliverables(emptyList()))
        assertEquals(emptyList<PresentedFile>(), decodeDeliverables(null))
        assertEquals(emptyList<PresentedFile>(), decodeDeliverables(""))
        // 脏数据（老库 / 手改）不该让界面崩，也不该凭空造出交付物
        assertEquals(emptyList<PresentedFile>(), decodeDeliverables("{not json"))
    }

    /**
     * 交付物卡片里显示的路径：工作区内裁掉工作区前缀，工作区外原样
     * （用户第 85 轮：「保留路径，但前面那段工作区的路径要去掉（这样省空间）」）。
     */
    @Test
    fun displayPathDropsTheWorkspacePrefixOnly() {
        val root = "/storage/emulated/0/1/App"
        // 工作区内的绝对路径 → 只留相对的那一截
        assertEquals("测试.md", deliverableDisplayPath(root, "$root/测试.md"))
        assertEquals(
            "app/src/main/java/com/adsh/app/Main.kt",
            deliverableDisplayPath(root, "$root/app/src/main/java/com/adsh/app/Main.kt"),
        )
        // 模型直接写成相对路径 → 原样（不能被"裁"成空）
        assertEquals("dist/app.apk", deliverableDisplayPath(root, "dist/app.apk"))
        // 工作区外 → 原样保留，那是它真实的落地位置
        assertEquals("/tmp/adsh-mock-deliverable.md", deliverableDisplayPath(root, "/tmp/adsh-mock-deliverable.md"))
        // 没绑工作区 / 根为空 → 一律原样
        assertEquals("$root/a.md", deliverableDisplayPath(null, "$root/a.md"))
        assertEquals("$root/a.md", deliverableDisplayPath("", "$root/a.md"))
        // 前缀相同但不是同一个目录（App2 不是 App 的子路径）→ 不能误裁
        assertEquals("$root" + "2/a.md", deliverableDisplayPath(root, "$root" + "2/a.md"))
        // 根末尾多写一个斜杠也要能裁
        assertEquals("a.md", deliverableDisplayPath("$root/", "$root/a.md"))
    }

    /** 一轮里所有工具行的交付物按顺序铺开，同一个路径只留第一次（dsh 的 presentedForClosing） */
    @Test
    fun collectKeepsOrderAndDropsDuplicates() {
        val collected = collectDeliverables(
            listOf(
                listOf(PresentedFile("a.md", "第一份")),
                emptyList(),
                listOf(PresentedFile("b.csv"), PresentedFile("a.md", "又声明了一次")),
            ),
        )
        assertEquals(listOf("a.md", "b.csv"), collected.map { it.path })
        assertEquals("第一份", collected.first().description)
    }
}
