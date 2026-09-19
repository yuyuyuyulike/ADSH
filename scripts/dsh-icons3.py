#!/usr/bin/env python3
"""从 dsh 前端产物里抽图标，生成 ui/DshSettingIcons.kt（设置页的行图标 / 外观立方 / 分节图标）。

dsh 的图标都是 16（或 14）见方的 SVG：path / circle / ellipse，
fill 用 currentColor 或 stroke 描边。这里逐字搬运，不做手绘近似。
"""
import os
import re
import sys

FRONT = '/mnt/d/WSN2005/node-v24.19.0-win-x64/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets/index-BKQ_L1z6.js'
ROOT = '/mnt/d/WSN2005/Android1/App/ADSH'

data = open(FRONT, encoding='utf-8', errors='replace').read()


def alias_of(export_name):
    i = data.find(export_name + ':')
    if i < 0:
        return None
    seg = data[i:i + 20000]
    m = re.match(r'[A-Za-z0-9_]+:([A-Za-z0-9_$]+)', seg)
    return m.group(1) if m else None


def body_of(alias):
    # $ 开头的别名（压缩后的变量名）没有词边界，直接用字面量找
    prefix = r'\b' if alias[:1].isalnum() or alias[:1] == '_' else ''
    m = re.search(prefix + re.escape(alias) + r'\s*=\s*\(', data)
    if not m:
        raise SystemExit('未找到 ' + alias)
    chunk = data[m.start():m.start() + 6000]
    nxt = re.search(r',[A-Za-z_$][\w$]{0,4}=\(\{size:', chunk[10:])
    if nxt:
        chunk = chunk[:10 + nxt.start()]
    return chunk


def parse(alias):
    body = body_of(alias)
    vb = re.search(r'viewBox:"([^"]+)"', body)
    view = float(vb.group(1).split()[2]) if vb else 16.0
    parts = []
    for pm in re.finditer(r'd\.jsxs?\("(path|circle|ellipse)",\{(.*?)\}\)', body, re.S):
        kind, seg = pm.group(1), pm.group(2)
        tx = ty = 0.0
        tm = re.search(r'transform:"translate\(([-\d.]+) ([-\d.]+)\)"', seg)
        if tm:
            tx, ty = float(tm.group(1)), float(tm.group(2))
        stroke = 'stroke:"currentColor"' in seg
        sw = 0.0
        sm = re.search(r'strokeWidth:"([\d.]+)"', seg)
        if sm:
            sw = float(sm.group(1))
        even = 'evenodd' in seg
        if kind == 'path':
            dm = re.search(r'd:"([^"]+)"', seg)
            if not dm:
                continue
            d = dm.group(1)
        elif kind == 'circle':
            cx = float(re.search(r'cx:"([-\d.]+)"', seg).group(1))
            cy = float(re.search(r'cy:"([-\d.]+)"', seg).group(1))
            r = float(re.search(r'r:"([-\d.]+)"', seg).group(1))
            d = ('M%g %gA%g %g 0 1 1 %g %gA%g %g 0 1 1 %g %gZ'
                 % (cx - r, cy, r, r, cx + r, cy, r, r, cx - r, cy))
        else:
            cx = float(re.search(r'cx:"([-\d.]+)"', seg).group(1))
            cy = float(re.search(r'cy:"([-\d.]+)"', seg).group(1))
            rx = float(re.search(r'rx:"([-\d.]+)"', seg).group(1))
            ry = float(re.search(r'ry:"([-\d.]+)"', seg).group(1))
            d = ('M%g %gA%g %g 0 1 1 %g %gA%g %g 0 1 1 %g %gZ'
                 % (cx - rx, cy, rx, ry, cx + rx, cy, rx, ry, cx - rx, cy))
        parts.append((d, tx, ty, even, stroke, sw))
    return view, parts


# (dsh 导出名, Kotlin 名, 注释)
ICONS = [
    ('IconSettingsOutline16', 'Settings', 'IconSettingsOutline16：通用设置分节 / 关于'),
    ('IconDataOutline16', 'Data', 'IconDataOutline16：模型分节 / 提供方'),
    ('IconPersonalizationOutline16', 'Personalization', 'IconPersonalizationOutline16：功能（插件）分节'),
    ('IconLightOutline16', 'Light', 'IconLightOutline16：外观·浅色'),
    ('IconDarkOutline16', 'Dark', 'IconDarkOutline16：外观·深色'),
    ('IconFollowsystemOutline16', 'FollowSystem', 'IconFollowsystemOutline16：外观·跟随系统'),
    ('IconEnhanceOutline16', 'Enhance', 'IconEnhanceOutline16：字号大小'),
    ('IconBrowseOutline16', 'Browse', 'IconBrowseOutline16：对话显示'),
    ('IconSendOutline16', 'Send', 'IconSendOutline16：繁忙时的发送行为'),
    ('IconFolderOpenOutline16', 'FolderOpen', 'IconFolderOpenOutline16：工作区'),
    ('IconListPenOutline16', 'ListPen', 'IconListPenOutline16：系统提示词附录'),
    ('IconQuestionOutline14', 'Question', 'IconQuestionOutline14：帮助'),
    ('IconGlobeOutline14', 'Globe', 'IconGlobeOutline14：网页搜索'),
    ('IconApiOutline14', 'Api', 'IconApiOutline14：终端（dsh 的 bash 工具图标）'),
    ('IconChevronUpOutline14', 'ChevronUp', 'IconChevronUpOutline14：字号步进上'),
    ('IconChevronDownOutline14', 'ChevronDown', 'IconChevronDownOutline14：下拉 / 展开'),
    ('IconRefreshOutline16', 'Refresh', 'IconRefreshOutline16：获取可用模型'),
    ('IconCheckOutline16', 'Check', 'IconCheckOutline16：已选中'),
    ('IconCloseOutline16', 'Close', 'IconCloseOutline16：设置面板的关闭 / 端点的删除'),
    ('IconChevronLeftOutline14', 'ChevronLeft', 'IconChevronLeftOutline14：返回'),
    ('IconEditOutline16', 'Edit', 'IconEditOutline16：编辑提供方'),
    ('IconTrashOutline16', 'Trash', 'IconTrashOutline16：删除模型 / 提供方'),
    ('IconWarningOutline16', 'Warning', 'IconWarningOutline16：警告'),
]

