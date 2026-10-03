package com.adsh.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * 用户隐私说明（ADSH 自研；第 177 轮用户要求「首次启动强制同意一次，不必整其他的」）。
 *
 * dsh 里**没有**隐私政策 / 用户协议（子代理对整包 12234 个文件做过双向检索，只有一条「在使用官方
 * 模型 API 时上传 Session Log」的开关和 onboarding 里一句「你的项目和文件保存在本地」）。
 * 所以这份正文按 ADSH 自己的事实来写，口径参考 dsh 那两句：本地优先、只有对话内容按需发给
 * **你自己配置的**模型服务、没有遥测。
 */
internal const val PRIVACY_POLICY_VERSION = "2026-10-03"

internal val PRIVACY_POLICY_TEXT = listOf(
    "一、数据都在本机" to
        "会话记录、附件、终端与工具的输出、导出的 ZIP，全部保存在本应用的私有目录与你选择的工作区文件夹里。" +
            "本应用没有账号体系，也不会把这些内容上传到我们自己的服务器。",
    "二、发给模型服务的内容" to
        "只有你**主动发送的消息**、模型需要看到的会话上下文、工具调用与工具结果，会通过 HTTPS 发给你在" +
            "「设置 → 模型」里配置的模型服务（DeepSeek 官方 API 或你自己的兼容端点）。发什么由你的提问与工具" +
            "调用决定；API Key 只存在本机设置里，只用于请求该服务。",
    "三、没有遥测" to
        "本应用不采集使用统计、崩溃上报或设备标识，也没有开屏广告与三方统计 SDK。随包分发的第三方二进制" +
            "（Termux 用户态、ripgrep、Node 等）只在本机运行，详见 THIRD_PARTY_NOTICES。",
    "四、权限与文件访问" to
        "存储权限用于读写你指定的工作区与导入附件；网络权限只用于访问模型服务与你触发的网页抓取。" +
            "文件预览与导出都在本机完成。",
    "五、你的选择" to
        "你可以随时在设置里更换模型服务或删除 API Key，也可以在会话抽屉里删除任意会话（连同它的附件与" +
            "工作区附件目录一起删除）。点击下面的「同意并继续」表示你已阅读并接受以上说明。",
)

/**
 * 首次启动的隐私说明门（强制同意一次）。
 *
 * 用户第 177 轮的口径：**首次启动强制同意一次**即可，所以这里：不进设置页、不做更多确认、
 * 也不给「跳过」；只有点过「同意并继续」才写入本地标记（[com.adsh.app.core.data.SettingsStore.privacyAccepted]）。
 * 返回键与点外部都不关闭（[DialogProperties]），因为这是使用前提。
 */
@Composable
internal fun PrivacyConsentGate(onAccept: () -> Unit) {
    val palette = LocalDshPalette.current
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            color = palette.inputMajor,
            contentColor = palette.labelPrimary,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("用户隐私说明", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.labelPrimary)
                Text(
                    "版本 " + PRIVACY_POLICY_VERSION + " · 首次使用前请阅读并同意",
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    fontSize = 12.sp,
                    color = palette.labelTertiary,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    PRIVACY_POLICY_TEXT.forEach { (title, text) ->
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.labelPrimary)
                        Text(
                            text,
                            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = palette.labelSecondary,
                        )
                    }
                }
                Spacer(Modifier.padding(top = 4.dp))
                Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                    Text("同意并继续")
                }
            }
        }
    }
}
