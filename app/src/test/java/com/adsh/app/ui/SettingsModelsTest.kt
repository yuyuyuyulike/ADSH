package com.adsh.app.ui

import com.adsh.app.core.data.ApiProtocol
import com.adsh.app.core.data.BuiltInProviders
import com.adsh.app.core.data.ModelDef
import com.adsh.app.core.data.ProviderDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页「模型」区的纯逻辑（[SettingsModels]）。
 *
 * 这一簇原先是 @Composable 文件里的私有函数，**一个用例都没有** —— 容量字段是「打字即写值」
 * 的输入框（`parseCapacity(typed)?.let { onValue(it) }`），解析失败与写 0 的差别决定了
 * 用户删掉一个数字之后模型档案里到底剩下什么，值得钉死。
 */
class SettingsModelsTest {

    /** 容量字段里那份「解析失败 → 消息里带上原文」的断言helper */
    private fun assertParsed(text: String, expected: Long) {
        assertEquals("parseCapacity(\"" + text + "\")", expected, parseCapacity(text) ?: error("解析失败：" + text))
    }

    // ------------------------------------------------------------ formatCapacity：显示

    @Test
    fun `零与负数没有写法`() {
        assertEquals("", formatCapacity(0))
        assertEquals("", formatCapacity(-1))
        assertEquals("", formatCapacity(Long.MIN_VALUE))
    }

    @Test
    fun `1024 的整数倍写成 K`() {
        assertEquals("1K", formatCapacity(1024))
        assertEquals("4K", formatCapacity(4096))
        // 1536 不是 1024 的整数倍（1.5K），按原样写数字 —— 免得显示成 "1K" 丢掉一半
        assertEquals("1536", formatCapacity(1536))
    }

    @Test
    fun `1024 与 M 都整除时优先写 M`() {
        assertEquals("1M", formatCapacity(1024L * 1024L))
        assertEquals("2M", formatCapacity(2 * 1024L * 1024L))
        // 1536×1024 = 1.5M：整除 1024 但不整除 1M，所以写成 "1536K"
        assertEquals("1536K", formatCapacity(1536L * 1024L))
    }

    @Test
    fun `真实容量是十进制整数时按原样写`() {
        // DeepSeek 的上下文窗口就是这种：1_000_000 不是 1024 的整数倍
        assertEquals("1000000", formatCapacity(1_000_000))
        assertEquals("200000", formatCapacity(200_000))
    }

    @Test
    fun `写入再读回来是同一个值`() {
        for (value in listOf(1024L, 4096L, 1024L * 1024L, 5 * 1024L * 1024L, 1536L * 1024L)) {
            assertParsed(formatCapacity(value), value)
        }
    }

    // ------------------------------------------------------------ parseCapacity：三态

    @Test
    fun `空串是零：那是「用提供方默认值」`() {
        assertParsed("", 0L)
        assertParsed("   ", 0L)
    }

    @Test
    fun `零与负数也落到零`() {
        assertParsed("0", 0L)
        assertParsed("-5", 0L)
        assertParsed("-5K", 0L)
    }

    @Test
    fun `解析不了返回 null：调用点据此不改已存的值`() {
        assertNull(parseCapacity("abc"))
        assertNull(parseCapacity("12x"))
        assertNull(parseCapacity("1.5K"))
        assertNull(parseCapacity("K"))
        assertNull(parseCapacity("M"))
        // 超出 Long：不是回绕，是直接不认
        assertNull(parseCapacity("99999999999999999999"))
    }

    @Test
    fun `K 与 M 是 1024 进制，大小写都认，前后空格忽略`() {
        assertParsed("128K", 131_072L)
        assertParsed("128k", 131_072L)
        assertParsed(" 128K ", 131_072L)
        assertParsed("1M", 1_048_576L)
        assertParsed("2m", 2_097_152L)
    }

    @Test
    fun `没有后缀就是原样的十进制数`() {
        assertParsed("1024", 1024L)
        assertParsed("1000000", 1_000_000L)
        // toLongOrNull 认前导加号（字段里能敲出来），顺带钉住
        assertParsed("+5", 5L)
    }

    /**
     * 边界（**现状**，本轮只做熵减、不改行为）：后缀乘法不做溢出保护，
     * `9999999999999M` 会回绕成负数 —— 于是容量字段显示回空串。
     * 要加保护是独立一轮的事，这条用例的作用是别让人在重构里悄悄把它改成别的样子。
     */
    @Test
    fun `超过 Long 的后缀写法会回绕成负数（现状）`() {
        assertEquals(-7_960_984_073_710_600_192L, parseCapacity("9999999999999M"))
    }

