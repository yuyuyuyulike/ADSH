#!/usr/bin/env python3
"""从 pi-ai 的提供方目录生成 ADSH 的「模型 -> 推理等级」表。

dsh 的推理等级不是全局固定的：dsh-llm-pi-ai 用 pi-ai 的
getSupportedThinkingLevels(model) 逐模型算出可选等级（dsh-llm-deepseek 则是固定的
Off/Low/High/Max），客户端菜单里的名称就来自这个列表。ADSH 没有 pi-ai 运行时，
所以把这张表在构建期烘进 APK。

用法（数据来自本机 dsh profile 里装的 pi-ai）：
    python3 scripts/gen-reasoning-levels.py > app/src/main/java/com/adsh/app/core/data/ModelThinkingLevels.kt

生成物带「由脚本生成」头，不要手改。
"""
import json
import os
import sys

DATA = os.environ.get(
    "PI_AI_DATA",
    os.path.expanduser("~/.dsh/profiles/node_modules/@earendil-works/pi-ai/dist/providers/data"),
)

# ADSH 的提供方下拉（ProviderCatalog.presets）里有的那些 route
PROVIDERS = [
    "openai", "openrouter", "moonshotai-cn", "moonshotai", "zai-coding-cn", "zai",
    "qwen-token-plan-cn", "xiaomi", "xai", "groq", "mistral", "together", "fireworks",
    "nvidia", "huggingface", "cerebras", "baseten", "ant-ling",
]

# pi-ai 的 EXTENDED_THINKING_LEVELS 顺序（位序就是这里的下标）
EXTENDED = ["off", "minimal", "low", "medium", "high", "xhigh", "max"]

# ADSH 只会用 OpenAI 兼容协议发请求：同一模型出现在多个 api 段时优先取它
API_PREFERENCE = ["openai-completions", "anthropic-messages"]


def levels_mask(model):
    """pi-ai getSupportedThinkingLevels 的位掩码版本。

    没有 reasoning 元数据的模型（pi-ai 的 reasoning: false）给 **0**：
    dsh 的 dsh-llm-pi-ai 在这种情况下整个 reasoning 字段都不下发，
    客户端菜单只剩「当前模型未提供推理等级」—— 不是「只有 Off 一档」。
    """
    if not model.get("reasoning"):
        return 0
    tlm = model.get("thinkingLevelMap") or {}
    mask = 0
    for i, level in enumerate(EXTENDED):
        if level in tlm and tlm[level] is None:
            continue
        if level in ("xhigh", "max") and level not in tlm:
            continue
        mask |= 1 << i
    return mask


def collect():
    per_provider = {}
    bare_seen = {}
    for pid in PROVIDERS:
        path = os.path.join(DATA, pid + ".json")
        if not os.path.exists(path):
            sys.stderr.write("missing catalog: %s\n" % path)
            continue
        with open(path) as handle:
            catalog = json.load(handle)
        entries = {}
        for api in API_PREFERENCE + [k for k in catalog if k not in API_PREFERENCE]:
            for mid, model in catalog.get(api, {}).items():
                entries.setdefault(mid, levels_mask(model))
        for mid, mask in entries.items():
            per_provider["%s/%s" % (pid, mid)] = mask
            bare_seen.setdefault(mid, set()).add(mask)
    bare = {mid: next(iter(masks)) for mid, masks in bare_seen.items() if len(masks) == 1}
    return per_provider, bare


def emit_map(name, table, chunk=150):
    keys = sorted(table)
    out = []
    for start in range(0, len(keys), chunk):
        part = keys[start:start + chunk]
        out.append("    private fun %s%d(): Map<String, Int> = mapOf(" % (name, start // chunk))
        for key in part:
            out.append('        "%s" to 0x%02X,' % (key, table[key]))
        out.append("    )")
        out.append("")
    return out, len(keys)


def main():
    per_provider, bare = collect()
    lines = []
    lines.append("// 由 scripts/gen-reasoning-levels.py 从 pi-ai 的提供方目录生成 —— 不要手改。")
    lines.append("//")
    lines.append("// dsh 的推理等级逐模型不同：dsh-llm-pi-ai 用 pi-ai 的 getSupportedThinkingLevels(model)")
    lines.append("// 算出该模型支持的等级，菜单里的名称就是这些。ADSH 没有 pi-ai 运行时，所以在这里烘一张表。")
    lines.append("// 生成来源：@earendil-works/pi-ai 的 dist/providers/data/*.json（ADSH 提供方下拉里的那些 route）。")
    lines.append("package com.adsh.app.core.data")
    lines.append("")
    lines.append("/**")
    lines.append(" * 每个模型的推理等级位掩码：位 i 置位 = EXTENDED 的第 i 个等级受支持；")
    lines.append(" * **掩码 0 = 这个模型没有推理能力**（pi-ai 的 reasoning: false，dsh 不下发 reasoning 字段）。")
    lines.append(" * 键是「提供方/模型」；BY_BARE_ID 是「这个模型 id 在所有提供方里等级集都一样」时的简写键。")
    lines.append(" * 表里没有的模型返回 null —— 调用方按约定退回 DeepSeek 的默认等级名。")
    lines.append(" */")
    lines.append("internal object ModelThinkingLevels {")
    lines.append("")
    lines.append("    /** pi-ai 的 EXTENDED_THINKING_LEVELS（位序就是这里的下标） */")
    lines.append('    val EXTENDED = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")')
    lines.append("")
    body_a, n_a = emit_map("qualified", per_provider)
    body_b, n_b = emit_map("bare", bare)
    lines.append('    /** "提供方/模型" -> 掩码（%d 条） */' % n_a)
    lines.append("    val BY_KEY: Map<String, Int> = qualified0()" + "".join(
        " + qualified%d()" % i for i in range(1, (n_a + 149) // 150)
    ))
    lines.append("")
    lines.append('    /** "模型" -> 掩码（%d 条，只在跨提供方没有分歧时收录） */' % n_b)
    lines.append("    val BY_BARE_ID: Map<String, Int> = bare0()" + "".join(
        " + bare%d()" % i for i in range(1, (n_b + 149) // 150)
    ))
    lines.append("")
    lines.extend(body_a)
    lines.extend(body_b)
    lines.append("}")
    lines.append("")
    sys.stdout.write("\n".join(lines))


if __name__ == "__main__":
    main()
