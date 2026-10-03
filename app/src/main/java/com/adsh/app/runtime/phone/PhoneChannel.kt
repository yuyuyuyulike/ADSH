package com.adsh.app.runtime.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import com.adsh.app.BuildConfig
import com.adsh.app.core.phone.PhoneRequest
import com.adsh.app.core.phone.encodePhoneResponse
import com.adsh.app.core.phone.PhoneOpenTarget
import com.adsh.app.core.phone.parsePhoneRequest
import com.adsh.app.core.phone.phoneInfoText
import com.adsh.app.core.phone.resolveOpenTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * `phone` 通道的 ADSH 侧（T1）：在 `<filesDir>/phone/{requests,responses}` 上轮询，
 * 用**应用自己的前台身份**执行 bash 做不到的那几件事。
 *
 * 为什么必须由 ADSH 做（本轮设备实测，app UID）：
 *   ❌ `am start` / `cmd activity start-activity` —— `package=com.android.shell does not belong to uid`
 *   ❌ `monkey`（rc=253）、`input tap`（INJECT_EVENTS）、`screencap`、`settings get`（INTERACT_ACROSS_USERS）
 *   ✅ `pm list packages` / `cmd -l` / `logcat` —— 只读认知（T0 已经把 /system/bin 放进 PATH）
 * ADSH 自己有 UID 与前台状态：`startActivity`、剪贴板、电量/屏幕状态都能做，**不需要任何新权限**
 * （网络类型要 ACCESS_NETWORK_STATE，清单里已声明）。
 *
 * 不做的事（用户口径「不想整没什么用的硬件操控」）：传感器 / 手电 / GPS / 短信 / 电话 / 相机。
 * 也不做点屏幕：那需要无障碍服务（用户手动开启），属于 T3，不在这条通道里。
 *
 * 信任边界：请求目录在**应用自己的私有目录**里，只有同 UID 的 termux userland（也就是 AI 的 bash）
 * 能写 —— 没有引入新的信任边界。每个动作都会 append 到 `phone/phone.log`（一行一条）。
 */
internal object PhoneChannel {

    /** 脚本内容变了就覆盖（版本号进脚本头，升级后自动更新） */
    private const val SCRIPT_VERSION = "1"

    private fun dirs(context: Context): Pair<File, File> {
        val req = File(context.filesDir, "phone/requests")
        val rsp = File(context.filesDir, "phone/responses")
        req.mkdirs()
        rsp.mkdirs()
        return req to rsp
    }

    /** 把 `phone` 脚本放进前缀的 bin（前缀 = filesDir/usr，与 TermuxRuntime.environment() 一致） */
    fun ensureScript(context: Context) {
        runCatching {
            val bin = File(context.filesDir, "usr/bin")
            if (!bin.isDirectory) return
            val script = File(bin, "phone")
            val content = SCRIPT.trimIndent().replace("@VERSION@", SCRIPT_VERSION) + "\n"
            if (script.isFile && script.readText() == content) return
            script.writeText(content)
            script.setExecutable(true, false)
        }
    }