    // ------------------------------------------------------------ 添加卡片：就绪算式

    private val sampleModels = listOf(ModelDef(id = "gpt-x"))

    private fun draft(
        route: String = "acme",
        displayName: String = "",
        baseUrl: String = "https://gw.example/v1",
        models: List<ModelDef> = sampleModels,
        apiKey: String = "",
        api: String = ApiProtocol.OPENAI_COMPLETIONS,
    ) = ProviderDraft(
        route = route,
        displayName = displayName,
        baseUrl = baseUrl,
        api = api,
        apiKey = apiKey,
        models = models,
    )

    /** 常用形状：route / 地址 / 一个模型都填好了 */
    private fun formOf(
        route: String = "acme",
        baseUrl: String = "https://gw.example/v1",
        models: List<ModelDef> = sampleModels,
        taken: List<String> = emptyList(),
        catalogRoute: Boolean = false,
    ) = providerFormState(draft(route = route, baseUrl = baseUrl, models = models), taken, catalogRoute)

    @Test
    fun `还没写 route 时不报错也不提示`() {
        val form = formOf(route = "")
        assertFalse(form.routeInvalid)
        assertFalse(form.routeTaken)
        assertFalse(form.ready)
        assertNull(form.hint)
    }

    @Test
    fun `route 不合法与已被占用各是一种错，且不在卡片底部重复说`() {
        val bad = formOf(route = "Acme")
        assertTrue(bad.routeInvalid)
        assertNull(bad.hint)
        val taken = formOf(route = "acme", taken = listOf("acme"))
        assertTrue(taken.routeTaken)
        assertNull(taken.hint)
    }

    @Test
    fun `地址写了就必须是 http 或 https，判的是 trim 之后的`() {
        assertTrue(formOf(baseUrl = "ftp://gw/v1").baseInvalid)
        assertFalse(formOf(baseUrl = "http://gw/v1").baseInvalid)
        val padded = formOf(baseUrl = "  https://gw/v1  ")
        assertFalse(padded.baseInvalid)
        assertEquals("https://gw/v1", padded.baseUrl)
    }

    @Test
    fun `只有空格的地址算写错，不算还没写`() {
        val form = formOf(baseUrl = "   ")
        assertTrue(form.baseInvalid)
        assertNull(form.hint)
    }

    @Test
    fun `地址缺失时提示地址，且地址优先于模型`() {
        val form = formOf(baseUrl = "", models = emptyList())
        assertEquals("自定义提供方需要填写 API 地址。", form.hint)
        assertFalse(form.ready)
    }

    @Test
    fun `手写的自定义提供方至少要一个模型`() {
        val form = formOf(models = emptyList())
        assertEquals(PROVIDER_NEEDS_MODELS, form.hint)
        assertFalse(form.ready)
    }

    @Test
    fun `ID 全是空白的模型行不算数，提示直接指到那一行（dsh 的口径）`() {
        val form = formOf(models = listOf(ModelDef(id = "   ")))
        assertEquals("模型 1: 模型 ID 不能为空。", form.hint)
        assertFalse(form.ready)
    }

    @Test
    fun `目录里挑的 route 允许先不写模型`() {
        val form = formOf(models = emptyList(), catalogRoute = true)
        assertTrue(form.ready)
        assertNull(form.hint)
    }

    @Test
    fun `填齐了就绪且不再提示`() {
        val form = formOf()
        assertTrue(form.ready)
        assertNull(form.hint)
    }

    // ------------------------------------------------------------ 创建提供方：写进设置的那一条

    @Test
    fun `创建时显示名留空用 route，密钥与地址去空格，空模型行被丢掉`() {
        val created = providerCreated(
            draft(
                route = "acme",
                displayName = "",
                baseUrl = " https://gw.example/v1 ",
                apiKey = " sk-1 ",
                models = listOf(ModelDef(id = "gpt-x"), ModelDef(id = "  ")),
            ),
            catalogRoute = false,
        )
        assertEquals("acme", created.id)
        assertEquals("acme", created.displayName)
        assertEquals("https://gw.example/v1", created.baseUrl)
        assertEquals("sk-1", created.apiKey)
        assertEquals(listOf("gpt-x"), created.models.map { it.id })
        assertTrue(created.custom)
    }

