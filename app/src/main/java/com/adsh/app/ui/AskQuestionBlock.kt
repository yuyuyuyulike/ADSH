package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 行摘要里问题的最大长度（超出截断加省略号）——摘要是一行省略的，长了只会把右边挤没 */
private const val ASK_SUMMARY_MAX = 60

/*
 * ask_user_question 的展开体与行摘要 —— 对齐 dsh 的 AskQuestionCard
 * （client/ui-tool/src/client/tool/components/AskQuestionCard.tsx + .module.css）。
 *
 * dsh 的卡片是一张「问答成绩单」，只有两种形态：
 *  - answered：<dl> 里一道题一组 —— <dt> 是题目（label-tertiary）、<dd> 是答案
 *    （label-primary），每个答案 <span class="answerLine"> 独占一行（display:block）；
 *    这道题没答就给一句 card.skippedLabel（「未回答」，label-tertiary）；
 *  - unanswered：一句 verdict（label-primary）+ <ul> 逐题列出（label-tertiary）。
 *
 * 卡片的几何（.card）也是逐项搬的：flex column / gap 16px / max-height 360px /
 * overflow-y auto / margin 4px 0 4px 4px / padding 16px 20px / border-radius lg。
 * 内层 .item 的 gap 是 2px（题目与答案贴得紧），.questionList 的 gap 是 8px、
 * padding-left 20px（列表圆点缩进）。字号与行高走 dsh 的 --dsh-content-font-size（14px）
 * 与 24px 行高 —— 与 ADSH 正文同一口径。
 *
 * **行摘要**是 dsh 的 AskQuestionRow：运行中「等待回答」、答完「N/M 已回答」、
 * 一份都没答上来「已结束」。dsh 的摘要来自 answers 数组，所以 ADSH 也从结果正文里数，
 * 而不是从参数里猜。
 */

/** 未回答时的行摘要与卡片 verdict（dsh 的 ask.closed 的两个层次用同一件事的两句话） */
internal const val ASK_UNANSWERED_LABEL = "已结束"

/** dsh 的 ask.closedDetail：这张卡里没有答案可用 */
private const val ASK_UNANSWERED_DETAIL = "本轮没有提交回答"

/** 一道题与它的答案（dsh 的 paired question：answers 为空即「未回答」） */
internal data class AskQuestionEntry(
    val id: String,
    val question: String,
    val answers: List<String> = emptyList(),
)

/**
 * 展开体要画的内容。
 * @param verdict 非空 = unanswered 形态（dsh 的 card.verdict）；空 = answered 形态（<dl>）
 */
internal data class AskCard(
    val questions: List<AskQuestionEntry>,
    val verdict: String? = null,
) {
    /** 有几道题真的答了（dsh 的 answeredPresentation 的 answered 计数口径） */
    val answered: Int get() = questions.count { it.answers.isNotEmpty() }
    val total: Int get() = questions.size
}

/**
 * 参数里的题目（dsh 的 questionEntries(argsRaw)）。
 *
 * 解析不出来（流式半截 JSON、老数据被截断）时返回空表 —— 调用方据此退回普通的 ioCard，
 * 而不是画一张空卡。只有 `id` 与 `question` 是必需的，与 AskUserTool 的校验同一口径。
 */