out = []
out.append('package com.adsh.app.ui')
out.append('')
out.append('import androidx.compose.ui.graphics.Color')
out.append('import androidx.compose.ui.graphics.PathFillType')
out.append('import androidx.compose.ui.graphics.SolidColor')
out.append('import androidx.compose.ui.graphics.StrokeJoin')
out.append('import androidx.compose.ui.graphics.vector.ImageVector')
out.append('import androidx.compose.ui.graphics.vector.PathParser')
out.append('import androidx.compose.ui.unit.dp')
out.append('')
out.append('/**')
out.append(' * 设置页用的 dsh 图标：path 逐字取自 dsh 前端产物')
out.append(' * （dsh-web-frontend/dist/assets/index-*.js 里的 dsh-client-ui-primitives）。')
out.append(' *')
out.append(' * 由 scripts/dsh-icons3.py 生成，不要手改。')
out.append(' */')
out.append('object DshSettingIcons {')
out.append('')
out.append('    private data class Part(')
out.append('        val d: String,')
out.append('        val tx: Float = 0f,')
out.append('        val ty: Float = 0f,')
out.append('        val evenOdd: Boolean = false,')
out.append('        val stroke: Boolean = false,')
out.append('        val strokeWidth: Float = 1.25f,')
out.append('    )')
out.append('')
out.append('    private fun icon(name: String, viewBox: Float, vararg parts: Part): ImageVector {')
out.append('        val builder = ImageVector.Builder(')
out.append('            name = name,')
out.append('            defaultWidth = viewBox.dp,')
out.append('            defaultHeight = viewBox.dp,')
out.append('            viewportWidth = viewBox,')
out.append('            viewportHeight = viewBox,')
out.append('        )')
out.append('        parts.forEach { part ->')
out.append('            builder.addGroup(translationX = part.tx, translationY = part.ty)')
out.append('            val nodes = PathParser().parsePathString(part.d).toNodes()')
out.append('            if (part.stroke) {')
out.append('                builder.addPath(')
out.append('                    pathData = nodes,')
out.append('                    stroke = SolidColor(Color.Black),')
out.append('                    strokeLineWidth = part.strokeWidth,')
out.append('                    strokeLineJoin = StrokeJoin.Round,')
out.append('                )')
out.append('            } else {')
out.append('                builder.addPath(')
out.append('                    pathData = nodes,')
out.append('                    pathFillType = if (part.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,')
out.append('                    fill = SolidColor(Color.Black),')
out.append('                )')
out.append('            }')
out.append('            builder.clearGroup()')
out.append('        }')
out.append('        return builder.build()')
out.append('    }')
out.append('')

for export, kotlin, doc in ICONS:
    alias = alias_of(export)
    if alias is None:
        print('!! ' + export + ' 没有别名', file=sys.stderr)
        continue
    view, parts = parse(alias)
    if not parts:
        print('!! ' + export + ' 没有 path', file=sys.stderr)
        continue
    out.append('    /** ' + doc + ' */')
    out.append('    val ' + kotlin + ': ImageVector by lazy {')
    out.append('        icon(')
    out.append('            "DshSet' + kotlin + '",')
    out.append('            ' + repr(view).replace(".0", "f") + ',')
    for d, tx, ty, even, stroke, sw in parts:
        out.append('            Part(')
        out.append('                "' + d + '",')
        if tx or ty or even or stroke:
            out.append('                tx = ' + repr(tx) + 'f, ty = ' + repr(ty) + 'f,')
            if even:
                out.append('                evenOdd = true,')
            if stroke:
                out.append('                stroke = true, strokeWidth = ' + repr(sw) + 'f,')
        out.append('            ),')
    out.append('        )')
    out.append('    }')
    out.append('')
    print('ok ' + export + ' -> ' + kotlin + ' (' + str(len(parts)) + ' parts, viewBox ' + str(view) + ')')

out.append('}')

path = os.path.join(ROOT, 'app/src/main/java/com/adsh/app/ui/DshSettingIcons.kt')
open(path, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('写出 ' + path)