    @Test
    fun `从目录里挑的 route 不打自定义标签，协议与显示名照搬`() {
        val created = providerCreated(
            draft(route = "openai", displayName = "OpenAI", api = ApiProtocol.ANTHROPIC_MESSAGES),
            catalogRoute = true,
        )
        assertFalse(created.custom)
        assertEquals("OpenAI", created.displayName)
        assertEquals(ApiProtocol.ANTHROPIC_MESSAGES, created.api)
    }

    // ------------------------------------------------------------ 编辑卡片：保存

    private val deepseek = BuiltInProviders.deepseek()

    private fun ProviderSave.ok(): ProviderDef = when (this) {
        is ProviderSave.Ok -> provider
        is ProviderSave.Failed -> error("预期保存成功，实际报错：" + message)
    }

    @Test
    fun `密钥留空保持原密钥，填了就用新值并去空格`() {
        val kept = providerSaved(deepseek, keyInput = "", baseUrlInput = "", models = deepseek.models).ok()
        assertEquals(deepseek.apiKey, kept.apiKey)
        val replaced = providerSaved(deepseek, keyInput = " sk-new ", baseUrlInput = "", models = deepseek.models).ok()
        assertEquals("sk-new", replaced.apiKey)
    }

    @Test
    fun `地址留空保持原地址，填了就用新值并去空格`() {
        val kept = providerSaved(deepseek, "", "", deepseek.models).ok()
        assertEquals(BuiltInProviders.DEEPSEEK_BASE_URL, kept.baseUrl)
        val replaced = providerSaved(deepseek, "", " https://gw.example/v1 ", deepseek.models).ok()
        assertEquals("https://gw.example/v1", replaced.baseUrl)
    }

    @Test
    fun `目录为空时整条不保存，报的是与创建同一句文案`() {
        val result = providerSaved(deepseek, keyInput = "sk-new", baseUrlInput = "https://gw/v1", models = emptyList())
        assertTrue(result is ProviderSave.Failed)
        assertEquals(PROVIDER_NEEDS_MODELS, (result as ProviderSave.Failed).message)
    }

    @Test
    fun `保存不改提供方身份字段`() {
        val saved = providerSaved(deepseek, keyInput = "", baseUrlInput = "", models = deepseek.models).ok()
        assertEquals(deepseek.id, saved.id)
        assertEquals(deepseek.displayName, saved.displayName)
        assertEquals(deepseek.api, saved.api)
        assertFalse(saved.custom)
    }

    /**
     * 按 dsh 的口径（`validateDeepSeekModels` + `EditorFooter`）：目录里只要有一行 ID 是空的，
     * **整条不保存**，报文是 dsh 的「模型 N: 原因」（N 从 1 数）。以前这里是「把 id = "" 的行
     * 原样落盘」，R47 按用户要求照 dsh 改掉了 —— 保存键同时变暗，这条纯函数是第二道防线。
     */
    @Test
    fun `目录里有一行 ID 为空就整条不保存，报文指到那一行`() {
        val result = providerSaved(deepseek, keyInput = "sk-new", baseUrlInput = "", models = listOf(ModelDef(id = "gpt-x"), ModelDef(id = "  ")))
        assertTrue(result is ProviderSave.Failed)
        assertEquals("模型 2: 模型 ID 不能为空。", (result as ProviderSave.Failed).message)
    }

    @Test
    fun `目录里有重复 ID（trim 之后相同）也整条不保存`() {
        val result = providerSaved(deepseek, "", "", listOf(ModelDef(id = "gpt-x"), ModelDef(id = " gpt-x ")))
        assertTrue(result is ProviderSave.Failed)
        assertEquals("模型 2: 模型 ID 不能重复。", (result as ProviderSave.Failed).message)
    }
    // ------------------------------------------------------------ 编辑卡片：目录头上的那句话

    @Test
    fun `内置提供方用默认目录时不算已自定义`() {
        assertFalse(providerOverridden(deepseek, BuiltInProviders.DEEPSEEK_MODELS, BuiltInProviders.DEEPSEEK_MODELS))
        assertTrue(providerOverridden(deepseek, listOf(ModelDef(id = "x")), BuiltInProviders.DEEPSEEK_MODELS))
    }

    @Test
    fun `自定义提供方不论目录如何都算已自定义`() {
        val custom = deepseek.copy(id = "acme", custom = true)
        assertTrue(providerOverridden(custom, BuiltInProviders.DEEPSEEK_MODELS, BuiltInProviders.DEEPSEEK_MODELS))
    }

