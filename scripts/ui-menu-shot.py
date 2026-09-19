import re, subprocess, sys, time, xml.etree.ElementTree as ET
ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
ROOT = "/mnt/d/WSN2005/Android1/App/ADSH"

def sh(*a):
    return subprocess.run([ADB, "shell"] + list(a), capture_output=True).stdout.decode("utf-8", "ignore")

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

r = dump()
hits = []
for n in r.iter("node"):
    d = n.get("content-desc") or ""
    if "的操作" in d or "新建会话" in d or "删除工作区" in d:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            hits.append((d, (x1 + x2) // 2, (y1 + y2) // 2))
for d, x, y in hits:
    print(d + " @ (" + str(x) + "," + str(y) + ")")

if hits:
    d, x, y = hits[0]
    sh("input", "tap", str(x), str(y))
    time.sleep(1.2)
    png = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout
    open(ROOT + "/build/tmp/menu.png", "wb").write(png)
    print("已点 " + d)
