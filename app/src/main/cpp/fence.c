/*
 * ADSH 的写围栏（dsh 的 fs-sandbox fence 在 Android 上的原生实现）。
 *
 * 背景与取舍
 * ----------
 * dsh 在桌面上用内核沙箱（bwrap / seatbelt / Landlock）把 workspace-write 变成
 * 「文件系统只读，只有白名单里的根可写」。安卓上没有给普通应用用的内核沙箱，
 * 也不能用 proot（ptrace 会拖慢每一次 IO，用户明确否掉）。这里走的是 Termux 自己的
 * 路子：LD_PRELOAD 一个 shim（termux-exec 就是这么干的），在 libc 的写类入口上做拦截。
 * 与 proot 相比：没有 ptrace、没有额外进程，只有一次 PLT 跳转的开销。
 *
 * 覆盖范围（诚实说明）
 * --------------------
 *  - 拦截 libc 的 open/openat/fopen/creat/mkdir/unlink/rename/link/symlink/truncate/remove…；
 *  - **动态链接**的程序（bootstrap 里的 bash、coreutils、apt、python…）全部继承 LD_PRELOAD，
 *    所以它们的写都会被拦；
 *  - 绕过不了的例外：直接发系统调用的静态程序（例如 Go 写的二进制）、以及已经打开的 fd
 *    （/proc/self/fd/N）—— 这两种情况下围栏不生效，与「安卓没有内核沙箱」是同一件事。
 *    读一律不拦（dsh 的读观察本来就不围栏）。
 *
 * 拒绝语义与 dsh 完全一致
 * ----------------------
 * errno = EACCES，并且往 stderr 打 dsh 的两行标记（denialMarker + hintMarker）：
 * 模型据此知道「这是策略拒绝、可以带 sandbox_permissions 申请一次更宽的权限」，
 * App 那边就会弹出审批卡。标记只打前几条，避免刷屏。
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <dirent.h>
#include <linux/stat.h>
#include <sys/time.h>
#include <sys/uio.h>
#include <unistd.h>

extern char **environ;

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#ifndef O_TMPFILE
#define O_TMPFILE 0
#endif

/** 白名单根的上限（工作区 + 临时目录，够用了） */
#define MAX_ROOTS 8
/** 同一个进程里最多打几条拒绝标记 */
#define MAX_REPORTS 3

static char g_roots[MAX_ROOTS][PATH_MAX];
static size_t g_root_count;
static bool g_enabled;
/** dsh 的模式名（拒绝标记里要回显它） */
static char g_mode[64] = "workspace-write";
/** 见过的路径：用于 App 侧确认 preload 真的生效（写一次就够） */
static char g_mark[PATH_MAX];
static pthread_once_t g_init_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_reports;

/** /tmp 的落点（ADSH_TMP_REDIRECT = $PREFIX/tmp）：安卓没有可写的 /tmp */
static char g_tmp[PATH_MAX];
/** 硬链接替身：SELinux 不给 app 私有目录 link 权限，失败时退化成复制（ADSH_LINK_EMULATE=0 关） */
static bool g_link_emulate = true;

/** 防重入：realpath/getcwd 这些 libc 调用内部可能又走到我们的入口 */
static __thread int g_busy;

/* 真实符号在文件后半部分定义，init 里要用，先声明 */
static void resolve_symbols(void);
/* /tmp 的落点（g_tmp）在 init 里赋值：redirect 里先 ensure_init */
static void ensure_init(void);
/* 诊断（ADSH_SHIM_DIAG）：定义在后面，ensure_init 里要用 */
static void diag(const char *text);


/* ------------------------------------------------------------------ 初始化 */

/** 词法归一化（不解析符号链接）：去掉重复的 '/'、'.' 和可消去的 '..' */
static void normalize(char *p) {
    char *out = p;
    char *in = p;
    if (*in == '/') {
        *out++ = *in++;
    }
    while (*in != '\0') {
        if (in[0] == '.' && in[1] == '/') {
            in += 2;
        } else if (in[0] == '.' && in[1] == '.' && in[2] == '/') {
            /* 回退一级，但不要越过根 */
            if (out > p + 1) {
                out--;
                while (out > p + 1 && out[-1] != '/') out--;
            }
            in += 3;
        } else if (in[0] == '/' ) {
            *out++ = *in++;
            while (*in == '/') in++;
        } else {
            *out++ = *in++;
        }
    }
    if (out > p + 1 && out[-1] == '/') out--;
    *out = '\0';
}

/** 绝对化（相对路径按当前工作目录展开） */
static bool absolute(const char *path, char *out, size_t size) {
    if (path == NULL || *path == '\0') return false;
    if (path[0] == '/') {
        snprintf(out, size, "%s", path);
        return true;
    }
    char cwd[PATH_MAX];
    if (getcwd(cwd, sizeof(cwd)) == NULL) return false;
    snprintf(out, size, "%s/%s", cwd, path);
    return true;
}

/**
 * 把路径化成「真实路径」：解析符号链接，但允许路径的最后几级还不存在
 * （mkdir -p / 新建文件的情况）。做法是从最深的**已存在**祖先开始 realpath，
 * 再把剩下的部分原样接回去。
 */
static bool canonical(const char *path, char *out, size_t size) {
    char work[PATH_MAX];
    if (!absolute(path, work, sizeof(work))) return false;
    normalize(work);

    char suffix[PATH_MAX];
    suffix[0] = '\0';
    for (;;) {
        char real[PATH_MAX];
        if (realpath(work, real) != NULL) {
            snprintf(out, size, "%s%s", real, suffix);
            return true;
        }
        char *slash = strrchr(work, '/');
        if (slash == NULL) return false;
        char next[PATH_MAX];
        if (slash == work) {
            /* 落到根了：realpath("/") 一定成功，上面的分支已经处理过 */
            next[0] = '\0';
        } else {
            snprintf(next, sizeof(next), "%s%s", slash, suffix);
            *slash = '\0';
        }
        if (next[0] == '\0') return false;
        snprintf(suffix, sizeof(suffix), "%s", next);
        if (work[0] == '\0') snprintf(work, sizeof(work), "/");
    }
}

static bool under(const char *path, const char *root) {
    size_t n = strlen(root);
    if (n == 0) return false;
    if (strncmp(path, root, n) != 0) return false;
    return path[n] == '\0' || path[n] == '/';
}

