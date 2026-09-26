package com.adsh.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * dsh 的图标：path 数据逐字取自 dsh 前端产物（dsh-web-frontend/dist/assets/index-*.js）。
 *
 * 本轮补齐输入框上方横窗与弹窗用到的那几个：任务清单（TodoPanel 的 lead）、
 * 警告（RiskConfirmation 的 warningIcon）。
 */
object DshDockIcons {

    private fun vector(
        name: String,
        viewBox: Float,
        paths: List<Triple<String, Boolean, Float>>,
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = viewBox.dp,
            defaultHeight = viewBox.dp,
            viewportWidth = viewBox,
            viewportHeight = viewBox,
        )
        paths.forEach { (data, stroked, strokeWidth) ->
            val nodes = PathParser().parsePathString(data).toNodes()
            if (stroked) {
                builder.addPath(
                    pathData = nodes,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = strokeWidth,
                    strokeLineJoin = StrokeJoin.Round,
                )
            } else {
                builder.addPath(pathData = nodes, fill = SolidColor(Color.Black))
            }
        }
        return builder.build()
    }

    /** DshChecklistOutline14 */
    val Checklist: ImageVector by lazy {
        vector(
            "DshChecklistOutline14",
            14f,
            listOf(
                Triple(
                    "M13.3277 9.69629V10.976H7.28086V9.69629H13.3277Z",
                    false,
                    0.0f,
                ),
                Triple(
                    "M13.3277 2.97256V4.25225H7.28086V2.97256H13.3277Z",
                    false,
                    0.0f,
                ),
                Triple(
                    "M4.64512 10.336C4.64505 9.62755 4.07081 9.05322 3.3623 9.05322C2.65386 9.05329 2.07956 9.62759 2.07949 10.336C2.07949 11.0445 2.65382 11.6188 3.3623 11.6188C4.07085 11.6188 4.64512 11.0446 4.64512 10.336ZM5.92559 10.336C5.92559 11.7515 4.77777 12.8993 3.3623 12.8993C1.94689 12.8993 0.799805 11.7515 0.799805 10.336C0.799871 8.92066 1.94693 7.7736 3.3623 7.77354C4.77773 7.77354 5.92552 8.92062 5.92559 10.336Z",
                    false,
                    0.0f,
                ),
                Triple(
                    "M4.64531 3.6123C4.6453 2.90382 4.07098 2.32949 3.3625 2.32949C2.65403 2.32951 2.0797 2.90383 2.07969 3.6123C2.07969 4.32079 2.65402 4.8951 3.3625 4.89512C4.07099 4.89512 4.64531 4.3208 4.64531 3.6123ZM5.925 3.6123C5.925 5.02772 4.77792 6.1748 3.3625 6.1748C1.9471 6.17479 0.8 5.02771 0.8 3.6123C0.800013 2.19691 1.9471 1.04982 3.3625 1.0498C4.77791 1.0498 5.92499 2.1969 5.925 3.6123Z",
                    false,
                    0.0f,
                ),
            ),
        )
    }

    /** DshWarningOutline14 */
    val Warning: ImageVector by lazy {
        vector(
            "DshWarningOutline14",
            14f,
            listOf(
                Triple(
                    "M6.3002 3.32843L7.69986 3.32843L7.69986 7.79657H6.3002L6.3002 3.32843Z",
                    false,
                    0.0f,
                ),
                Triple(
                    "M6.3002 9.01935H7.69986V10.6711H6.3002V9.01935Z",
                    false,
                    0.0f,
                ),
                Triple(
                    "M12.6328 6.99976C12.6328 3.88874 10.111 1.36694 7 1.36694C3.88899 1.36695 1.3672 3.88875 1.36719 6.99976C1.36719 10.1108 3.88899 12.6326 7 12.6326C10.111 12.6326 12.6328 10.1108 12.6328 6.99976ZM13.8582 6.99976C13.8582 10.7873 10.7876 13.8579 7 13.8579C3.21244 13.8579 0.141846 10.7873 0.141846 6.99976C0.141857 3.2122 3.21245 0.141612 7 0.141602C10.7876 0.141602 13.8581 3.21219 13.8582 6.99976Z",
                    false,
                    0.0f,
                ),
            ),
        )
    }
}
