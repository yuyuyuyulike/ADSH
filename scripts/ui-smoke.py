import re, subprocess, sys, time, xml.etree.ElementTree as ET
ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
PKG = "com.adsh.app.debug"

def sh(*args):
    return subprocess.run([ADB, "shell"] + list(args), capture_output=True).stdout.decode("utf-8", "ignore")

def focus():
    m = re.search(r"mCurrentFocus=(\S+)\s+(\S+)\s+([^\s}]+)", sh("dumpsys", "window"))
    return m.group(3).split("/")[-1] if m else "?"

def ime():
    m = re.search(r"mInputShown=(\w+)", sh("dumpsys", "input_method"))
    return m.group(1) if m else "?"

def dump():
    for _ in range(3):
        sh("rm", "-f", "/sdcard/ui.xml")
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        xml = subprocess.run([ADB, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout.decode("utf-8", "ignore")
        if xml.strip().startswith("<?xml"):
            try:
                return ET.fromstring(xml)
            except Exception:
                pass
        time.sleep(1.0)
    return None

def has(want):
    r = dump()
    if r is None:
        return False
    for n in r.iter("node"):
        if want in (n.get("text") or "") or want in (n.get("content-desc") or ""):
            return True
    return False

def tap(x, y, label):
    sh("input", "tap", str(x), str(y))
    time.sleep(1.6)
    print("   " + label + " -> IME=" + ime() + " 焦点=" + focus())

f = focus()
if "launcher" not in f.lower() and "adsh" not in f.lower():
    print("手机正在被使用（" + f + "），放弃测试")
    sys.exit(0)

sh("am", "force-stop", PKG)
time.sleep(1.5)
sh("am", "start", "-n", PKG + "/com.adsh.app.MainActivity")
time.sleep(4)

print("1) 点输入框")
tap(400, 2450, "输入框")

print("2) 点权限预设")
tap(368, 2619, "权限")
print("      菜单可见=" + str(has("工作区内修改")))

print("3) 点模型与推理等级")
tap(846, 2619, "模型")
print("      菜单可见=" + str(has("推理等级")))

print("4) 点 + 指令面板")
tap(105, 2619, "指令")
print("      面板可见=" + str(has("压缩以上对话内容")))

print("5) 点消息区空白")
tap(640, 900, "空白")
print("      面板还可见=" + str(has("压缩以上对话内容")))

print("6) 再点输入框并打字")
tap(400, 2450, "输入框")
sh("input", "text", "hello")
time.sleep(1.2)
r = dump()
typed = any((n.get("text") or "") == "hello" for n in r.iter("node")) if r is not None else False
print("      输入框内容写入=" + str(typed) + " IME=" + ime())
print("完成")