/** 读一个环境变量（用 getenv，不走我们的拦截） */
static const char *env_or_null(const char *name) {
    const char *value = getenv(name);
    if (value == NULL || *value == '\0') return NULL;
    return value;
}

/** 符号解析只做一次 */
static bool g_symbols_done;
/** 环境已经读到过（且当时 environ 是可读的） */
static bool g_env_done;
/** 环境还没就绪时被调用过几次（诊断用） */
static int g_early_skips;

/**
 * 读环境（只做一次，且必须等 environ 真的可读之后）。
 *
 * 真机教训（这是 shim 从上线起就一直「加载了却什么都不做」的根因）：
 * preload 库的第一次被拦截调用可能发生在 libc 把 environ 装好之前（linker/libc 自己的
 * 启动路径），那时 getenv() 全是 NULL；而原来的写法是 pthread_once 一锤定音 —— 于是
 * g_tmp/g_roots 永远为空，围栏与路径映射全部静默失效，表现却是「库明明在
 * /proc/self/maps 里、符号也能被 dlsym 解析到」。现在：environ 不可读就直接返回
 * （不消耗 once），等它可读了下一次调用再来。
 */
static void init_env(void) {
    const char *mode = env_or_null("ADSH_FENCE_MODE");
    if (mode != NULL) snprintf(g_mode, sizeof(g_mode), "%s", mode);

    const char *tmp = env_or_null("ADSH_TMP_REDIRECT");
    if (tmp != NULL) snprintf(g_tmp, sizeof(g_tmp), "%s", tmp);

    /* 硬链接替身（默认开）：安卓的 SELinux 不给 untrusted_app 对 app_data_file 的 link
       权限（avc: denied { link }），真 link() 必然 EACCES。设 0 就回到「老实报错」。 */
    const char *no_link = env_or_null("ADSH_LINK_EMULATE");
    if (no_link != NULL && no_link[0] == '0' && no_link[1] == '\0') g_link_emulate = false;

    /* 哨兵：App 用它确认这次进程真的加载了 shim（围栏有没有生效全看它） */
    const char *mark = env_or_null("ADSH_FENCE_MARK");
    if (mark != NULL) {
        snprintf(g_mark, sizeof(g_mark), "%s", mark);
        /* 用原始系统调用写，别惊动自己的拦截 */
        int fd = (int)syscall(SYS_openat, AT_FDCWD, g_mark, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            const char *payload = "adsh-fence\n";
            ssize_t ignored = write(fd, payload, strlen(payload));
            (void)ignored;
            syscall(SYS_close, fd);
        }
    }

    const char *roots = env_or_null("ADSH_FENCE_ROOTS");
    if (roots == NULL) return; /* 没有白名单 = 不启用围栏（完全权限 / 终端页） */

    char list[PATH_MAX * 2];
    snprintf(list, sizeof(list), "%s", roots);
    char *save = NULL;
    for (char *part = strtok_r(list, ":", &save); part != NULL; part = strtok_r(NULL, ":", &save)) {
        if (g_root_count >= MAX_ROOTS) break;
        char real[PATH_MAX];
        if (!canonical(part, real, sizeof(real))) continue;
        snprintf(g_roots[g_root_count], PATH_MAX, "%s", real);
        g_root_count++;
    }
    if (g_root_count == 0) return;
    g_enabled = true;
}

static void ensure_init(void) {
    /* 符号解析：dlsym(RTLD_NEXT, …)。惰性第一次调用可能早到 libc 还没初始化完，
       那时 dlsym 拿不到东西 —— 每个符号都有「拿不到就用原始系统调用」的兜底，安全。 */
    if (!g_symbols_done) {
        resolve_symbols();
        g_symbols_done = true;
    }
    if (g_env_done) return;
    /* 环境还没装好就先不读（也不要消耗 once）：下一次被拦截的调用会再来一遍 */
    extern char **environ;
    if (environ == NULL || environ[0] == NULL) {
        g_early_skips++;
        return;
    }
    g_env_done = true;
    init_env();
    char buf[320];
    snprintf(buf, sizeof(buf), "[shim] init done tmp=%s enabled=%d roots=%zu early_skips=%d\n",
             g_tmp[0] != 0 ? g_tmp : "(none)", (int)g_enabled, g_root_count, g_early_skips);
    diag(buf);
}

/**
 * 可选诊断：ADSH_SHIM_DIAG=<文件> 时把一行运行期事实追加进去（缺省零成本）。
 *
 * 为什么要有它（真机教训）：shim 被加载、符号也确实排在全局作用域第一位（用另一个探针 .so
 * 调 dlsym(RTLD_DEFAULT, "open") 验证过就是 libadshfence），但行为上看不出它跑没跑 ——
 * 于是「LD_PRELOAD 到底有没有生效」只能靠猜。全部走原始系统调用，不惊动自己的拦截。
 */
static void diag(const char *text) {
    const char *path = getenv("ADSH_SHIM_DIAG");
    if (path == NULL || *path == '\0') return;
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd < 0) return;
    ssize_t n = write(fd, text, strlen(text));
    (void)n;
    syscall(SYS_close, fd);
}

/* 这里**故意没有** constructor：真机实测，在 LD_PRELOAD 库的 constructor 里调 dlsym（解析真实
   符号）会把进程卡死 —— bionic 这时候还握着 linker 的锁。所以初始化只能走惰性路径，
   由下面的 ensure_init 在 environ 就绪之后再做（见 init_env 的注释）。 */

/* ------------------------------------------------------------------ 判决 */

static bool write_flags(int flags) {
    if ((flags & O_ACCMODE) != O_RDONLY) return true;
    return (flags & (O_CREAT | O_TRUNC | O_APPEND)) != 0;
}

static bool write_mode(const char *mode) {
    if (mode == NULL) return false;
    for (const char *p = mode; *p != '\0'; p++) {
        if (*p == 'w' || *p == 'a' || *p == '+' || *p == 'x') return true;
    }
    return false;
}

static void report_denial(const char *path) {
    bool print = false;
    pthread_mutex_lock(&g_lock);
    if (g_reports < MAX_REPORTS) {
        g_reports++;
        print = true;
    }
    pthread_mutex_unlock(&g_lock);
    if (!print) return;
    /* 逐字对齐 dsh-sandbox 的 denialMarker + hintMarker */
    fprintf(stderr,
            "[sandbox: file access denied under %s mode]\n"
            "[sandbox: escalation available \xE2\x80\x94 retry this exact command once with "
            "sandbox_permissions (the narrowest wider mode that suffices) + justification; "
            "the approval prompt asks the user]\n",
            g_mode);
    (void)path;
}

