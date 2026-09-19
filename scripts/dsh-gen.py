import re, os, sys

FRONT = '/mnt/d/WSN2005/node-v24.19.0-win-x64/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets/index-BKQ_L1z6.js'
ROOT = '/mnt/d/WSN2005/Android1/App/ADSH'

data = open(FRONT, encoding='utf-8', errors='replace').read()

def cut(sym):
    """取符号定义的正文（到下一个组件定义之前）"""
    pats = [
        r'(?<![A-Za-z0-9_$])' + re.escape(sym) + r'\s*=\s*\(\{',
        r'function\s+' + re.escape(sym) + r'\(',
    ]
    m = None
    for p in pats:
        m = re.search(p, data)
        if m:
            break
    if not m:
        raise SystemExit('未找到 ' + sym)
    start = m.start()
    nxt = re.compile(r',[A-Za-z_$][\w$]{0,4}=\(\{size:|function\s+[A-Za-z_$][\w$]{0,4}\(').search(data, m.end())
    end = nxt.start() if nxt else start + 12000
    return data[start:end]

def viewbox(body):
    m = re.search(r'viewBox:"([^"]+)"', body)
    return m.group(1)

def parts(body):
    """抽出 (translate_x, translate_y, fillRule, d) 列表"""
    out = []
    for pm in re.finditer(r'd\.jsxs?\("path",\{(.*?)\}\)', body, re.S):
        seg = pm.group(1)
        dm = re.search(r'd:"([^"]+)"', seg)
        if not dm:
            continue
        d = dm.group(1)
        tx = ty = 0.0
        tm = re.search(r'transform:"translate\(([-\d.]+) ([-\d.]+)\)"', seg)
        if tm:
            tx, ty = float(tm.group(1)), float(tm.group(2))
        even = 'evenodd' in seg
        out.append((tx, ty, even, d))
    return out

# 需要的图标：符号 → (Kotlin 名, 注释, 类型)
ICONS = [
    ('Zh', 'Ellipsis',  'IconEllipsisOutline16（会话行右侧的三点菜单）', '16'),
    ('Wh', 'Branch',    'IconBranchOutline16（分叉会话）', '16'),
    ('pp', 'Trash',     'IconTrashOutline16（删除工作区 / 会话）', '16'),
    ('ip', 'Edit',      'IconEditOutline16（重命名）', '16'),
    ('Qh', 'TriangleRight', 'IconTriangleRightFill14（工作区行的展开三角）', '14'),
    ('bp', 'ProjectAdd', 'IconProjectAddOutline16（添加工作区）', '16'),
    ('Mp', 'FolderOpenOutline', 'IconFolderOpenOutline16', '16'),
    ('Fh', 'NewChat',   'IconNewChatOutline16（侧栏新会话按钮）', '16'),
]

def kotlin_name(s):
    return s

lines = []
lines.append('package com.adsh.app.ui')
lines.append('')
lines.append('import androidx.compose.ui.graphics.Color')
lines.append('import androidx.compose.ui.graphics.PathFillType')
lines.append('import androidx.compose.ui.graphics.SolidColor')
lines.append('import androidx.compose.ui.graphics.vector.ImageVector')
lines.append('import androidx.compose.ui.graphics.vector.PathParser')
lines.append('import androidx.compose.ui.unit.dp')
lines.append('')
lines.append('/**')
lines.append(' * 抽屉 / 会话列表用到的 dsh 图标：path 数据逐字取自 dsh 前端产物')
lines.append(' * （dsh-web-frontend/dist/assets/index-*.js 里的 dsh-client-ui-primitives）。')
lines.append(' *')
lines.append(' * 由 scripts/dsh-gen.py 生成，不要手改。')
lines.append(' */')
lines.append('object DshSidebarIcons {')
lines.append('')
lines.append('    private data class Part(val d: String, val tx: Float = 0f, val ty: Float = 0f, val evenOdd: Boolean = false)')
lines.append('')
lines.append('    private fun icon(name: String, viewBox: Float, vararg parts: Part): ImageVector {')
lines.append('        val builder = ImageVector.Builder(')
lines.append('            name = name,')
lines.append('            defaultWidth = viewBox.dp,')
lines.append('            defaultHeight = viewBox.dp,')
lines.append('            viewportWidth = viewBox,')
lines.append('            viewportHeight = viewBox,')
lines.append('        )')
lines.append('        parts.forEach { part ->')
lines.append('            builder.addGroup(translationX = part.tx, translationY = part.ty)')
lines.append('            builder.addPath(')
lines.append('                pathData = PathParser().parsePathString(part.d).toNodes(),')
lines.append('                pathFillType = if (part.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,')
lines.append('                fill = SolidColor(Color.Black),')
lines.append('            )')
lines.append('            builder.clearGroup()')
lines.append('        }')
lines.append('        return builder.build()')
lines.append('    }')
lines.append('')

for sym, name, doc, _ in ICONS:
    body = cut(sym)
    vb = viewbox(body)
    ps = parts(body)
    if not ps:
        print('!! ' + sym + ' 没有 path', file=sys.stderr)
        continue
    size = float(vb.split()[2])
    lines.append('    /** ' + doc + ' */')
    lines.append('    val ' + name + ': ImageVector by lazy {')
    lines.append('        icon(')
    lines.append('            "' + 'Dsh' + name + '",')
    lines.append('            ' + repr(size) + 'f + 0f,'.replace(' + 0f', ''))
    for tx, ty, even, d in ps:
        lines.append('            Part(')
        lines.append('                "' + d + '",')
        if tx or ty or even:
            lines.append('                tx = ' + repr(tx) + 'f, ty = ' + repr(ty) + 'f, evenOdd = ' + ('true' if even else 'false') + ',')
        lines.append('            ),')
    lines.append('        )')
    lines.append('    }')
    lines.append('')

lines.append('}')

out = os.path.join(ROOT, 'app/src/main/java/com/adsh/app/ui/DshSidebarIcons.kt')
open(out, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print('写出 ' + out + '，' + str(len(lines)) + ' 行')

# ---- logo 词标（BrandWordmark，includeMark=false → viewBox 26 0 156 24） ----
body = cut('uC')
ps = parts(body)
print('词标 path 数 = ' + str(len(ps)) + '，总长 ' + str(sum(len(p[3]) for p in ps)))
xml = []
xml.append('<?xml version="1.0" encoding="utf-8"?>')
xml.append('<!-- dsh 官方品牌词标（BrandWordmark includeMark=false）：path 逐字取自 dsh 前端产物 -->')
xml.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
xml.append('    android:width="156dp"')
xml.append('    android:height="24dp"')
xml.append('    android:viewportWidth="156"')
xml.append('    android:viewportHeight="24">')
xml.append('    <group android:translateX="-26">')
for tx, ty, even, d in ps:
    xml.append('        <path')
    xml.append('            android:fillColor="#FF000000"')
    if even:
        xml.append('            android:fillType="evenOdd"')
    xml.append('            android:pathData="' + d + '" />')
xml.append('    </group>')
xml.append('</vector>')
out2 = os.path.join(ROOT, 'app/src/main/res/drawable/ic_dsh_wordmark.xml')
open(out2, 'w', encoding='utf-8').write('\n'.join(xml) + '\n')
print('写出 ' + out2)
