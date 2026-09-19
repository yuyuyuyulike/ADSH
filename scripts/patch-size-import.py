import io, sys
P = '/mnt/d/WSN2005/Android1/App/ADSH/app/src/main/java/com/adsh/app/ui/SettingsScreen.kt'
s = io.open(P, encoding='utf-8').read()
if 'import androidx.compose.foundation.layout.size' not in s:
    anchor = 'import androidx.compose.foundation.layout.Spacer'
    if anchor not in s:
        print('!! 找不到 Spacer import'); sys.exit(1)
    s = s.replace(anchor, 'import androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.size', 1)
io.open(P, 'w', encoding='utf-8').write(s)
print('import size 已补')