/** 允许写？读一律放行 */
static bool allowed(const char *path) {
    ensure_init();
    if (!g_enabled) return true;
    if (path == NULL || *path == '\0') return true;
    if (g_busy) return true;
    g_busy = 1;
    bool ok = false;
    char real[PATH_MAX];
    if (canonical(path, real, sizeof(real))) {
        /* 设备与 procfs 放行：/dev/null、/dev/tty、/proc/self/… 是命令的正常组成部分 */
        ok = under(real, "/dev") || under(real, "/proc");
        for (size_t i = 0; !ok && i < g_root_count; i++) ok = under(real, g_roots[i]);
    } else {
        ok = true; /* 解析不出来就当读处理，交给内核去拒绝 */
    }
    g_busy = 0;
    if (!ok) {
        report_denial(path);
        errno = EACCES;
    }
    return ok;
}

/** at 变体：dirfd 交给 /proc/self/fd 展开，再走同一套判决 */
static bool allowed_at(int dirfd, const char *path) {
    ensure_init();
    if (!g_enabled || path == NULL || *path == '\0') return true;
    if (path[0] == '/' || dirfd == AT_FDCWD) return allowed(path);
    g_busy = 1;
    char link[64];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", dirfd);
    char base[PATH_MAX];
    ssize_t n = readlink(link, base, sizeof(base) - 1);
    g_busy = 0;
    if (n <= 0) return true; /* 拿不到目录：按读处理 */
    base[n] = '\0';
    char joined[PATH_MAX * 2];
    snprintf(joined, sizeof(joined), "%s/%s", base, path);
    return allowed(joined);
}

/**
 * fd 变体（fchmod/fchown）的判决：把 fd 展开成路径再走同一套。
 * 拿不到路径就按读处理（放行）—— 宁可漏判也不能因为拿不到 /proc 就乱拒。
 */
static bool allowed_fd(int fd) {
    ensure_init();
    if (!g_enabled || fd < 0) return true;
    char link[64];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    char base[PATH_MAX];
    g_busy = 1;
    ssize_t n = readlink(link, base, sizeof(base) - 1);
    g_busy = 0;
    if (n <= 0) return true;
    base[n] = '\0';
    return allowed(base);
}

/* ------------------------------------------------------------------ /tmp 映射 */

/**
 * /tmp → $TMPDIR（ADSH_TMP_REDIRECT）。**所有**路径入口的第一件事（在围栏判决之前）。
 *
 * 为什么必须做：安卓的 /tmp 属主是 shell、权限 0711，任何 App 都写不进去；而不少第三方工具把 /tmp
 * 写死在代码里（pip download --dest /tmp、各种日志与 socket 路径），它们不看 TMPDIR。
 * 映射发生在围栏判决**之前**（所有入口都是先 REDIRECT 再 allowed），所以 workspace-write 下
 * 往「/tmp」写也会被判成 $PREFIX/tmp 而放行。cwd 与 PWD 不变，路径语义只在这里改写。
 *
 * 历史：「官方前缀 → 等长别名」的改写曾经也在这里（包名还不是 com.termux 时的方案）。
 * 方案 A（包名 = com.termux）之后官方前缀就是真前缀，那套改写随第 22 节的清理全部删除。
 */
static const char *redirect(const char *path, char *buf, size_t size) {
    ensure_init();
    if (path == NULL) return path;

    if (g_tmp[0] != 0) {
        static const char TMP[] = "/tmp";
        size_t tn = sizeof(TMP) - 1;
        if (strncmp(path, TMP, tn) == 0 && (path[tn] == '/' || path[tn] == 0)) {
            size_t rest = strlen(path + tn);
            size_t base = strlen(g_tmp);
            if (base + rest + 1 <= size) {
                memcpy(buf, g_tmp, base);
                memcpy(buf + base, path + tn, rest + 1);
                return buf;
            }
        }
    }
    return path;
}

/* 每个路径入口都用它：先做 /tmp 映射，再判决/调用 */
#define REDIRECT(var) char redir_buf_##var[PATH_MAX]; var = redirect(var, redir_buf_##var, sizeof(redir_buf_##var));
#define REDIRECT_AT(dirfd, var) char redir_buf_##var[PATH_MAX]; if ((dirfd) == AT_FDCWD) var = redirect(var, redir_buf_##var, sizeof(redir_buf_##var));


/* ------------------------------------------------------------------ 真实符号 */

static void *resolve(const char *name) {
    return dlsym(RTLD_NEXT, name);
}

/* 真实符号：fortify 头文件把 open/openat 之类声明成了重载集合，__typeof__(名字) 在那里
   解析不出来（NDK 的 _FORTIFY_SOURCE 下就是这样），所以每个函数指针的类型都显式写出来，
   在 init 里一次性 dlsym 解析。拿不到就退回原始系统调用（绝不因为解析失败就放行写）。 */
static int (*g_open)(const char *, int, ...);
static int (*g_open64)(const char *, int, ...);
static int (*g_openat)(int, const char *, int, ...);
static int (*g_openat64)(int, const char *, int, ...);
static int (*g_open_2)(const char *, int);
static int (*g_openat_2)(int, const char *, int);
static int (*g_creat)(const char *, mode_t);
static int (*g_creat64)(const char *, mode_t);
static FILE *(*g_fopen)(const char *, const char *);
static FILE *(*g_fopen64)(const char *, const char *);
static FILE *(*g_freopen)(const char *, const char *, FILE *);
static int (*g_mkdir)(const char *, mode_t);
static int (*g_mkdirat)(int, const char *, mode_t);
static int (*g_unlink)(const char *);
static int (*g_unlinkat)(int, const char *, int);
static int (*g_rmdir)(const char *);
static int (*g_remove)(const char *);
static int (*g_rename)(const char *, const char *);
static int (*g_renameat)(int, const char *, int, const char *);
static int (*g_renameat2)(int, const char *, int, const char *, unsigned int);
static long (*g_syscall)(long, ...);
static int (*g_link)(const char *, const char *);
static int (*g_linkat)(int, const char *, int, const char *, int);
static int (*g_symlink)(const char *, const char *);
static int (*g_symlinkat)(const char *, int, const char *);
static int (*g_truncate)(const char *, off_t);
static int (*g_truncate64)(const char *, off64_t);
static int (*g_chdir)(const char *);
static int (*g_fchdir)(int);
static int (*g_chmod)(const char *, mode_t);
static int (*g_fchmod)(int, mode_t);
static int (*g_fchmodat)(int, const char *, mode_t, int);
static int (*g_chown)(const char *, uid_t, gid_t);
static int (*g_lchown)(const char *, uid_t, gid_t);
static int (*g_fchown)(int, uid_t, gid_t);
static int (*g_fchownat)(int, const char *, uid_t, gid_t, int);
static int (*g_execve)(const char *, char *const[], char *const[]);

