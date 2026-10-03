package com.adsh.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.tools.Answer
import com.adsh.app.core.tools.Question
import com.adsh.app.core.tools.QuestionOption
import com.adsh.app.ui.theme.LocalDshPalette

// ------------------------------------------------------------------ 提问卡片

/** dsh 的 parseRecommendedLabel：选项末尾的「（推荐）/(Recommended)」拆成独立徽标，答案值不变 */
private val RECOMMENDED_SUFFIX =
    Regex("\\s*(?:\\((?:recommended|推荐)\\)|（(?:recommended|推荐)）)\\s*$", RegexOption.IGNORE_CASE)

/** 一道题的作答草稿（dsh 的 draft：selected / custom / skipped） */
private data class QuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
    val skipped: Boolean = false,
) {
    val answered: Boolean get() = selected.isNotEmpty() || custom.isNotBlank()
    val completed: Boolean get() = answered || skipped
}

/**
 * 提问卡，逐项对齐 dsh 的 QuestionComposer（client/ui-questions 的 QuestionComposer.module.css）：
 *
 *  - .frame{padding:6px ... 10px} 里一张 .card：圆角 16（窄屏）、底 --dsw-specific-input-major、
 *    max-height min(60vh,520px)，底部 10px 内边距；
 *  - .header：eyebrow 11/16 三级色 + 标题 15/21 500 + 右上角 24x24 圆形按钮（收起 / 放弃整组问题）；
 *  - 选项行：min-height 40、圆角 12、选中底色 interactive-bg-hover；单选左边是 20x20 的序号方块，
 *    多选是复选框；标签 14/500/24，后面可以跟「推荐」徽标，描述 14/24 三级色；
 *  - 最后一行的「输入你的答案」：有选项时跟选项同一列（左边是编辑图标或复选框），
 *    没有选项时是一整块 textarea（border .5px border-l4、圆角 10、最小高 64）；
 *  - .footer：左分页（上一题 / 1 / 2 / 下一题）、中间报错文案、右边「跳过本题」+「下一题 / 提交」。
 *
 * 单选点一下会自动翻到下一题（dsh 的 choose 就是这么做的），多选只切换勾选。
 */