    /** 起轮询（应用进程活着就一直在；一次请求的处理很快，采样间隔 120ms 足够） */
    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val (reqDir, rspDir) = dirs(app)
            while (true) {
                runCatching { pump(app, reqDir, rspDir) }
                delay(120)
            }
        }
    }

    private fun pump(context: Context, reqDir: File, rspDir: File) {
        val pending = reqDir.listFiles { f -> f.isFile && f.name.endsWith(".req") }?.sortedBy { it.name } ?: return
        for (req in pending) {
            val id = req.name.removeSuffix(".req")
            val text = runCatching { req.readText() }.getOrDefault("")
            val parsed = parsePhoneRequest(text)
            val (code, body) = if (parsed == null) 2 to USAGE else runCatching { handle(context, parsed) }
                .getOrElse { 1 to ("失败：" + (it.message ?: it::class.java.simpleName)) }
            runCatching {
                File(rspDir, id + ".rsp").writeText(encodePhoneResponse(code, body))
                req.delete()
                File(context.filesDir, "phone/phone.log").appendText(
                    System.currentTimeMillis().toString() + " " + text.replace('\n', ' ').take(200) +
                        " -> " + code + "\n",
                )
            }
        }
    }

    private fun handle(context: Context, request: PhoneRequest): Pair<Int, String> = when (request) {
        is PhoneRequest.Open -> open(context, request.target)
        PhoneRequest.Info -> 0 to info(context)
        is PhoneRequest.ClipGet -> clipGet(context)
        is PhoneRequest.ClipSet -> clipSet(context, request.text)
        PhoneRequest.Help -> 0 to USAGE
    }

    /** 开什么由 [resolveOpenTarget] 判定（纯函数、有用例），这里只把结果变成 Intent */
    private fun open(context: Context, target: String): Pair<Int, String> {
        val pm = context.packageManager
        val resolved = resolveOpenTarget(target) { pm.getLaunchIntentForPackage(it) != null }
        val intent = when (resolved) {
            is PhoneOpenTarget.Url -> Intent(Intent.ACTION_VIEW, Uri.parse(resolved.url))
            is PhoneOpenTarget.Domain -> Intent(Intent.ACTION_VIEW, Uri.parse(resolved.url))
            is PhoneOpenTarget.Package -> pm.getLaunchIntentForPackage(resolved.name)
                ?: return 1 to ("拉起失败：找不到 " + resolved.name + " 的启动入口")
            is PhoneOpenTarget.Unsupported ->
                return 1 to ("打不开：" + resolved.target + "（既不是网址，也不是已安装的包名）")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            0 to ("已打开：" + target)
        }.getOrElse {
            // 后台启动活动限制（锁屏 / 应用不在前台）会走到这里
            1 to ("打开失败：" + (it.message ?: it::class.java.simpleName))
        }
    }

    /** 只负责取四个事实，文本形状在 [phoneInfoText]（那边有用例） */
    private fun info(context: Context): String {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return phoneInfoText(
            screenOn = power.isInteractive,
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            networkConnected = cm.activeNetwork != null,
            versionName = BuildConfig.VERSION_NAME,
            sdk = android.os.Build.VERSION.SDK_INT,
        )
    }

    private fun clipboard(context: Context): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun clipGet(context: Context): Pair<Int, String> {
        val clip = clipboard(context).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        // Android 10+：应用不在前台时读剪贴板会拿到 null —— 如实说明，别让模型以为剪贴板是空的
        return if (text == null) 1 to "剪贴板读不到（应用不在前台时系统不允许读）" else 0 to text
    }

    private fun clipSet(context: Context, text: String): Pair<Int, String> = runCatching {
        clipboard(context).setPrimaryClip(ClipData.newPlainText("ADSH", text))
        0 to ("已写入剪贴板（" + text.length + " 字）")
    }.getOrElse { 1 to ("写入剪贴板失败：" + (it.message ?: it::class.java.simpleName)) }

    private val USAGE = """
        phone —— 让 ADSH 用它自己的前台身份做几件事（bash 直接做不了的那些）

        phone open <网址|包名|域名>   打开网址 / 拉起应用（例：phone open https://example.com）
        phone info                    屏幕 / 电量 / 网络 / ADSH 版本
        phone clip get                读剪贴板（要应用在前台）
        phone clip set <文本>         写剪贴板
        phone help                    这份用法

        注意：拉起应用受系统「后台启动活动」限制 —— 应用不在前台（锁屏等）时会失败并如实报错。
        只读认知（列包 / 看日志）不需要 phone，直接 pm list packages / logcat 就行。
    """.trimIndent()

    /** 写进 PREFIX/bin/phone 的脚本（@VERSION@ 由 [ensureScript] 替换） */
    private val SCRIPT = """
#!/data/data/com.termux/files/usr/bin/sh
# ADSH 的 phone 通道（T1）。协议见 app/src/main/java/com/adsh/app/core/phone/PhoneProtocol.kt
# version @VERSION@ —— 由 ADSH 在启动时写入/更新，别手改（改了会被覆盖）
set -u
PREFIX="${'$'}{PREFIX:-/data/data/com.termux/files/usr}"
DIR="${'$'}(dirname "${'$'}PREFIX")/phone"
REQ="${'$'}DIR/requests"
RSP="${'$'}DIR/responses"
mkdir -p "${'$'}REQ" "${'$'}RSP" 2>/dev/null || true
if [ "${'$'}#" -eq 0 ]; then set -- help; fi
id="${'$'}${'$'}.${'$'}(date +%s 2>/dev/null || echo 0)"
tmp="${'$'}REQ/.${'$'}id.tmp"
req="${'$'}REQ/${'$'}id.req"
rsp="${'$'}RSP/${'$'}id.rsp"
{ printf '%s
' "${'$'}1"; shift; for a in "${'$'}@"; do printf '%s
' "${'$'}a"; done; } > "${'$'}tmp" || exit 3
mv "${'$'}tmp" "${'$'}req" || exit 3
i=0
while [ "${'$'}i" -lt 120 ]; do
  if [ -f "${'$'}rsp" ]; then
    code="${'$'}(head -n 1 "${'$'}rsp")"
    tail -n +2 "${'$'}rsp"
    rm -f "${'$'}rsp"
    exit "${'$'}{code:-1}"
  fi
  sleep 0.1 2>/dev/null || sleep 1
  i=${'$'}((i+1))
done
rm -f "${'$'}req"
echo "phone: ADSH 没有响应（应用进程没在跑？）" >&2
exit 4
""".trimIndent()
}