/* 只解析一次（init_once 里调用） */
static void resolve_symbols(void) {
    g_open = (int (*)(const char *, int, ...))resolve("open");
    g_open64 = (int (*)(const char *, int, ...))resolve("open64");
    g_openat = (int (*)(int, const char *, int, ...))resolve("openat");
    g_openat64 = (int (*)(int, const char *, int, ...))resolve("openat64");
    g_open_2 = (int (*)(const char *, int))resolve("__open_2");
    g_openat_2 = (int (*)(int, const char *, int))resolve("__openat_2");
    g_creat = (int (*)(const char *, mode_t))resolve("creat");
    g_creat64 = (int (*)(const char *, mode_t))resolve("creat64");
    g_fopen = (FILE *(*)(const char *, const char *))resolve("fopen");
    g_fopen64 = (FILE *(*)(const char *, const char *))resolve("fopen64");
    g_freopen = (FILE *(*)(const char *, const char *, FILE *))resolve("freopen");
    g_mkdir = (int (*)(const char *, mode_t))resolve("mkdir");
    g_mkdirat = (int (*)(int, const char *, mode_t))resolve("mkdirat");
    g_unlink = (int (*)(const char *))resolve("unlink");
    g_unlinkat = (int (*)(int, const char *, int))resolve("unlinkat");
    g_rmdir = (int (*)(const char *))resolve("rmdir");
    g_remove = (int (*)(const char *))resolve("remove");
    g_rename = (int (*)(const char *, const char *))resolve("rename");
    g_renameat = (int (*)(int, const char *, int, const char *))resolve("renameat");
    g_renameat2 = (int (*)(int, const char *, int, const char *, unsigned int))resolve("renameat2");
    g_syscall = (long (*)(long, ...))resolve("syscall");
    g_link = (int (*)(const char *, const char *))resolve("link");
    g_linkat = (int (*)(int, const char *, int, const char *, int))resolve("linkat");
    g_symlink = (int (*)(const char *, const char *))resolve("symlink");
    g_symlinkat = (int (*)(const char *, int, const char *))resolve("symlinkat");
    g_truncate = (int (*)(const char *, off_t))resolve("truncate");
    g_truncate64 = (int (*)(const char *, off64_t))resolve("truncate64");
    g_chdir = (int (*)(const char *))resolve("chdir");
    g_fchdir = (int (*)(int))resolve("fchdir");
    g_chmod = (int (*)(const char *, mode_t))resolve("chmod");
    g_fchmod = (int (*)(int, mode_t))resolve("fchmod");
    g_fchmodat = (int (*)(int, const char *, mode_t, int))resolve("fchmodat");
    g_chown = (int (*)(const char *, uid_t, gid_t))resolve("chown");
    g_lchown = (int (*)(const char *, uid_t, gid_t))resolve("lchown");
    g_fchown = (int (*)(int, uid_t, gid_t))resolve("fchown");
    g_fchownat = (int (*)(int, const char *, uid_t, gid_t, int))resolve("fchownat");
    g_execve = (int (*)(const char *, char *const[], char *const[]))resolve("execve");
}

/* open 家族：真实符号拿不到时退回原始系统调用（绝不会因为 dlsym 失败就放行写） */

static int sys_open(const char *path, int flags, mode_t mode) {
    return (int)syscall(SYS_openat, AT_FDCWD, path, flags, (unsigned int)mode);
}

static int sys_openat(int dirfd, const char *path, int flags, mode_t mode) {
    return (int)syscall(SYS_openat, dirfd, path, flags, (unsigned int)mode);
}

int open(const char *path, int flags, ...) {
    REDIRECT(path)
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    /* 只有写才判决：dsh 的读观察不围栏，/sdcard 里的图片照样要能读 */
    if (write_flags(flags) && !allowed(path)) return -1;
    return g_open != NULL ? g_open(path, flags, mode) : sys_open(path, flags, mode);
}

int open64(const char *path, int flags, ...) {
    REDIRECT(path)
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    if (write_flags(flags) && !allowed(path)) return -1;
    return g_open64 != NULL ? g_open64(path, flags, mode) : sys_open(path, flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
    REDIRECT_AT(dirfd, path)
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    if (write_flags(flags) && !allowed_at(dirfd, path)) return -1;
    return g_openat != NULL ? g_openat(dirfd, path, flags, mode) : sys_openat(dirfd, path, flags, mode);
}

int openat64(int dirfd, const char *path, int flags, ...) {
    REDIRECT_AT(dirfd, path)
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    if (write_flags(flags) && !allowed_at(dirfd, path)) return -1;
    return g_openat64 != NULL ? g_openat64(dirfd, path, flags, mode) : sys_openat(dirfd, path, flags, mode);
}

/* 编译器给「没有 O_CREAT 的 open」生成的定型版本（fortify） */
int __open_2(const char *path, int flags) {
    REDIRECT(path)
    if (write_flags(flags) && !allowed(path)) return -1;
    return g_open_2 != NULL ? g_open_2(path, flags) : sys_open(path, flags, 0);
}

int __openat_2(int dirfd, const char *path, int flags) {
    REDIRECT_AT(dirfd, path)
    if (write_flags(flags) && !allowed_at(dirfd, path)) return -1;
    return g_openat_2 != NULL ? g_openat_2(dirfd, path, flags) : sys_openat(dirfd, path, flags, 0);
}

int creat(const char *path, mode_t mode) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    return g_creat != NULL ? g_creat(path, mode) : sys_open(path, O_CREAT | O_WRONLY | O_TRUNC, mode);
}