@Composable
internal fun QuestionCard(questions: List<Question>, onAnswer: (List<Answer>) -> Unit, onSkip: () -> Unit) {
    val palette = LocalDshPalette.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    var index by remember(questions) { mutableIntStateOf(0) }
    var drafts by remember(questions) { mutableStateOf(questions.map { QuestionDraft() }) }
    var minimized by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val question = questions.getOrNull(index) ?: return
    val draft = drafts.getOrNull(index) ?: QuestionDraft()
    val multi = question.multiSelect
    val last = index == questions.size - 1

    fun replaceDraft(next: QuestionDraft, nextIndex: Int = index, clearError: Boolean = true) {
        drafts = drafts.toMutableList().also { it[index] = next }
        if (nextIndex != index) index = nextIndex
        if (clearError) error = null
    }

    fun submit(values: List<QuestionDraft>) {
        val missing = values.indexOfFirst { !it.completed }
        if (missing >= 0) {
            index = missing
            error = "请先完成这道问题。"
            return
        }
        error = null
        onAnswer(
            questions.mapIndexed { at, item ->
                val value = values[at]
                if (value.skipped) {
                    Answer(id = item.id, selected = emptyList())
                } else {
                    val custom = value.custom.trim()
                    Answer(
                        id = item.id,
                        selected = if (custom.isEmpty() || item.multiSelect) value.selected else emptyList(),
                        custom = custom.ifEmpty { null },
                    )
                }
            },
        )
    }

    fun continueFlow() {
        if (!draft.answered) {
            error = "请选择一个选项或填写自定义答案。"
            return
        }
        if (!last) {
            index += 1
            error = null
            return
        }
        submit(drafts)
    }

    fun choose(label: String) {
        if (multi) {
            val picked = if (label in draft.selected) draft.selected - label else draft.selected + label
            replaceDraft(draft.copy(selected = picked, skipped = false))
        } else {
            val nextIndex = if (!last) index + 1 else index
            replaceDraft(QuestionDraft(selected = listOf(label)), nextIndex)
        }
    }

    fun skipQuestion() {
        val next = drafts.toMutableList().also { it[index] = QuestionDraft(skipped = true) }
        drafts = next
        error = null
        if (last) submit(next) else index += 1
    }

    val cardShape = RoundedCornerShape(16.dp)
    val rowShape = RoundedCornerShape(12.dp)
    val maxBodyHeight = minOf(520, (configuration.screenHeightDp * 0.6f).toInt()).dp

    Column(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Xxl).padding(bottom = DshSpacing.Lg)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(palette.inputMajor)
                .border(0.5.dp, palette.borderL1, cardShape)
                .padding(bottom = DshSpacing.Xxl),
        ) {
            QuestionCardHeader(
                header = question.header,
                question = question.question,
                minimized = minimized,
                onToggleMinimized = { minimized = !minimized },
                onSkipAll = onSkip,
            )

            if (!minimized) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxBodyHeight)
                        // 卡片滚到头之后剩下的位移/惯性留在卡片里（第 115 轮，见 ScrollEdgeEater）
                        .nestedScroll(ScrollEdgeEater)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Md),
                        verticalArrangement = Arrangement.spacedBy(DshSpacing.Xxs),
                    ) {
                        question.options.forEachIndexed { optionIndex, option ->
                            QuestionOptionRow(
                                option = option,
                                optionIndex = optionIndex,
                                picked = option.label in draft.selected,
                                multi = multi,
                                onClick = { choose(option.label) },
                            )
                        }

                        if (question.options.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = DshSpacing.Xxxl)
                                    .heightIn(min = 64.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.bgModulePlatform)
                                    .border(0.5.dp, palette.borderL4, RoundedCornerShape(10.dp))
                                    .padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Xl),
                            ) {
                                QuestionAnswerField(
                                    value = draft.custom,
                                    placeholder = "输入你的答案",
                                    modifier = Modifier.fillMaxWidth(),
                                    onValue = { typed ->
                                        replaceDraft(
                                            draft.copy(
                                                custom = typed,
                                                selected = if (multi) draft.selected else emptyList(),
                                                skipped = false,
                                            ),
                                        )
                                    },
                                )
                            }
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .background(if (draft.custom.isNotEmpty()) palette.hover else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (draft.custom.isNotEmpty()) palette.borderL2 else Color.Transparent,
                                        rowShape,
                                    )
                                    .padding(horizontal = DshSpacing.Lg, vertical = DshSpacing.Xl),
                                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(Modifier.size(20.dp).padding(top = DshSpacing.Xs)) {
                                    if (multi) {
                                        DshCheckbox(
                                            checked = draft.custom.isNotEmpty(),
                                            onCheckedChange = { checked ->
                                                replaceDraft(
                                                    draft.copy(
                                                        custom = if (checked) draft.custom else "",
                                                        skipped = false,
                                                    ),
                                                )
                                            },
                                            size = 14.dp,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = DshSettingIcons.Edit,
                                            contentDescription = null,
                                            tint = palette.labelTertiary,
                                            modifier = Modifier.padding(top = DshSpacing.Sm).size(12.dp),
                                        )
                                    }
                                }
                                QuestionAnswerField(
                                    value = draft.custom,
                                    placeholder = "输入你的答案",
                                    modifier = Modifier.weight(1f),
                                    onValue = { typed ->
                                        replaceDraft(
                                            draft.copy(
                                                custom = typed,
                                                selected = if (multi) draft.selected else emptyList(),
                                                skipped = false,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                QuestionCardFooter(
                    index = index,
                    count = questions.size,
                    last = last,
                    error = error,
                    answered = draft.answered,
                    onPrev = {
                        index -= 1
                        error = null
                    },
                    onNext = {
                        index += 1
                        error = null
                    },
                    onSkip = { skipQuestion() },
                    onSubmit = { continueFlow() },
                )
            }
        }
    }
}

/** dsh 的 AnswerField：占位符压在输入框上（textarea 的 placeholder 是 14/24 的 label-caption） */
@Composable
private fun QuestionAnswerField(
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDshPalette.current
    Box(modifier) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 14.sp,
                lineHeight = 24.sp,
                color = palette.labelCaption,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 24.sp, color = palette.labelPrimary),
            cursorBrush = SolidColor(palette.business),
        )
    }
}