internal fun askQuestionEntries(arguments: String): List<AskQuestionEntry> {
    val args = ToolArgs.parse(arguments) ?: return emptyList()
    val raw = args["questions"] as? JsonArray ?: return emptyList()
    return raw.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val text = (obj["question"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (id.isEmpty() || text.isEmpty()) null else AskQuestionEntry(id = id, question = text)
    }
}

/** 结果正文里的答案（dsh 的 answerEntries(text)）：按 id 配回题目，对不上的忽略 */
private fun askAnswers(output: String?): List<Pair<String, List<String>>> {
    val text = output?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    // 与参数同一套容错解析（截断过的老数据也认）；answers 取不到就当作「没有答案」
    val parsed = ToolArgs.parse(text) ?: return emptyList()
    val raw = parsed["answers"] as? JsonArray ?: return emptyList()
    return raw.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (id.isEmpty()) return@mapNotNull null
        val selected = (obj["selected"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        // dsh 的答案行：selected 的每一项各占一行，custom 另起一行（选择与自定义可以同时存在）
        val custom = (obj["custom"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        id to (selected + listOfNotNull(custom))
    }
}

/**
 * 工具行数据 → 卡片数据。返回 null = 这一行不该画问答卡（参数不是题目，退回 ioCard）。
 *
 * @param error 这一行是失败态（AskUserTool 在超时 / 被跳过时返回错误）
 */
internal fun askCardOf(arguments: String, output: String?, running: Boolean, error: Boolean): AskCard? {
    val questions = askQuestionEntries(arguments)
    if (questions.isEmpty()) return null
    if (running || error) return AskCard(questions, verdict = ASK_UNANSWERED_DETAIL)
    val answers = askAnswers(output).toMap()
    return AskCard(
        questions = questions.map { entry ->
            entry.copy(answers = answers[entry.id].orEmpty())
        },
    )
}

/**
 * 行摘要（dsh 的 AskQuestionRow 的 summary）：
 *  1. 运行中 → 第一道题（dsh 那行是「waiting」，ADSH 这条是通用摘要的退路）；
 *  2. 失败（超时 / 被跳过）→「已结束」；
 *  3. 有答案 →「N/M 已回答」；
 *  4. 都没有（结果还没落库）→ 仍是第一道题，**不编数字**；
 *  5. 参数不是题目 → null，调用方退回通用摘要。
 */
internal fun askRowSummary(card: AskCard?, running: Boolean, error: Boolean): String? {
    if (card == null) return null
    return when {
        running -> askSummaryQuestion(card)
        error -> ASK_UNANSWERED_LABEL
        card.answered > 0 -> card.answered.toString() + "/" + card.total + " 已回答"
        // 结果还没到（没有 answers）：摘要显示第一道题，不编数字
        else -> askSummaryQuestion(card).takeIf { it.isNotEmpty() }
    }
}

/** 行摘要里显示的问题：第一道题的正文（压成一行，超长截断） */
private fun askSummaryQuestion(card: AskCard): String {
    val text = card.questions.firstOrNull()?.question.orEmpty()
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (text.length <= ASK_SUMMARY_MAX) text else text.take(ASK_SUMMARY_MAX) + "…"
}

/** 作答卡（dsh 的 AskQuestionCard） */
@Composable
internal fun RailAskQuestionCard(card: AskCard, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
            .clip(shape)
            .background(palette.codeBlock)
            .border(0.5.dp, palette.borderL2, shape)
            // dsh 的 .card{max-height:360px; overflow-y:auto}：题多了在卡片内部滚，
            // 不去撑会话的滚动条
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val verdict = card.verdict
        if (verdict != null) {
            // dsh 的 unanswered 形态：一句 verdict + 逐题列表（圆点 20px 缩进、题间 8px）
            Text(
                text = verdict,
                fontSize = 14.sp,
                lineHeight = 24.sp,
                color = palette.labelPrimary,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                card.questions.forEach { entry ->
                    Text(
                        text = entry.question,
                        fontSize = 14.sp,
                        lineHeight = 24.sp,
                        color = palette.labelTertiary,
                    )
                }
            }
        } else {
            // dsh 的 answered 形态：一道题一组，题目三级色、答案一级色，答案每项独占一行
            card.questions.forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entry.question,
                        fontSize = 14.sp,
                        lineHeight = 24.sp,
                        color = palette.labelTertiary,
                    )
                    if (entry.answers.isEmpty()) {
                        Text(
                            text = "未回答",
                            fontSize = 14.sp,
                            lineHeight = 24.sp,
                            color = palette.labelTertiary,
                        )
                    } else {
                        entry.answers.forEach { line ->
                            Text(
                                text = line,
                                fontSize = 14.sp,
                                lineHeight = 24.sp,
                                color = palette.labelPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}