int creat64(const char *path, mode_t mode) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    return g_creat64 != NULL ? g_creat64(path, mode) : sys_open(path, O_CREAT | O_WRONLY | O_TRUNC, mode);
}

static int flags_of_mode(const char *mode) {
    int flags = O_RDONLY;
    if (mode == NULL) return O_RDONLY;
    if (strchr(mode, '+') != NULL) flags = O_RDWR;
    else if (strchr(mode, 'w') != NULL || strchr(mode, 'a') != NULL) flags = O_WRONLY;
    if (strchr(mode, 'w') != NULL) flags |= O_CREAT | O_TRUNC;
    if (strchr(mode, 'a') != NULL) flags |= O_CREAT | O_APPEND;
    if (strchr(mode, 'x') != NULL) flags |= O_EXCL;
    return flags;
}

static FILE *fallback_fopen(const char *path, const char *mode) {
    int fd = sys_open(path, flags_of_mode(mode), 0666);
    if (fd < 0) return NULL;
    FILE *file = fdopen(fd, mode);
    if (file == NULL) close(fd);
    return file;
}

FILE *fopen(const char *path, const char *mode) {
    REDIRECT(path)
    if (write_mode(mode) && !allowed(path)) return NULL;
    return g_fopen != NULL ? g_fopen(path, mode) : fallback_fopen(path, mode);
}

FILE *fopen64(const char *path, const char *mode) {
    REDIRECT(path)
    if (write_mode(mode) && !allowed(path)) return NULL;
    return g_fopen64 != NULL ? g_fopen64(path, mode) : fallback_fopen(path, mode);
}

FILE *freopen(const char *path, const char *mode, FILE *stream) {
    REDIRECT(path)
    if (path != NULL && write_mode(mode) && !allowed(path)) return NULL;
    return g_freopen != NULL ? g_freopen(path, mode, stream) : NULL;
}

int mkdir(const char *path, mode_t mode) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    return g_mkdir != NULL ? g_mkdir(path, mode) : (int)syscall(SYS_mkdirat, AT_FDCWD, path, mode);
}

int mkdirat(int dirfd, const char *path, mode_t mode) {
    REDIRECT_AT(dirfd, path)
    if (!allowed_at(dirfd, path)) return -1;
    return g_mkdirat != NULL ? g_mkdirat(dirfd, path, mode) : (int)syscall(SYS_mkdirat, dirfd, path, mode);
}

int unlink(const char *path) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    return g_unlink != NULL ? g_unlink(path) : (int)syscall(SYS_unlinkat, AT_FDCWD, path, 0);
}

int unlinkat(int dirfd, const char *path, int flags) {
    REDIRECT_AT(dirfd, path)
    if (!allowed_at(dirfd, path)) return -1;
    return g_unlinkat != NULL ? g_unlinkat(dirfd, path, flags) : (int)syscall(SYS_unlinkat, dirfd, path, flags);
}

int rmdir(const char *path) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    return g_rmdir != NULL ? g_rmdir(path) : (int)syscall(SYS_unlinkat, AT_FDCWD, path, AT_REMOVEDIR);
}

int remove(const char *path) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    if (g_remove != NULL) return g_remove(path);
    if (syscall(SYS_unlinkat, AT_FDCWD, path, 0) == 0) return 0;
    return (int)syscall(SYS_unlinkat, AT_FDCWD, path, AT_REMOVEDIR);
}

int rename(const char *old_path, const char *new_path) {
    REDIRECT(old_path)
    REDIRECT(new_path)
    if (!allowed(old_path) || !allowed(new_path)) return -1;
    return g_rename != NULL ? g_rename(old_path, new_path)
                               : (int)syscall(SYS_renameat, AT_FDCWD, old_path, AT_FDCWD, new_path);
}

int renameat(int old_dirfd, const char *old_path, int new_dirfd, const char *new_path) {
    REDIRECT_AT(old_dirfd, old_path)
    REDIRECT_AT(new_dirfd, new_path)
    if (!allowed_at(old_dirfd, old_path) || !allowed_at(new_dirfd, new_path)) return -1;
    return g_renameat != NULL ? g_renameat(old_dirfd, old_path, new_dirfd, new_path)
                                 : (int)syscall(SYS_renameat, old_dirfd, old_path, new_dirfd, new_path);
}

/**
 * renameat2：GNU mv 走的是这个（coreutils 9.x 优先用它），漏了它就出现
 * 「mv: cannot move '/tmp/x' to '/tmp/y': No such file or directory」——路径没被改写，
 * 于是去真实 /tmp 找那个文件。软链、rename 都覆盖了，唯独这个口子漏了一版。
 */
/**
 * syscall() 直通：GNU coreutils 的 mv 用的是**裸系统调用**。
 *
 * 真机实测：Termux 的 coreutils（multi-call 的 bin/coreutils）在 mv 里走的是
 *   syscall(SYS_renameat2, …)   ← gnulib 的 renameatu 在 configure 时没找到 bionic 的
 *                                 renameat2（按较低 API 编译），于是退化成裸 syscall
 * 裸 syscall 完全绕过 LD_PRELOAD，所以我们包装 renameat2 也没用，
 * 表现就是「mv /tmp/a /tmp/b → No such file or directory」（它去真实 /tmp 找了）。
 * 这里只接管 need 路径改写的那一个 syscall 号，其余原样转发（读 6 个 varargs 与 bionic 自己一致）。
 * 说明：这是「libc 层改写」的固有边界，别的裸 syscall 依旧绕不过去（见文档第 21 节）。
 */
