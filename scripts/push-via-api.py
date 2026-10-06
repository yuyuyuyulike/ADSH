"""把本地的一段提交搬到 GitHub 上（github.com:443 被墙时的替代 push）。

背景：这台机器上 github.com / raw.githubusercontent.com 连不上（Connection reset），
但 api.github.com 与 uploads.github.com 通 —— 也就是 gh CLI 能用、gh release 能发，
只有 git push 走不通。于是用 REST 的 git-data 接口把这几个提交原样重建到远端：

  blobs（每个改动文件）→ trees（base_tree = 远端当前的树）→ commits（作者/提交者/时间/信息照抄）
  最后 PATCH refs/heads/main。

用法（在仓库根目录）：
  python scripts/push-via-api.py <base-commit> [<remote>] [<branch>]

<base-commit> = 远端 main 当前那个提交（本地也要有它）。脚本会把 base..HEAD 的提交按顺序重放。
因为 tree 只由内容决定，重放完远端那个 tree 的 sha 应该与本地 HEAD 的 tree 完全一致 ——
脚本最后会自己比一遍，不一致就直接失败（宁可报错也不要留一个「看着像推上去了」的远端）。
"""
import base64, json, os, subprocess, sys, tempfile

repo = "yuyuyuyulike/ADSH"
branch = "main"
work = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
env = dict(os.environ, HOME="/c/Users/王舒宁")


def git(*args, binary=False):
    r = subprocess.run(["git", "-C", work] + list(args), capture_output=True, env=env)
    if r.returncode != 0:
        raise SystemExit("git " + " ".join(args) + " 失败：" + r.stderr.decode("utf-8", "replace"))
    return r.stdout if binary else r.stdout.decode("utf-8", "replace").strip()


def gh(path, payload=None, method=None):
    args = ["gh", "api", path]
    if method:
        args += ["-X", method]
    tmp = None
    if payload is not None:
        tmp = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
        json.dump(payload, tmp)
        tmp.close()
        args += ["--input", tmp.name]
    r = subprocess.run(args, capture_output=True, env=env)
    if tmp:
        os.unlink(tmp.name)
    if r.returncode != 0:
        raise SystemExit("gh api " + path + " 失败：" + r.stderr.decode("utf-8", "replace")[:400])
    out = r.stdout.decode("utf-8", "replace")
    return json.loads(out) if out.strip() else {}


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    base = sys.argv[1]
    global repo, branch
    if len(sys.argv) > 2:
        repo = sys.argv[2]
    if len(sys.argv) > 3:
        branch = sys.argv[3]

    commits = [c for c in git("rev-list", "--reverse", base + "..HEAD").split("\n") if c]
    if not commits:
        raise SystemExit("base..HEAD 里没有提交，没什么可推的")

    remote = gh("repos/" + repo + "/git/ref/heads/" + branch)
    parent = remote["object"]["sha"]
    tree = gh("repos/" + repo + "/git/commits/" + parent)["tree"]["sha"]
    print("远端 " + branch + " 当前 " + parent[:10] + "（tree " + tree[:10] + "），要重放 " + str(len(commits)) + " 个提交")

    for sha in commits:
        subject = git("show", "-s", "--format=%s", sha)
        entries = []
        for line in git("diff-tree", "-r", "--no-commit-id", "--name-status", "--no-renames", sha).split("\n"):
            if not line.strip():
                continue
            parts = line.split("\t")
            status, path = parts[0], parts[-1]
            if status == "D":                      # 删除 = tree 里把 sha 置 null
                entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
                continue
            meta = git("ls-tree", sha, "--", path).split()
            mode, blob = meta[0], meta[2]
            content = git("cat-file", "blob", blob, binary=True)
            blob_sha = gh("repos/" + repo + "/git/blobs",
                          {"content": base64.b64encode(content).decode("ascii"), "encoding": "base64"})["sha"]
            entries.append({"path": path, "mode": mode, "type": "blob", "sha": blob_sha})
        info = git("show", "-s", "--format=%an%x00%ae%x00%aI%x00%cn%x00%ce%x00%cI%x00%B", sha).split("\x00")
        tree = gh("repos/" + repo + "/git/trees", {"base_tree": tree, "tree": entries})["sha"]
        parent = gh("repos/" + repo + "/git/commits", {
            "message": info[6].rstrip("\n"),
            "tree": tree,
            "parents": [parent],
            "author": {"name": info[0], "email": info[1], "date": info[2]},
            "committer": {"name": info[3], "email": info[4], "date": info[5]},
        })["sha"]
        print("  " + sha[:8] + " -> " + parent[:8] + "  " + subject[:50] + "  files=" + str(len(entries)))

    gh("repos/" + repo + "/git/refs/heads/" + branch, {"sha": parent, "force": False}, method="PATCH")
    local_tree = git("rev-parse", "HEAD^{tree}")
    remote_tree = gh("repos/" + repo + "/git/commits/" + parent)["tree"]["sha"]
    print("远端 " + branch + " 现在指向 " + parent)
    print("本地 HEAD tree : " + local_tree)
    print("远端 tree      : " + remote_tree)
    if local_tree != remote_tree:
        raise SystemExit("两边 tree 不一致 —— 远端内容不等价，别当成推成功了")
    print("内容逐字节一致（tree sha 相同）")


if __name__ == "__main__":
    main()
