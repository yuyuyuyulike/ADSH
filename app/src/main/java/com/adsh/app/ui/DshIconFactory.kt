package com.adsh.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 图标 path 的「一段」：SVG 的 `d` + 这一段的变换与绘制口径。
 *
 * 字段顺序与默认值来自 **DshSettingIcons / DshToolIcons** 的原实现（两者逐字相同，
 * CPD 抓到 43 行重复）；DshSidebarIcons 只要 `d/tx/ty/evenOdd`，是它的真子集。
 */
data class DshIconPart(
    val d: String,
    val tx: Float = 0f,
    val ty: Float = 0f,
    val evenOdd: Boolean = false,
    val stroke: Boolean = false,
    val strokeWidth: Float = 1.25f,
    /**
     * 描边透明度。默认 1f = 不传，与 Compose 的默认参数一致（第 2 阶段核实过：
     * `addPath` 的 fillAlpha / strokeAlpha 都是默认参数，显式写 1f 与省略等价）。
     *
     * 需要它的只有菜单里的「压缩」图标：dsh 的轨道弧 opacity 0.35（见 DshMenuIcons.Compact）。
     */
    val strokeAlpha: Float = 1f,
    /**
     * 这一段的缩放（默认 1f = 不缩放）。
     *
     * 放在**段**上而不是 `dshIcon` 的参数上，有个实打实的原因：`dshIcon` 的最后一个是 vararg，
     * Kotlin 不允许 vararg 之后再声明参数，插在它前面又会让全部调用点位置失配（试过，58 处全红）。
     * 语义上也说得通 —— 它就是这一段的变换，和 tx/ty 是同一类东西。
     *
     * 需要它的只有「文件面板」图标：`scale = -1f` + pivot 取 viewBox 中点 = 水平镜像。
     */
    val scale: Float = 1f,
    /** 缩放中心；**只在 scale ≠ 1 时有意义**，默认是 viewBox 中点 */
    val pivotX: Float = Float.NaN,
    val pivotY: Float = Float.NaN,
)

/** [DshIconPart] 的构造入口：84 个图标段一律写 `dshPart("M…")`，别再造局部工厂 */
fun dshPart(
    d: String,
    tx: Float = 0f,
    ty: Float = 0f,
    evenOdd: Boolean = false,
    stroke: Boolean = false,
    strokeWidth: Float = 1.25f,
    strokeAlpha: Float = 1f,
    scale: Float = 1f,
    pivotX: Float = Float.NaN,
    pivotY: Float = Float.NaN,
): DshIconPart = DshIconPart(d, tx, ty, evenOdd, stroke, strokeWidth, strokeAlpha, scale, pivotX, pivotY)

/**
 * dsh 图标的唯一装配器。
 *
 * 第二轮熵减前，六个图标对象各自抄了一份私有工厂（DshSettingIcons / DshToolIcons 逐字相同，
 * DshSidebarIcons 是子集，DshDockIcons 与 DshIcons.vector 逐字相同）—— 一共 5 份、约 120 行，
 * 任何一处口径变化（描边宽度、圆角连接、evenOdd 处理）都要改五遍，漏一处就是"某几个图标看着不对"。
 * 现在只有这一份：**想要不同的视觉口径就加参数，不要再复制装配逻辑**。
 *
 * 第二阶段收尾后，全库**只有这里**出现 `ImageVector.Builder`：六个图标对象各自的私有工厂
 * （icon / vector / filled / grouped / stroke）与菜单里那个手写 builder 全部并进来了。
 *
 * 与旧实现逐字等价：默认 viewBox 16、viewBox 同时当 dp 尺寸、每段先 addGroup(平移) 再 addPath，
 * 描边走 SolidColor.Black + StrokeJoin.Round，填充走 NonZero/EvenOdd；最后 clearGroup 退栈。
 */
fun dshIcon(name: String, viewBox: Float = 16f, vararg parts: DshIconPart): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = viewBox.dp,
        defaultHeight = viewBox.dp,
        viewportWidth = viewBox,
        viewportHeight = viewBox,
    )
    parts.forEach { part ->
        // 与 Compose 的 `addGroup` 有个易踩的差别：它的 pivot 默认是 0（左上角），
        // 对缩放/镜像必须显式给中点，否则图形会飞出画布。所以这里替调用方兜底成 viewBox 中点。
        builder.addGroup(
            translationX = part.tx,
            translationY = part.ty,
            scaleX = part.scale,
            pivotX = if (part.pivotX.isNaN()) viewBox / 2f else part.pivotX,
            pivotY = if (part.pivotY.isNaN()) viewBox / 2f else part.pivotY,
        )
        val nodes = PathParser().parsePathString(part.d).toNodes()
        if (part.stroke) {
            builder.addPath(
                pathData = nodes,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = part.strokeWidth,
                strokeAlpha = part.strokeAlpha,
                strokeLineJoin = StrokeJoin.Round,
            )
        } else {
            builder.addPath(
                pathData = nodes,
                pathFillType = if (part.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = SolidColor(Color.Black),
            )
        }
        builder.clearGroup()
    }
    return builder.build()
}