long syscall(long number, ...) {
    va_list ap;
    va_start(ap, number);
    long a1 = va_arg(ap, long);
    long a2 = va_arg(ap, long);
    long a3 = va_arg(ap, long);
    long a4 = va_arg(ap, long);
    long a5 = va_arg(ap, long);
    long a6 = va_arg(ap, long);
    va_end(ap);
    if ((number == SYS_renameat2 || number == SYS_renameat)) {
        char buf_old[PATH_MAX];
        char buf_new[PATH_MAX];
        const char *old_path = (const char *)a2;
        const char *new_path = (const char *)a4;
        const char *mapped_old = redirect(old_path, buf_old, sizeof(buf_old));
        const char *mapped_new = redirect(new_path, buf_new, sizeof(buf_new));
        if (!allowed_at((int)a1, mapped_old) || !allowed_at((int)a3, mapped_new)) return -1;
        if (g_syscall == NULL) {
            errno = ENOSYS;
            return -1;
        }
        if (number == SYS_renameat2) return g_syscall(number, a1, mapped_old, a3, mapped_new, a5);
        return g_syscall(number, a1, mapped_old, a3, mapped_new);
    }
    if (g_syscall == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return g_syscall(number, a1, a2, a3, a4, a5, a6);
}

int renameat2(int old_dirfd, const char *old_path, int new_dirfd, const char *new_path, unsigned int flags) {
    REDIRECT_AT(old_dirfd, old_path)
    REDIRECT_AT(new_dirfd, new_path)
    if (!allowed_at(old_dirfd, old_path) || !allowed_at(new_dirfd, new_path)) return -1;
    if (g_renameat2 != NULL) return g_renameat2(old_dirfd, old_path, new_dirfd, new_path, flags);
    return (int)syscall(SYS_renameat2, old_dirfd, old_path, new_dirfd, new_path, flags);
}

/* ------------------------------------------------- 硬链接：SELinux 不许，退化成复制

   真机实测（Android 16 / f2fs / enforcing）：

     avc: denied { link } for name="ws_a" dev="dm-67" ino=2471669
       scontext=u:r:untrusted_app:s0:c133,… tcontext=u:object_r:app_data_file:s0:c133,…
       tclass=file permissive=0 app=com.termux

   也就是说：**不是我们的 bug，内核层也没有解法**（要 root 改 SELinux 策略，或者换域）。而
   `ln`、`cp -l`、`git clone --local`、ccache、npm/pnpm 的缓存都走 link()，直接失败会让这些
   工具在半路炸掉。折中：真 link() 拿到 EACCES/EPERM 时**复制**一份出来。

   语义差异（提示词与文档里都写明）：新名字是独立 inode —— `stat -c %h` 是 1、`[ a -ef b ]`
   为假、改一个不影响另一个、占双份空间。选复制而不是符号链接，是因为源被删时符号链接会悬空
   （npm/pnpm 的 store 会被清理，node_modules 会整片坏掉）。ADSH_LINK_EMULATE=0 可关掉替身。 */

/** dirfd 相对路径 → 绝对路径（拿不到那个目录就返回 false） */
static bool at_absolute(int dirfd, const char *path, char *out, size_t size) {
    if (path == NULL || *path == '\0') return false;
    if (path[0] == '/' || dirfd == AT_FDCWD) return absolute(path, out, size);
    char link[64];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", dirfd);
    char base[PATH_MAX];
    g_busy = 1;
    ssize_t n = readlink(link, base, sizeof(base) - 1);
    g_busy = 0;
    if (n <= 0) return false;
    base[n] = '\0';
    snprintf(out, size, "%s/%s", base, path);
    return true;
}

/** link() 的替身：复制内容 + 权限位 + 时间戳；源是符号链接就照抄符号链接 */
static int copy_as_link(const char *old_path, const char *new_path, bool follow) {
    struct stat st;
    if (syscall(SYS_newfstatat, AT_FDCWD, old_path, &st, follow ? 0 : AT_SYMLINK_NOFOLLOW) != 0)
        return -1;
    if (S_ISLNK(st.st_mode)) {
        char target[PATH_MAX];
        ssize_t n = (ssize_t)syscall(SYS_readlinkat, AT_FDCWD, old_path, target, sizeof(target) - 1);
        if (n <= 0) return -1;
        target[n] = '\0';
        return (int)syscall(SYS_symlinkat, target, AT_FDCWD, new_path);
    }
    if (!S_ISREG(st.st_mode)) {
        errno = EPERM; /* 设备 / 目录 / fifo：POSIX 本来也不允许硬链接 */
        return -1;
    }
    int in = (int)syscall(SYS_openat, AT_FDCWD, old_path, O_RDONLY | O_CLOEXEC, 0);
    if (in < 0) return -1;
    int out = (int)syscall(SYS_openat, AT_FDCWD, new_path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC,
                           (unsigned)(st.st_mode & 07777));
    if (out < 0) {
        int saved = errno;
        syscall(SYS_close, in);
        errno = saved;
        return -1;
    }
    char buf[65536];
    int rc = 0;
    for (;;) {
        ssize_t r = read(in, buf, sizeof(buf));
        if (r < 0) {
            if (errno == EINTR) continue;
            rc = -1;
            break;
        }
        if (r == 0) break;
        ssize_t off = 0;
        while (off < r) {
            ssize_t w = write(out, buf + off, (size_t)(r - off));
            if (w < 0) {
                if (errno == EINTR) continue;
                rc = -1;
                break;
            }
            off += w;
        }
        if (rc != 0) break;
    }
    if (rc == 0) {
        syscall(SYS_fchmod, out, (unsigned)(st.st_mode & 07777));
        struct timespec ts[2];
        ts[0] = st.st_atim;
        ts[1] = st.st_mtim;
        syscall(SYS_utimensat, AT_FDCWD, new_path, ts, 0);
    }
    int saved = errno;
    syscall(SYS_close, out);
    syscall(SYS_close, in);
    if (rc != 0) {
        syscall(SYS_unlinkat, AT_FDCWD, new_path, 0);
        errno = saved;
    }
    return rc;
}

int link(const char *old_path, const char *new_path) {
    REDIRECT(old_path)
    REDIRECT(new_path)
    if (!allowed(old_path) || !allowed(new_path)) return -1;
    int rc = g_link != NULL ? g_link(old_path, new_path)
                            : (int)syscall(SYS_linkat, AT_FDCWD, old_path, AT_FDCWD, new_path, 0);
    if (rc == 0 || !g_link_emulate || (errno != EACCES && errno != EPERM)) return rc;
    int denied = errno;
    if (copy_as_link(old_path, new_path, false) == 0) return 0;
    if (errno != EEXIST) errno = denied; /* 复制也失败：交回原始的「不允许」 */
    return -1;
}

int linkat(int old_dirfd, const char *old_path, int new_dirfd, const char *new_path, int flags) {
    REDIRECT_AT(old_dirfd, old_path)
    REDIRECT_AT(new_dirfd, new_path)
    if (!allowed_at(old_dirfd, old_path) || !allowed_at(new_dirfd, new_path)) return -1;
    int rc = g_linkat != NULL ? g_linkat(old_dirfd, old_path, new_dirfd, new_path, flags)
                              : (int)syscall(SYS_linkat, old_dirfd, old_path, new_dirfd, new_path, flags);
    if (rc == 0 || !g_link_emulate || (errno != EACCES && errno != EPERM)) return rc;
    int denied = errno;
    char src[PATH_MAX];
    char dst[PATH_MAX];
    bool follow = (flags & AT_SYMLINK_FOLLOW) != 0;
    if (at_absolute(old_dirfd, old_path, src, sizeof(src)) &&
        at_absolute(new_dirfd, new_path, dst, sizeof(dst)) &&
        copy_as_link(src, dst, follow) == 0) return 0;
    if (errno != EEXIST) errno = denied;
    return -1;
}

int symlink(const char *target, const char *link_path) {
    REDIRECT(target)
    REDIRECT(link_path)
    /* 只拦新链接本身：target 是内容，不是这次调用写的东西 */
    if (!allowed(link_path)) return -1;
    return g_symlink != NULL ? g_symlink(target, link_path)
                                : (int)syscall(SYS_symlinkat, target, AT_FDCWD, link_path);
}

int symlinkat(const char *target, int dirfd, const char *link_path) {
    REDIRECT(target)
    REDIRECT_AT(dirfd, link_path)
    if (!allowed_at(dirfd, link_path)) return -1;
    return g_symlinkat != NULL ? g_symlinkat(target, dirfd, link_path)
                                  : (int)syscall(SYS_symlinkat, target, dirfd, link_path);
}

/**
 * chdir/fchdir：**必须**一起改写，否则相对路径会绕过整个映射。
 *
 * 真机实测：进入 /tmp 再创建相对文件名时，chdir 不经过我们就落进真实 /tmp（App 无写权限），
 * 那个相对名自然也落在真实 /tmp —— 表现是「绝对路径 /tmp/x 能用，相对路径就 Permission denied」。
 * 把 chdir 也改写掉，进程的 cwd 就真的在 $TMPDIR 里，后续相对路径、getcwd、mkdir -p 的逐级创建全部自洽。
 */
int chdir(const char *path) {
    REDIRECT(path)
    return g_chdir != NULL ? g_chdir(path) : (int)syscall(SYS_chdir, path);
}

int fchdir(int fd) {
    return g_fchdir != NULL ? g_fchdir(fd) : (int)syscall(SYS_fchdir, fd);
}

/* chmod / chown 家族：既要做 /tmp 映射，也要受围栏管辖（它们是「修改」而不是读） */
int chmod(const char *path, mode_t mode) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    return g_chmod != NULL ? g_chmod(path, mode) : (int)syscall(SYS_fchmodat, AT_FDCWD, path, mode, 0);
}

