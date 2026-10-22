package com.adsh.app.ui.blocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 文件类型图标（dsh 的 FileTypeIcon，ui-primitives/FileTypeIcon.tsx）：**28×28 的文档轮廓**
 * + 类别色 + 白色类别标记；未知类型退化成去掉标记的灰色文档。
 *
 * 与 dsh 的差别（有意）：
 *  - dsh 对代码文件用 48 种语言彩色方标（58KB 内嵌 SVG artwork）；这里统一用 code 标记（尖括号）
 *    + 类别色，不搬那份矢量图；
 *  - html 在 dsh 是地球标记，这里同样归到 code 标记（显示上都是「代码文件」）。
 *
 * 工作区文件树、标签条、轮尾的改动 / 交付物卡片、待发附件共用这一枚 —— 文件图标只有这一处定义。
 */
private const val VIEW_BOX = 28f

/** dsh 的 --dsw-static-*（浅深两套取值相同，都是静态色，不跟主题走） */
private val DEEPSEEK_500 = Color(0xFF4176E6)
private val DEEPSEEK_450 = Color(0xFF5686FE)
private val GREEN_500 = Color(0xFF22C55E)
private val VIOLET = Color(0xFF8B76F6)
private val RED_600 = Color(0xFFEC1313)
private val AMBER_500 = Color(0xFFF59E0B)
private val NEUTRAL_BLUISH_300 = Color(0xFFCFD3D6)
private val NEUTRAL_400 = Color(0xFFA2A4A6)
private val WHITE_70 = Color(0xB3FFFFFF)

/** 文档轮廓（dsh 的 FILE_BODY）与折角（FILE_FOLD） */
private const val FILE_BODY =
    "M8.48924 28H19.5108C21.6479 28 22.7165 28 23.5594 27.6509C24.6833 27.1853 25.5762 26.2924 " +
        "26.0417 25.1685C26.3909 24.3256 26.3909 23.257 26.3909 21.1199V8.79443C26.3909 8.32877 " +
        "26.3909 8.09593 26.3471 7.87507C26.2887 7.58058 26.173 7.30042 26.0067 7.05048C25.882 " +
        "6.86303 25.7177 6.69799 25.3893 6.36792L20.0611 1.01354C19.7304 0.681235 19.5651 0.515081 " +
        "19.3769 0.38885C19.126 0.220541 18.8443 0.103463 18.5481 0.0443412C18.3259 0 18.0915 0 " +
        "17.6226 0H8.48924C6.35209 0 5.28351 0 4.4406 0.349145C3.31672 0.814671 2.4238 1.70759 " +
        "1.95828 2.83147C1.60913 3.67438 1.60913 4.74296 1.60913 6.88011V21.1199C1.60913 23.257 " +
        "1.60913 24.3256 1.95828 25.1685C2.4238 26.2924 3.31672 27.1853 4.4406 27.6509C5.28351 28 " +
        "6.35209 28 8.48924 28Z"

private const val FILE_FOLD =
    "M26.3909 7.37445L19.0525 0V3.77445C19.0525 4.89271 19.0525 5.45184 19.2352 5.89289C19.4788 " +
        "6.48096 19.946 6.94818 20.5341 7.19176C20.9751 7.37445 21.5342 7.37445 22.6525 7.37445H26.3909Z"

/** dsh 的代码标记（尖括号与斜杠）：三段描边，不做缩放 */
private val CODE_MARK_STROKES = listOf(
    "M10.0053 13.126L7.0236 16.3788C6.96052 16.4476 6.96052 16.5532 7.0236 16.622L10.0053 19.8748" to 1.35f,
    "M17.9941 13.126L20.9759 16.3788C21.039 16.4476 21.039 16.5532 20.9759 16.622L17.9941 19.8748" to 1.35f,
    "M15.2652 12.957L12.7344 20.0433" to 1.35f,
)

/** dsh 的表格标记（excel）：格线描边 */
private val EXCEL_MARK_STROKES = listOf(
    "M14 11.5H11.4C10.5599 11.5 10.1399 11.5 9.81901 11.6635C9.53677 11.8073 9.3073 12.0368 " +
        "9.16349 12.319C9 12.6399 9 13.0599 9 13.9V16.5M14 11.5H16.6C17.4401 11.5 17.8601 11.5 " +
        "18.181 11.6635C18.4632 11.8073 18.6927 12.0368 18.8365 12.319C19 12.6399 19 13.0599 19 " +
        "13.9V16.5M14 11.5V21.5M14 21.5H16.6C17.4401 21.5 17.8601 21.5 18.181 21.3365C18.4632 " +
        "21.1927 18.6927 20.9632 18.8365 20.681C19 20.3601 19 19.9401 19 19.1V16.5M14 21.5H11.4C10.5599 " +
        "21.5 10.1399 21.5 9.81901 21.3365C9.53677 21.1927 9.3073 20.9632 9.16349 20.681C9 20.3601 " +
        "9 19.9401 9 19.1V16.5M19 16.5H9" to 1.2f,
)