    @Test
    fun `目录里加的提供方与内置默认目录不同，因此也显示已自定义（现状）`() {
        val openai = ProviderDef(id = "openai", displayName = "OpenAI", models = listOf(ModelDef(id = "gpt-6-sol")))
        assertTrue(providerOverridden(openai, openai.models, BuiltInProviders.DEEPSEEK_MODELS))
    }

    // ------------------------------------------------------------ 提供方清单：增 / 存 / 删

    private val official = BuiltInProviders.deepseek()

    private val acme = ProviderDef(
        id = "acme",
        displayName = "Acme",
        baseUrl = "https://acme.example/v1",
        apiKey = "sk-acme",
        models = listOf(ModelDef(id = "m1"), ModelDef(id = "m2")),
        custom = true,
    )

    @Test
    fun `保存替换同 id 的那一条，顺序不变，也不写当前选中值`() {
        val sel = providersSaved(
            listOf(official, acme),
            acme.copy(displayName = "Acme 2"),
            currentProviderId = official.id,
            currentModel = "deepseek-flash",
        )
        assertEquals(listOf(official.id, acme.id), sel.providers.map { it.id })
        assertEquals("Acme 2", sel.providers[1].displayName)
        assertNull(sel.providerId)
        assertNull(sel.model)
    }

    @Test
    fun `保存的是当前提供方且当前模型被删掉了，就落到该提供方的第一条`() {
        val sel = providersSaved(
            listOf(official, acme),
            acme.copy(models = listOf(ModelDef(id = "m2"))),
            currentProviderId = acme.id,
            currentModel = "m1",
        )
        assertEquals("m2", sel.model)
        assertNull(sel.providerId)
    }

    @Test
    fun `保存的是当前提供方且当前模型还在，就不写模型`() {
        val sel = providersSaved(listOf(acme), acme.copy(baseUrl = "https://new.example/v1"), acme.id, "m1")
        assertNull(sel.model)
        assertEquals("https://new.example/v1", sel.providers.single().baseUrl)
    }

    @Test
    fun `保存后目录空了就什么都不写（保存那一步本来也拦住了空目录）`() {
        val sel = providersSaved(listOf(acme), acme.copy(models = emptyList()), acme.id, "m1")
        assertNull(sel.model)
        assertTrue(sel.providers.single().models.isEmpty())
    }

    @Test
    fun `保存别的提供方不动当前模型`() {
        val sel = providersSaved(listOf(official, acme), official.copy(displayName = "DeepSeek 2"), acme.id, "m1")
        assertNull(sel.model)
        assertNull(sel.providerId)
    }

    @Test
    fun `删掉的不是当前提供方就只改清单`() {
        val sel = providersDeleted(listOf(official, acme), targetId = official.id, currentProviderId = acme.id)
        assertEquals(listOf(acme.id), sel.providers.map { it.id })
        assertNull(sel.providerId)
        assertNull(sel.model)
    }

    @Test
    fun `删掉当前提供方就落到剩下的第一条`() {
        val sel = providersDeleted(listOf(official, acme), targetId = official.id, currentProviderId = official.id)
        assertEquals(acme.id, sel.providerId)
    }

    @Test
    fun `删掉最后一个提供方时当前提供方写成空串`() {
        val sel = providersDeleted(listOf(official), targetId = official.id, currentProviderId = official.id)
        assertTrue(sel.providers.isEmpty())
        assertEquals("", sel.providerId)
    }

    /**
     * 现状：删除**不动当前模型**，哪怕它正属于被删掉的那个提供方（模型选择器里会留着它的
     * id，用户下次自己挑）。这条以前只写在注释里，现在有用例。
     */
    @Test
    fun `删除不动当前模型，哪怕它属于被删掉的那个提供方`() {
        val sel = providersDeleted(listOf(acme), targetId = acme.id, currentProviderId = acme.id)
        assertNull(sel.model)
    }

    @Test
    fun `新增提供方追加到末尾`() {
        val sel = providersAdded(listOf(official), ProviderDef(id = "openai", displayName = "OpenAI"))
        assertEquals(listOf(official.id, "openai"), sel.providers.map { it.id })
        assertNull(sel.providerId)
        assertNull(sel.model)
    }

    // ------------------------------------------------------------ 模型目录的逐行校验（dsh 的 validateDeepSeekModels）

    @Test
    fun `全都合法（含空目录）时没有第一处不合法`() {
        assertNull(firstInvalidModel(emptyList()))
        assertNull(firstInvalidModel(listOf(ModelDef(id = "gpt-x"), ModelDef(id = "gpt-y"))))
        // 行内前后空格不算错（校验用的是 trim 之后的值）
        assertNull(firstInvalidModel(listOf(ModelDef(id = " gpt-x "))))
    }