int fchmod(int fd, mode_t mode) {
    if (!allowed_fd(fd)) return -1;
    return g_fchmod != NULL ? g_fchmod(fd, mode) : (int)syscall(SYS_fchmod, fd, mode);
}

int fchmodat(int dirfd, const char *path, mode_t mode, int flags) {
    REDIRECT_AT(dirfd, path)
    if (!allowed_at(dirfd, path)) return -1;
    return g_fchmodat != NULL ? g_fchmodat(dirfd, path, mode, flags)
                                 : (int)syscall(SYS_fchmodat, dirfd, path, mode, flags);
}

int chown(const char *path, uid_t owner, gid_t group) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    return g_chown != NULL ? g_chown(path, owner, group)
                              : (int)syscall(SYS_fchownat, AT_FDCWD, path, owner, group, 0);
}

int lchown(const char *path, uid_t owner, gid_t group) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    return g_lchown != NULL ? g_lchown(path, owner, group)
                               : (int)syscall(SYS_fchownat, AT_FDCWD, path, owner, group, AT_SYMLINK_NOFOLLOW);
}

int fchown(int fd, uid_t owner, gid_t group) {
    if (!allowed_fd(fd)) return -1;
    return g_fchown != NULL ? g_fchown(fd, owner, group) : (int)syscall(SYS_fchown, fd, owner, group);
}

int fchownat(int dirfd, const char *path, uid_t owner, gid_t group, int flags) {
    REDIRECT_AT(dirfd, path)
    if (!allowed_at(dirfd, path)) return -1;
    return g_fchownat != NULL ? g_fchownat(dirfd, path, owner, group, flags)
                                 : (int)syscall(SYS_fchownat, dirfd, path, owner, group, flags);
}

int truncate(const char *path, off_t length) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    return g_truncate != NULL ? g_truncate(path, length) : (int)syscall(SYS_truncate, path, length);
}

int truncate64(const char *path, off64_t length) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    return g_truncate64 != NULL ? g_truncate64(path, length) : (int)syscall(SYS_truncate, path, (off_t)length);
}


/* ------------------------------------------------------------------ exec */

/**
 * execve：只做 /tmp 映射，然后把链路原样交给下一个 preload 库（termux-exec）。
 *
 * app 私有目录里的可执行文件与脚本由 termux-exec 负责（app 数据目录里的路径改写成
 * /system/bin/linker64 形式、以及 shebang 的处理）——这是 targetSdk 37 的 W^X 限制下唯一
 * 能跑通的路子。我们不再自己做用户态 shebang 解析与脚本自愈：那两件事都是为「官方前缀 →
 * 等长别名」服务的，方案 A 之后没有任何前缀需要改写（见 docs/UI-v6-report.md 第 22 节）。
 */
int execve(const char *path, char *const argv[], char *const envp[]) {
    REDIRECT(path)
    ensure_init();
    if (g_execve != NULL) return g_execve(path, argv, envp);
    return (int)syscall(SYS_execve, path, argv, envp);
}
int execv(const char *path, char *const argv[]) {
    ensure_init();
    return execve(path, argv, environ);
}