/** dsh 的图片标记（两座山 + 太阳） */
private val IMAGE_MARK = listOf(
    "M10.4212 15.9204C10.5756 15.6558 10.9579 15.6558 11.1123 15.9204L13.6493 20.2696C13.8048 " +
        "20.5362 13.6125 20.8711 13.3037 20.8711H8.22974C7.92102 20.8711 7.72868 20.5362 7.88423 " +
        "20.2696L10.4212 15.9204Z",
    "M15.4981 13.186C15.6505 12.9117 16.0451 12.9117 16.1975 13.186L20.1368 20.2769C20.2849 " +
        "20.5435 20.0922 20.8711 19.7872 20.8711H11.9084C11.6034 20.8711 11.4107 20.5435 11.5588 " +
        "20.2769L15.4981 13.186Z",
    "M11.8603 11.3997C11.8603 12.286 11.1418 13.0045 10.2555 13.0045C9.36924 13.0045 8.65076 " +
        "12.286 8.65076 11.3997C8.65076 10.5134 9.36924 9.79492 10.2555 9.79492C11.1418 9.79492 " +
        "11.8603 10.5134 11.8603 11.3997Z",
)

/** dsh 的 markdown 标记（M 加向下箭头） */
private val MARKDOWN_MARK = listOf(
    "M8.7588 19.5V14.6H9.8998L11.9298 17.932H11.3278L13.3018 14.6H14.4428L14.4568 19.5H13.1828L13.1688 " +
        "16.539H13.3858L11.9088 19.017H11.2928L9.7738 16.539H10.0398V19.5H8.7588ZM15.4375 19.5V14.6H17.7545C18.2958 " +
        "14.6 18.7718 14.7003 19.1825 14.901C19.5932 15.1017 19.9128 15.384 20.1415 15.748C20.3748 " +
        "16.112 20.4915 16.546 20.4915 17.05C20.4915 17.5493 20.3748 17.9833 20.1415 18.352C19.9128 " +
        "18.716 19.5932 18.9983 19.1825 19.199C18.7718 19.3997 18.2958 19.5 17.7545 19.5H15.4375ZM16.8235 " +
        "18.394H17.6985C17.9785 18.394 18.2212 18.3427 18.4265 18.24C18.6365 18.1327 18.7998 17.9787 " +
        "18.9165 17.778C19.0332 17.5727 19.0915 17.33 19.0915 17.05C19.0915 16.7653 19.0332 16.5227 " +
        "18.9165 16.322C18.7998 16.1213 18.6365 15.9697 18.4265 15.867C18.2212 15.7597 17.9785 15.706 " +
        "17.6985 15.706H16.8235V18.394Z",
)

/** dsh 的 PDF 标记（PD 字母） */
private val PDF_MARK = listOf(
    "M6.80616 19.5V14.6H9.04616C9.49416 14.6 9.87916 14.6723 10.2012 14.817C10.5278 14.9617 10.7798 " +
        "15.1717 10.9572 15.447C11.1345 15.7177 11.2232 16.0397 11.2232 16.413C11.2232 16.7817 11.1345 " +
        "17.1013 10.9572 17.372C10.7798 17.6427 10.5278 17.8527 10.2012 18.002C9.87916 18.1467 9.49416 " +
        "18.219 9.04616 18.219H7.57616L8.19216 17.617V19.5H6.80616ZM8.19216 17.764L7.57616 17.127H8.96216C9.2515 " +
        "17.127 9.46616 17.064 9.60616 16.938C9.75083 16.812 9.82316 16.637 9.82316 16.413C9.82316 " +
        "16.1843 9.75083 16.007 9.60616 15.881C9.46616 15.755 9.2515 15.692 8.96216 15.692H7.57616L8.19216 " +
        "15.055V17.764ZM11.8989 19.5V14.6H14.2159C14.7573 14.6 15.2333 14.7003 15.6439 14.901C16.0546 " +
        "15.1017 16.3743 15.384 16.6029 15.748C16.8363 16.112 16.9529 16.546 16.9529 17.05C16.9529 " +
        "17.5493 16.8363 17.9833 16.6029 18.352C16.3743 18.716 16.0546 18.9983 15.6439 19.199C15.2333 " +
        "19.3997 14.7573 19.5 14.2159 19.5H11.8989ZM13.2849 18.394H14.1599C14.4399 18.394 14.6826 " +
        "18.3427 14.8879 18.24C15.0979 18.1327 15.2613 17.9787 15.3779 17.778C15.4946 17.5727 15.5529 " +
        "17.33 15.5529 17.05C15.5529 16.7653 15.4946 16.5227 15.3779 16.322C15.2613 16.1213 15.0979 " +
        "15.9697 14.8879 15.867C14.6826 15.7597 14.4399 15.706 14.1599 15.706H13.2849V18.394ZM17.6821 " +
        "19.5V14.6H21.5251V15.671H19.0681V19.5H17.6821ZM18.9701 17.82V16.749H21.2311V17.82H18.9701Z",
)

