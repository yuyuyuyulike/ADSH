import io, re
D = '/mnt/d/WSN2005/node-v24.19.0-win-x64/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai'
FRONT = D + '/dsh-web-frontend/dist/assets/index-BKQ_L1z6.js'
data = open(FRONT, encoding='utf-8', errors='replace').read()
for sym, name in [('op', 'ThinkOutline14')]:
    m = re.search(r'(?<![A-Za-z0-9_$])' + sym + r'\s*=\s*\(\{', data)
    if not m:
        print('未找到 ' + sym); continue
    nxt = re.search(r',[A-Za-z_$][\w$]{0,4}=\(\{size:', data[m.end():])
    body = data[m.start(): m.end() + (nxt.start() if nxt else 2000)]
    vb = re.search(r'viewBox:"([^"]+)"', body).group(1)
    paths = re.findall(r'd:"([^"]+)"', body)
    print(name + ' viewBox=' + vb + ' paths=' + str(len(paths)))
    for p in paths:
        print('   ' + p)