int execvp(const char *file, char *const argv[]) {
    ensure_init();
    if (file != NULL && strchr(file, '/') != NULL) return execve(file, argv, environ);
    /* PATH 搜索：找到第一个可执行的候选就交给 execve（它只做 /tmp 映射，exec 本身由 termux-exec 接力） */
    const char *path_env = getenv("PATH");
    if (file != NULL && path_env != NULL) {
        char list[PATH_MAX * 2];
        snprintf(list, sizeof(list), "%s", path_env);
        char *save = NULL;
        for (char *dir = strtok_r(list, ":", &save); dir != NULL; dir = strtok_r(NULL, ":", &save)) {
            char candidate[PATH_MAX];
            snprintf(candidate, sizeof(candidate), "%s/%s", dir, file);
            if (access(candidate, X_OK) != 0) continue;
            execve(candidate, argv, environ);
            if (errno != ENOENT && errno != ENOTDIR) return -1;
        }
    }
    return execve(file, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    ensure_init();
    if (file != NULL && strchr(file, '/') != NULL) return execve(file, argv, envp);
    const char *path_env = getenv("PATH");
    if (file != NULL && path_env != NULL) {
        char list[PATH_MAX * 2];
        snprintf(list, sizeof(list), "%s", path_env);
        char *save = NULL;
        for (char *dir = strtok_r(list, ":", &save); dir != NULL; dir = strtok_r(NULL, ":", &save)) {
            char candidate[PATH_MAX];
            snprintf(candidate, sizeof(candidate), "%s/%s", dir, file);
            if (access(candidate, X_OK) != 0) continue;
            execve(candidate, argv, envp);
            if (errno != ENOENT && errno != ENOTDIR) return -1;
        }
    }
    return execve(file, argv, envp);
}


/* ------------------------------------------------- 读/判断类入口（也做 /tmp 映射）

   [ -f /tmp/x ]、test -x、ls /tmp、touch /tmp/x 这些**不经过 open/exec**：bash 的内建判断走
   stat/access，ls 走 opendir，touch 走 utimensat —— 不映射的话它们看到的是真实 /tmp（空的、
   而且不可写），与 open 看到的 $TMPDIR 不是同一个地方。这些入口不参与围栏判决（读一律放行）。 */

int stat(const char *path, struct stat *buf) {
    REDIRECT(path)
    static int (*real)(const char *, struct stat *);
    if (real == NULL) real = (int (*)(const char *, struct stat *))resolve("stat");
    return real != NULL ? real(path, buf) : (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, 0);
}

int stat64(const char *path, struct stat64 *buf) {
    REDIRECT(path)
    static int (*real)(const char *, struct stat64 *);
    if (real == NULL) real = (int (*)(const char *, struct stat64 *))resolve("stat64");
    return real != NULL ? real(path, buf) : (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, 0);
}

int lstat(const char *path, struct stat *buf) {
    REDIRECT(path)
    static int (*real)(const char *, struct stat *);
    if (real == NULL) real = (int (*)(const char *, struct stat *))resolve("lstat");
    return real != NULL ? real(path, buf) : (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, AT_SYMLINK_NOFOLLOW);
}

int lstat64(const char *path, struct stat64 *buf) {
    REDIRECT(path)
    static int (*real)(const char *, struct stat64 *);
    if (real == NULL) real = (int (*)(const char *, struct stat64 *))resolve("lstat64");
    return real != NULL ? real(path, buf) : (int)syscall(SYS_newfstatat, AT_FDCWD, path, buf, AT_SYMLINK_NOFOLLOW);
}

int fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    REDIRECT_AT(dirfd, path)
    static int (*real)(int, const char *, struct stat *, int);
    if (real == NULL) real = (int (*)(int, const char *, struct stat *, int))resolve("fstatat");
    return real != NULL ? real(dirfd, path, buf, flags) : (int)syscall(SYS_newfstatat, dirfd, path, buf, flags);
}

int fstatat64(int dirfd, const char *path, struct stat64 *buf, int flags) {
    REDIRECT_AT(dirfd, path)
    static int (*real)(int, const char *, struct stat64 *, int);
    if (real == NULL) real = (int (*)(int, const char *, struct stat64 *, int))resolve("fstatat64");
    return real != NULL ? real(dirfd, path, buf, flags) : (int)syscall(SYS_newfstatat, dirfd, path, buf, flags);
}

int access(const char *path, int mode) {
    REDIRECT(path)
    static int (*real)(const char *, int);
    if (real == NULL) real = (int (*)(const char *, int))resolve("access");
    return real != NULL ? real(path, mode) : (int)syscall(SYS_faccessat, AT_FDCWD, path, mode, 0);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
    REDIRECT_AT(dirfd, path)
    static int (*real)(int, const char *, int, int);
    if (real == NULL) real = (int (*)(int, const char *, int, int))resolve("faccessat");
    return real != NULL ? real(dirfd, path, mode, flags) : (int)syscall(SYS_faccessat, dirfd, path, mode, flags);
}

ssize_t readlink(const char *path, char *buf, size_t size) {
    REDIRECT(path)
    static ssize_t (*real)(const char *, char *, size_t);
    if (real == NULL) real = (ssize_t (*)(const char *, char *, size_t))resolve("readlink");
    return real != NULL ? real(path, buf, size) : (ssize_t)syscall(SYS_readlinkat, AT_FDCWD, path, buf, size);
}

ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size) {
    REDIRECT_AT(dirfd, path)
    static ssize_t (*real)(int, const char *, char *, size_t);
    if (real == NULL) real = (ssize_t (*)(int, const char *, char *, size_t))resolve("readlinkat");
    return real != NULL ? real(dirfd, path, buf, size) : (ssize_t)syscall(SYS_readlinkat, dirfd, path, buf, size);
}

DIR *opendir(const char *path) {
    REDIRECT(path)
    static DIR *(*real)(const char *);
    if (real == NULL) real = (DIR * (*)(const char *)) resolve("opendir");
    if (real != NULL) return real(path);
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    return fd >= 0 ? fdopendir(fd) : NULL;
}

int utimensat(int dirfd, const char *path, const struct timespec times[2], int flags) {
    REDIRECT_AT(dirfd, path)
    static int (*real)(int, const char *, const struct timespec[2], int);
    if (real == NULL) real = (int (*)(int, const char *, const struct timespec[2], int))resolve("utimensat");
    if (real != NULL) return real(dirfd, path, times, flags);
    return (int)syscall(SYS_utimensat, dirfd, path, times, flags);
}

/* 新版 coreutils（GNU 9.x）与部分工具用 statx 而不是 stat：一样要做 /tmp 映射 */
int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *buf) {
    REDIRECT_AT(dirfd, path)
    static int (*real)(int, const char *, int, unsigned int, struct statx *);
    if (real == NULL) real = (int (*)(int, const char *, int, unsigned int, struct statx *))resolve("statx");
    if (real != NULL) return real(dirfd, path, flags, mask, buf);
    return (int)syscall(SYS_statx, dirfd, path, flags, mask, buf);
}