/** dsh 的 PPT 标记（P） */
private val PPT_MARK = listOf(
    "M11.0132 20.5V13.5H14.2132C14.8532 13.5 15.4032 13.6033 15.8632 13.81C16.3299 14.0167 16.6899 " +
        "14.3167 16.9432 14.71C17.1966 15.0967 17.3232 15.5567 17.3232 16.09C17.3232 16.6167 17.1966 " +
        "17.0733 16.9432 17.46C16.6899 17.8467 16.3299 18.1467 15.8632 18.36C15.4032 18.5667 14.8532 " +
        "18.67 14.2132 18.67H12.1132L12.9932 17.81V20.5H11.0132ZM12.9932 18.02L12.1132 17.11H14.0932C14.5066 " +
        "17.11 14.8132 17.02 15.0132 16.84C15.2199 16.66 15.3232 16.41 15.3232 16.09C15.3232 15.7633 " +
        "15.2199 15.51 15.0132 15.33C14.8132 15.15 14.5066 15.06 14.0932 15.06H12.1132L12.9932 14.15V18.02Z",
)

/** dsh 的 Word 标记（W） */
private val WORD_MARK = listOf(
    "M10.5118 20.5L8.24179 13.5H10.2818L12.1918 19.56H11.1618L13.1718 13.5H14.9918L16.8918 19.56H15.9018L17.8718 " +
        "13.5H19.7618L17.4918 20.5H15.3718L13.7518 15.35H14.3218L12.6318 20.5H10.5118Z",
)

/** dsh 的视频标记（播放三角） */
private val VIDEO_MARK = listOf(
    "M17.5 14.634C18.1667 15.0189 18.1667 15.9811 17.5 16.366L11.5 19.8301C10.8333 20.215 10 " +
        "19.7339 10 18.9641L10 12.0359C10 11.2661 10.8333 10.785 11.5 11.1699L17.5 14.634Z",
)

/** 类别：dsh 的 FileType；标记与颜色的对应见 [artworkOf] */
private enum class Kind { CODE, MARKDOWN, IMAGE, EXCEL, WORD, PPT, PDF, VIDEO, OTHER }

private class Artwork(
    val color: Color,
    val fold: Color = WHITE_70,
    val fills: List<String> = emptyList(),
    val strokes: List<Pair<String, Float>> = emptyList(),
    /** dsh 的 FILE_MARK_TRANSFORM（1.12）/ LARGE_FILE_MARK_TRANSFORM（1.22）；code / excel 不缩放 */
    val markScale: Float = 1f,
    val markPivotY: Float = 16f,
)

private fun artworkOf(kind: Kind): Artwork = when (kind) {
    Kind.CODE -> Artwork(DEEPSEEK_500, strokes = CODE_MARK_STROKES)
    Kind.MARKDOWN -> Artwork(DEEPSEEK_500, fills = MARKDOWN_MARK, markScale = 1.22f)
    Kind.IMAGE -> Artwork(VIOLET, fills = IMAGE_MARK)
    Kind.EXCEL -> Artwork(GREEN_500, strokes = EXCEL_MARK_STROKES)
    Kind.WORD -> Artwork(DEEPSEEK_450, fills = WORD_MARK, markScale = 1.22f)
    Kind.PPT -> Artwork(AMBER_500, fills = PPT_MARK, markScale = 1.22f)
    Kind.PDF -> Artwork(RED_600, fills = PDF_MARK, markScale = 1.22f)
    Kind.VIDEO -> Artwork(VIOLET, fills = VIDEO_MARK)
    Kind.OTHER -> Artwork(NEUTRAL_BLUISH_300, fold = NEUTRAL_400)
}

