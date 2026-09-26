import sys

# 读取 dsh 客户端源码的某个区域：编译产物里混着超长压缩行（pdf.js 等），按长度过滤掉
def main():
    path, start, end = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    maxlen = int(sys.argv[4]) if len(sys.argv) > 4 else 400
    with open(path, encoding='utf-8', errors='replace') as f:
        for i, line in enumerate(f, 1):
            if i < start:
                continue
            if i > end:
                break
            line = line.rstrip('\n')
            if len(line) > maxlen:
                print(str(i) + '| <长行 ' + str(len(line)) + ' 字符>')
            else:
                print(str(i) + '| ' + line)

main()