import re, subprocess, sys, time, xml.etree.ElementTree as ET
ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
PKG = "com.adsh.app.debug"

def sh(*a):
    return subprocess.run([ADB, "shell"] + list(a), capture_output=True).stdout.decode("utf-8", "ignore")

def focus():
    m = re.search(r"mCurrentFocus=(\S+)\s+(\S+)\s+([^\s}]+)", sh("dumpsys", "window"))
    return m.group(3) if m else "?"

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

f = focus()
print("焦点: " + f)
if "launcher" not in f.lower() and "adsh" not in f.lower():
    print("手机被占用，放弃")
    sys.exit(0)

sh("input", "swipe", "5", "1400", "900", "1400", "300")
sh("am", "force-stop", PKG)
time.sleep(1.5)
sh("am", "start", "-n", PKG + "/com.adsh.app.MainActivity")
time.sleep(5)
r = dump()
if r is None:
    print("dump 失败")
    sys.exit(1)
for n in r.iter("node"):
    t = (n.get("text") or "").strip()
    d = (n.get("content-desc") or "").strip()
    b = n.get("bounds") or ""
    if t or d:
        print("[" + b + "] text=" + t[:46] + " desc=" + d[:46])
