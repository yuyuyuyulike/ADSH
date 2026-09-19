import re, sys

FRONT = '/mnt/d/WSN2005/node-v24.19.0-win-x64/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets/index-BKQ_L1z6.js'

# 压缩产物里按「标识符 = 」查符号定义（图标组件等）
def main():
    keys = sys.argv[1:]
    data = open(FRONT, encoding='utf-8', errors='replace').read()
    for key in keys:
        pat = re.compile(r'(?<![A-Za-z0-9_$])' + re.escape(key) + r'\s*=')
        hits = [m.start() for m in pat.finditer(data)]
        print('##### ' + key + ' → ' + str(len(hits)) + ' 处')
        for idx in hits[:2]:
            print(data[idx:idx + 1100].replace(chr(10), ' '))
            print('   ----')

main()