/** dsh 的分类表（EXTENSION_TYPES + code-file-types；html 归 code，见类 KDoc） */
private val CODE_EXTENSIONS = setOf(
    "js", "mjs", "cjs", "jsx", "ts", "tsx", "mts", "cts", "json", "xml", "yaml", "yml",
    "toml", "ini", "cfg", "conf", "env", "css", "scss", "sass", "less", "vue", "svelte",
    "astro", "py", "pyw", "kt", "kts", "java", "go", "rs", "c", "h", "cpp", "hpp", "cc",
    "cxx", "cs", "php", "rb", "swift", "scala", "sh", "bash", "zsh", "lua", "pl", "pm",
    "r", "zig", "dart", "ex", "exs", "erl", "hrl", "hs", "clj", "cljs", "graphql", "gql",
    "proto", "sql", "bat", "cmd", "dockerfile", "cmake", "gradle", "makefile", "wasm", "m",
    "mm", "sol", "ps1", "vim", "tex", "sty", "lock", "properties",
)

private fun kindOf(name: String): Kind {
    val base = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
    if (base in setOf("readme", "changelog", "contributing")) return Kind.MARKDOWN
    if (base == "makefile" || base == "dockerfile") return Kind.CODE
    val ext = base.substringAfterLast('.', "")
    return when {
        ext == "md" || ext == "mdx" || ext == "markdown" -> Kind.MARKDOWN
        ext == "csv" || ext == "tsv" || ext == "xls" || ext == "xlsx" || ext == "xlsm" ||
            ext == "xlsb" || ext == "ods" -> Kind.EXCEL
        ext == "doc" || ext == "docx" || ext == "rtf" || ext == "odt" || ext == "pages" -> Kind.WORD
        ext == "ppt" || ext == "pptx" || ext == "key" -> Kind.PPT
        ext == "pdf" -> Kind.PDF
        ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "gif" || ext == "svg" ||
            ext == "webp" || ext == "avif" || ext == "bmp" || ext == "ico" || ext == "tif" ||
            ext == "tiff" || ext == "heic" || ext == "heif" -> Kind.IMAGE
        ext == "mp4" || ext == "mov" || ext == "m4v" || ext == "webm" || ext == "mkv" ||
            ext == "avi" || ext == "mpg" || ext == "mpeg" -> Kind.VIDEO
        ext in CODE_EXTENSIONS -> Kind.CODE
        else -> Kind.OTHER
    }
}

/** SVG path 的解析缓存：同一段 d 只解析一次（每帧绘制直接复用 Path） */
private val pathCache = HashMap<String, Path>()

private fun pathOf(d: String): Path = synchronized(pathCache) {
    pathCache.getOrPut(d) { PathParser().parsePathString(d).toPath() }
}

/** 通用「代码文件」图标（多文件改动卡片）：与 dsh 的 code 类别是同一枚 glyph */
@Composable
fun CodeTypeIcon(size: Dp = 20.dp) {
    FileTypeIcon(name = "code.ts", size = size)
}

/**
 * 文件类型图标。尺寸见调用点（文件树 16dp、标签条 16dp、附件卡 28dp、交付物 20dp、预览空态 36dp）。
 */
@Composable
fun FileTypeIcon(name: String, size: Dp = 16.dp) {
    val kind = remember(name) { kindOf(name) }
    val artwork = remember(kind) { artworkOf(kind) }
    Canvas(Modifier.size(size)) {
        val unit = this.size.minDimension / VIEW_BOX
        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
            drawPath(pathOf(FILE_BODY), artwork.color)
            drawPath(pathOf(FILE_FOLD), artwork.fold)
            if (artwork.fills.isNotEmpty() || artwork.strokes.isNotEmpty()) {
                // dsh 的 mark transform = translate(pivot) scale(k) translate(-pivot)。**pivot 必须显式给**：
                // DrawTransform.scale 的默认 pivot 是「当前像素画布的 center」，在外层已按 viewBox
                // 缩放过的坐标系里它落在 38.5 而不是 14 —— 标记会整体左移并被裁掉（PDF / W 的字母缺半边）。
                withTransform({
                    scale(artwork.markScale, artwork.markScale, pivot = Offset(14f, artwork.markPivotY))
                }) {
                    artwork.fills.forEach { drawPath(pathOf(it), Color.White) }
                    artwork.strokes.forEach { (d, width) ->
                        drawPath(
                            path = pathOf(d),
                            color = Color.White,
                            style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
        }
    }
}