/** 卡片头：小标题（可选）+ 问题正文，右侧是收起 / 放弃整组两个图标按钮 */
@Composable
private fun QuestionCardHeader(
    header: String?,
    question: String,
    minimized: Boolean,
    onToggleMinimized: () -> Unit,
    onSkipAll: () -> Unit,
) {
    val palette = LocalDshPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = DshSpacing.Xxxl, top = DshSpacing.Xxl),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            header?.takeIf { it.isNotBlank() }?.let { header ->
                Text(
                    text = header,
                    modifier = Modifier.padding(bottom = 5.dp),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = palette.labelTertiary,
                )
            }
            Text(
                text = question,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Medium,
                color = palette.labelPrimary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md)) {
            DshIconButton(
                icon = if (minimized) DshSettingIcons.ChevronUp else DshSettingIcons.ChevronDown,
                description = if (minimized) "展开问题卡片" else "收起问题卡片",
                onClick = onToggleMinimized,
                size = 24.dp,
            )
            DshIconButton(
                icon = DshSettingIcons.Close,
                description = "放弃整组问题",
                onClick = onSkipAll,
                size = 24.dp,
            )
        }
    }
}

/**
 * 一个选项行：多选是复选框、单选是序号方块；标签里的「(Recommended)」后缀拆成右侧的「推荐」徽标。
 *
 * 选中态的底色与描边只在**单选**时给整行（多选靠复选框自己），与 dsh 的 option row 一致。
 */
@Composable
private fun QuestionOptionRow(
    option: QuestionOption,
    optionIndex: Int,
    picked: Boolean,
    multi: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val rowShape = RoundedCornerShape(12.dp)
    val display = option.label.replace(RECOMMENDED_SUFFIX, "")
    val recommended = RECOMMENDED_SUFFIX.containsMatchIn(option.label)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(if (picked && !multi) palette.hover else Color.Transparent)
            .border(
                1.dp,
                if (picked && !multi) palette.borderL2 else Color.Transparent,
                rowShape,
            )
            .dshClickable(interactionSource = dshInteraction()) { onClick() }
            .padding(horizontal = DshSpacing.Lg, vertical = DshSpacing.Xl),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
        verticalAlignment = Alignment.Top,
    ) {
        if (multi) {
            Box(Modifier.size(20.dp).padding(top = DshSpacing.Xs)) {
                DshCheckbox(
                    checked = picked,
                    onCheckedChange = { onClick() },
                    size = 14.dp,
                )
            }
        } else {
            Box(
                Modifier
                    .padding(top = DshSpacing.Xs)
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.bgModulePlatform),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (optionIndex + 1).toString(),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.labelSecondary,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.Xs),
            ) {
                Text(
                    text = display,
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.labelPrimary,
                )
                if (recommended) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.navActive)
                            .padding(horizontal = DshSpacing.Md),
                    ) {
                        Text(
                            text = "推荐",
                            fontSize = 11.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.accent,
                        )
                    }
                }
            }
            option.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    color = palette.labelTertiary,
                )
            }
        }
    }
}

/** 卡片脚：上一题 / 题号 / 下一题 + 错误文案 + 「跳过本题」「提交 · 下一题」 */
@Composable
private fun QuestionCardFooter(
    index: Int,
    count: Int,
    last: Boolean,
    error: String?,
    answered: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onSubmit: () -> Unit,
) {
    val palette = LocalDshPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Xxl).padding(top = DshSpacing.Xxxl),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
        ) {
            DshIconButton(
                icon = DshSettingIcons.ChevronLeft,
                description = "上一题",
                onClick = onPrev,
                size = 24.dp,
                enabled = index > 0,
            )
            Text(
                text = (index + 1).toString() + " / " + count,
                modifier = Modifier.padding(horizontal = DshSpacing.Md),
                fontSize = 14.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
                color = palette.labelSecondary,
            )
            DshIconButton(
                icon = DshIcons.ChevronRight,
                description = "下一题",
                onClick = onNext,
                size = 24.dp,
                enabled = !last,
            )
        }
        Text(
            text = error.orEmpty(),
            modifier = Modifier.weight(1f).padding(horizontal = DshSpacing.Xl),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = palette.errorLabel,
            textAlign = TextAlign.End,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DshButton(text = "跳过本题", onClick = onSkip)
            DshButton(
                text = if (last) "提交" else "下一题",
                onClick = onSubmit,
                kind = DshButtonKind.Primary,
                enabled = answered,
            )
        }
    }
}