    @Test
    fun `ID 为空或只有空白都算「不能为空」`() {
        assertEquals(InvalidModel(0, ModelProblem.ID_REQUIRED), firstInvalidModel(listOf(ModelDef(id = ""))))
        assertEquals(InvalidModel(0, ModelProblem.ID_REQUIRED), firstInvalidModel(listOf(ModelDef(id = "   "))))
        assertEquals(
            InvalidModel(1, ModelProblem.ID_REQUIRED),
            firstInvalidModel(listOf(ModelDef(id = "gpt-x"), ModelDef(id = ""))),
        )
    }

    @Test
    fun `trim 之后相同就是重复，报的是后出现的那一行`() {
        assertEquals(
            InvalidModel(1, ModelProblem.ID_DUPLICATE),
            firstInvalidModel(listOf(ModelDef(id = "gpt-x"), ModelDef(id = " gpt-x "))),
        )
    }

    @Test
    fun `只报第一处：空 ID 比后面的重复更靠前`() {
        val models = listOf(ModelDef(id = ""), ModelDef(id = ""))
        assertEquals(InvalidModel(0, ModelProblem.ID_REQUIRED), firstInvalidModel(models))
    }

    @Test
    fun `原因文案逐字照 dsh 的 zh 字典，行号从 1 数`() {
        assertEquals("模型 1: 模型 ID 不能为空。", modelProblemText(InvalidModel(0, ModelProblem.ID_REQUIRED)))
        assertEquals("模型 3: 模型 ID 不能重复。", modelProblemText(InvalidModel(2, ModelProblem.ID_DUPLICATE)))
    }

    // ------------------------------------------------------------ 添加卡片：不合法的行也不给就绪

    @Test
    fun `手写的卡片里有一行空 ID 就不就绪，提示指到那一行`() {
        val form = formOf(models = listOf(ModelDef(id = "gpt-x"), ModelDef(id = "")))
        assertFalse(form.ready)
        assertEquals("模型 2: 模型 ID 不能为空。", form.hint)
    }

    @Test
    fun `目录 route 允许空目录，但不允许留着空 ID 的行`() {
        assertTrue(formOf(models = emptyList(), catalogRoute = true).ready)
        val withBlankRow = formOf(models = listOf(ModelDef(id = "")), catalogRoute = true)
        assertFalse(withBlankRow.ready)
        assertEquals("模型 1: 模型 ID 不能为空。", withBlankRow.hint)
    }

    @Test
    fun `地址缺失仍然优先于模型的问题`() {
        val form = formOf(baseUrl = "", models = listOf(ModelDef(id = "")))
        assertEquals("自定义提供方需要填写 API 地址。", form.hint)
    }

    // ------------------------------------------------------------ 拉取可用模型（弹窗）

    @Test
    fun `候选里不列目录里已经有的，保持服务端顺序`() {
        assertEquals(listOf("b", "c"), fetchCandidates(listOf("a", "b", "c"), existing = listOf("a")))
        assertEquals(emptyList<String>(), fetchCandidates(listOf("a"), existing = listOf("a")))
    }

    @Test
    fun `搜索先去前后空格，再大小写不敏感地包含`() {
        val candidates = listOf("gpt-6", "DeepSeek-V4", "claude-opus-5")
        assertEquals(candidates, candidateFilter(candidates, ""))
        assertEquals(candidates, candidateFilter(candidates, "   "))
        assertEquals(listOf("gpt-6"), candidateFilter(candidates, "GPT"))
        // 带空格的输入以前一个都匹配不到（dsh 会先 trim）—— 这条是回归钉子
        assertEquals(listOf("gpt-6"), candidateFilter(candidates, " gpt "))
        assertEquals(listOf("DeepSeek-V4"), candidateFilter(candidates, "seek"))
        assertEquals(emptyList<String>(), candidateFilter(candidates, "nope"))
    }

    @Test
    fun `全选按钮的文案判据：可见的都选过才算已全选`() {
        assertFalse(allVisiblePicked(emptySet(), emptyList()))
        assertFalse(allVisiblePicked(setOf("a"), listOf("a", "b")))
        assertTrue(allVisiblePicked(setOf("a", "b"), listOf("a", "b")))
        // 可见为空 → false（那枚按钮此时本来就是禁用的）
        assertFalse(allVisiblePicked(setOf("a"), emptyList()))
    }

