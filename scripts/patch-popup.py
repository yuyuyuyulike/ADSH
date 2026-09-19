import io, sys
P = '/mnt/d/WSN2005/Android1/App/ADSH/app/src/main/java/com/adsh/app/ui/Composer.kt'
s = io.open(P, encoding='utf-8').read()
old = '''    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable),
    ) { content() }'''
new = '''    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        // 不抢焦点（否则输入法会掉）之后，Popup 自己也会监听「外部触摸」来关闭：
        // 打开菜单的那一下点击正好在弹窗外面，会被判定成外部触摸，菜单一闪就没。
        // 所以两种自动关闭都关掉，「点空白关闭」交给上层自己的拦截层（ChatScreen / 抽屉）。
        properties = PopupProperties(
            focusable = focusable,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) { content() }'''
if old not in s:
    print('!! 未匹配 Popup'); sys.exit(1)
io.open(P, 'w', encoding='utf-8').write(s.replace(old, new, 1))
print('Popup 自动关闭已关闭')
