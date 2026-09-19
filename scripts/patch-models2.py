import io, sys
R = '/mnt/d/WSN2005/Android1/App/ADSH/app/src/main/java/com/adsh/app/'
def load(p): return io.open(R + p, encoding='utf-8').read()
def save(p, s): io.open(R + p, 'w', encoding='utf-8').write(s)
def rep(s, old, new):
    if old not in s:
        print('!! 未匹配: ' + old[:70].replace('\n', ' | ')); sys.exit(1)
    return s.replace(old, new, 1)

# 1) DshModel 数据类
p = 'core/data/SettingsStore.kt'
s = load(p)
head = s.split('\n', 1)
idx = s.index('class SettingsStore')
s = s[:idx] + '''/** dsh 模型目录里的一条：id / 展示名 / 一句说明（说明来自 dsh 的中文字典） */
data class DshModel(val id: String, val name: String, val description: String)

''' + s[idx:]
save(p, s)

# 2) ChatViewModel：模型与推理等级都按 dsh
p = 'ui/ChatViewModel.kt'
s = load(p)
s = rep(s, '''    val availableModels: List<String> get() = listOf("deepseek-chat", "deepseek-flash", "deepseek-reasoner")

    /** 推理等级（对齐 dsh 的 reasoning_effort 菜单）：空串 = 提供方默认 */
    val availableEfforts: List<Pair<String, String>>
        get() = listOf("" to "默认", "low" to "低", "medium" to "中", "high" to "高")''',
'''    /** 模型清单：dsh 的 deepseek-official 目录（旧 id 不再出现） */
    val availableModels: List<String>
        get() = com.adsh.app.core.data.SettingsStore.MODEL_CATALOG.map { it.id }

    /**
     * 推理等级（dsh 的 reasoning_effort 菜单：Off / Low / High / Max，另有提供方默认）。
     * 空串 = 不指定，off 会走 thinking=disabled。
     */
    val availableEfforts: List<Pair<String, String>>
        get() = listOf(
            "" to "默认",
            "off" to "关闭",
            "low" to "低",
            "high" to "高",
            "max" to "最高",
        )''')
save(p, s)

# 3) 设置页：模型候选改成 dsh 目录（带说明）
p = 'ui/SettingsScreen.kt'
s = load(p)
s = rep(s, '''        SettingCard(title = "模型", description = "本会话使用的模型；推理等级由提供方决定。") {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型 ID") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("deepseek-chat", "deepseek-flash", "deepseek-reasoner").forEach { candidate ->
                    TextButton(onClick = { model = candidate }) { Text(candidate, fontSize = 12.sp) }
                }
            }
        }''',
'''        SettingCard(
            title = "模型",
            description = "清单与 dsh 的 deepseek-official 目录一致（DeepSeek-V4 系列）；点一下即填入模型 ID，也可以自己敲。",
        ) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型 ID") },
                modifier = Modifier.fillMaxWidth(),
            )
            com.adsh.app.core.data.SettingsStore.MODEL_CATALOG.forEach { item ->
                val active = item.id == model
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { model = item.id }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.name + "  ·  " + item.id,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(item.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (active) Icon(DshIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }''')
save(p, s)

print('模型配置 UI 完成')