    @Test
    fun `全选是并集：被搜索过滤掉的已选行不会被丢掉（dsh 的口径）`() {
        assertEquals(setOf("a", "b"), toggleAllVisible(setOf("a"), listOf("b")))
        assertEquals(setOf("a", "b", "c"), toggleAllVisible(setOf("a"), listOf("b", "c")))
    }

    @Test
    fun `可见的全都选过时点全选 = 取消全选，连当前看不见的一起清`() {
        assertEquals(emptySet<String>(), toggleAllVisible(setOf("a", "b"), listOf("a", "b")))
        assertEquals(emptySet<String>(), toggleAllVisible(setOf("a", "b", "z"), listOf("a")))
    }

    @Test
    fun `添加所选按候选顺序（服务端顺序），而不是字母序`() {
        val adopted = adoptedModels(candidates = listOf("zeta", "alpha"), selected = setOf("zeta", "alpha"))
        assertEquals(listOf("zeta", "alpha"), adopted.map { it.id })
    }

    @Test
    fun `添加所选只取选中的那些`() {
        val adopted = adoptedModels(candidates = listOf("a", "b", "c"), selected = setOf("b"))
        assertEquals(listOf("b"), adopted.map { it.id })
    }

    @Test
    fun `添加所选用内置目录补档案，图片能力跟着回来`() {
        val adopted = adoptedModels(
            candidates = listOf("deepseek-flash", "brand-new"),
            selected = setOf("deepseek-flash", "brand-new"),
        )
        val flash = adopted.first { it.id == "deepseek-flash" }
        assertEquals("DeepSeek-V4.1-Flash", flash.name)
        assertEquals(1_000_000L, flash.contextWindow)
        assertTrue(flash.acceptsImages)
        val unknown = adopted.first { it.id == "brand-new" }
        assertNull(unknown.name)
        assertFalse(unknown.acceptsImages)
    }

    // ------------------------------------------------------------ 模型目录：行内编辑

    private val twoModels = listOf(ModelDef(id = "a", name = "A"), ModelDef(id = "b", name = "B"))

    @Test
    fun `改一行只动那一行，其余行原样`() {
        val edited = modelEdited(twoModels, 1) { it.copy(name = "B2") }
        assertEquals(listOf("A", "B2"), edited.map { it.name })
        assertEquals(listOf("a", "b"), edited.map { it.id })
    }

    @Test
    fun `改一行的下标越界时原样返回（删行与重组之间会差一帧）`() {
        assertEquals(twoModels, modelEdited(twoModels, -1) { it.copy(id = "x") })
        assertEquals(twoModels, modelEdited(twoModels, 2) { it.copy(id = "x") })
        assertEquals(emptyList<ModelDef>(), modelEdited(emptyList(), 0) { it.copy(id = "x") })
    }

    @Test
    fun `删一行保留其余顺序，越界下标什么也不删`() {
        assertEquals(listOf("b"), modelsRemoved(twoModels, 0).map { it.id })
        assertEquals(listOf("a"), modelsRemoved(twoModels, 1).map { it.id })
        assertEquals(twoModels, modelsRemoved(twoModels, 9))
        assertEquals(emptyList<ModelDef>(), modelsRemoved(emptyList(), 0))
    }

    @Test
    fun `删行之后展开集合跟着挪：后面的减一，前面的不动`() {
        assertEquals(setOf(0, 1), reindexOnRemove(setOf(0, 2), 1))
        assertEquals(setOf(0, 1, 2), reindexOnRemove(setOf(0, 1, 3), 2))
        assertEquals(setOf(0), reindexOnRemove(setOf(0), 2))
        assertEquals(emptySet<Int>(), reindexOnRemove(emptySet(), 0))
    }

    @Test
    fun `被删掉的那一行自己的展开状态要丢掉，它后面那一行跟着前移`() {
        // {0,1} 里删掉第 0 行：原来的第 1 行挪到 0（它还是展开的），0 号自己的状态丢掉
        assertEquals(setOf(0), reindexOnRemove(setOf(0, 1), 0))
        assertEquals(emptySet<Int>(), reindexOnRemove(setOf(1), 1))
    }

    @Test
    fun `展开与收起一行`() {
        assertEquals(setOf(1), toggleExpanded(emptySet(), 1))
        assertEquals(emptySet<Int>(), toggleExpanded(setOf(1), 1))
        assertEquals(setOf(0, 1), toggleExpanded(setOf(0), 1))
    }
}
