import re
p = 'app/src/main/java/com/adsh/app/ui/ChatScreen.kt'
s = open(p, encoding='utf-8').read()
# 删除旧的整形块（到 Hero 之前）
a = s.index('// ------------------------------------------------------------------ （整形逻辑见 TurnList.kt）')
b = s.index('/** dsh 的空态：鲸鱼')
s = s[:a] + s[b:]
# 删除旧的过程区块（到「消息」之前）
a = s.index('// ------------------------------------------------------------------ （过程区渲染见 TurnRail.kt）')
b = s.index('// ------------------------------------------------------------------ 消息')
s = s[:a] + s[b:]
open(p, 'w', encoding='utf-8').write(s)
print(len(s.splitlines()))
