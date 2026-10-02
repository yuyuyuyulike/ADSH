import sys

# 提取 dsh 编译产物里的 CSS 字符串（const css = "...";）并按规则换行打印
def unescape(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            mapping = {'n': '\n', 't': '\t', 'r': '\r', '"': '"', '\\': '\\', "'": "'"}
            out.append(mapping.get(n, n))
            i += 2
        else:
            out.append(c)
            i += 1
    return ''.join(out)

def main():
    path, lineno = sys.argv[1], int(sys.argv[2])
    with open(path, encoding='utf-8', errors='replace') as f:
        lines = f.readlines()
    line = lines[lineno - 1]
    start = line.find('"')
    end = line.rfind('"')
    if start < 0 or end <= start:
        print('这一行没有 CSS 字符串: ' + line[:120])
        return
    css = unescape(line[start + 1:end])
    depth = 0
    buf = ''
    for ch in css:
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            buf += ch
            if depth <= 0:
                print(buf.strip())
                buf = ''
                depth = 0
            continue
        buf += ch
    if buf.strip():
        print(buf.strip())

main()