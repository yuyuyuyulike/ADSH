import sys

# 在压缩后的前端产物里按关键字取一段上下文（用于抄 dsh 的图标路径 / 文案）
def main():
    path = sys.argv[1]
    key = sys.argv[2]
    before = int(sys.argv[3]) if len(sys.argv) > 3 else 800
    after = int(sys.argv[4]) if len(sys.argv) > 4 else 2500
    data = open(path, encoding='utf-8', errors='replace').read()
    idx = data.find(key)
    if idx < 0:
        print('未找到 ' + key)
        return
    print('命中位置 ' + str(idx) + ' / 总长 ' + str(len(data)))
    print(data[max(0, idx - before):idx + after])

main()