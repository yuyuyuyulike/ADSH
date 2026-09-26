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
 * 顺带承担的 Android 适配（第 67 轮）
 * ---------------------------------
 *  - **脚本自愈**：targetSdk ≥ 29 的应用不能 exec 自己 home 目录里的文件（安卓 10 起的 W^X），
 *    termux-exec 的 linker64 改写只救 ELF，于是 npm / pip / dpkg 的 postinst 这些**后来装出来**
 *    的脚本全是 Permission denied。shim 在脚本 exec 被拒（EACCES/EPERM）时自己解析 shebang、
 *    按内核的 argv 规则改 exec 解释器 —— 见 shebang_retry 的长注释。
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
#include <stdint.h>
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
/** 环境里真的给了 ADSH_FENCE_MODE 吗（没有 = 不挂围栏；exec 给子进程补环境时要用这个区分） */
static bool g_mode_given;
/** 见过的路径：用于 App 侧确认 preload 真的生效（写一次就够） */
static char g_mark[PATH_MAX];
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_reports;

/** /tmp 的落点（ADSH_TMP_REDIRECT = $PREFIX/tmp）：安卓没有可写的 /tmp */
static char g_tmp[PATH_MAX];
/** 硬链接替身：SELinux 不给 app 私有目录 link 权限，失败时退化成复制（ADSH_LINK_EMULATE=0 关） */
static bool g_link_emulate = true;

/**
 * 原始 LD_PRELOAD 清单（init 时原样抄下来）。exec bionic 子进程时用它把 preload 补回去：
 * 于是 `env -u LD_PRELOAD cmd` 摘不掉围栏 —— cmd 是由**当前这个已经加载了 shim 的进程**
 * exec 的，shim 在 exec 之前把清单重新写进子进程的环境（第 66 轮）。
 */
static char g_preload[PATH_MAX * 2];
/** shim 自己的绝对路径（dladdr 拿；g_preload 缺失时的兜底） */
static char g_self[PATH_MAX];
/**
 * bionic 的连接器路径：ELF 的 PT_INTERP 以它开头才算「自己人」。
 * ADSH_BIONIC_LINKER 可以覆盖它 —— 本地自测（桌面 Linux，解释器是 ld-linux-x86-64）要用。
 */
static char g_bionic_linker[PATH_MAX] = "/system/bin/linker64";
/** chmod 被文件系统静默改写的提示次数（与拒绝标记分开计数） */
static int g_mode_reports;

/** shebang 自愈的递归上限（与内核的 BINPRM_MAX_RECURSION 同值） */
#define MAX_SHEBANG_DEPTH 4
/**
 * 自测旋钮（ADSH_SHEBANG_FORCE=1）：对 `#!` 脚本**假装内核拒绝了这次 exec**，直接走自愈分支。
 * 桌面 Linux 与真机 runas_app 域都没有那条 W^X 拒绝，没有它就验证不了自愈本身（第 67 轮）。
 */
static bool g_shebang_force;
/** 自愈递归深度（线程局部：exec 失败会在同一个进程映像里一层层重试） */
static __thread int g_shebang_depth;

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
    if (mode != NULL) {
        snprintf(g_mode, sizeof(g_mode), "%s", mode);
        g_mode_given = true;
    }

    const char *tmp = env_or_null("ADSH_TMP_REDIRECT");
    if (tmp != NULL) snprintf(g_tmp, sizeof(g_tmp), "%s", tmp);

    /* 记下 LD_PRELOAD 原样清单与自己的绝对路径：exec 时要给 bionic 子进程补回去（第 66 轮）。
       清单要在这里（environ 刚可读时）抄，用户的命令之后怎么 unset 都不影响这一份。 */
    const char *lp = getenv("LD_PRELOAD");
    if (lp != NULL) snprintf(g_preload, sizeof(g_preload), "%s", lp);
    {
        Dl_info info;
        if (dladdr((void *)&g_preload, &info) && info.dli_fname != NULL) {
            snprintf(g_self, sizeof(g_self), "%s", info.dli_fname);
        }
    }
    const char *bl = env_or_null("ADSH_BIONIC_LINKER");
    if (bl != NULL) snprintf(g_bionic_linker, sizeof(g_bionic_linker), "%s", bl);

    /* 抄完就把 LD_PRELOAD 从**本进程**的环境里摘掉（第 66 轮真机教训）：
       有些 exec 路径不走我们的拦截 —— toybox 的 /system/bin/env 实测如此 —— 那时子进程
       拿到的就是本进程的 environ。留着 bionic 的 preload，glibc/musl 子进程会直接被搞死
       （error while loading shared libraries: liblog.so / Could not find a PHDR: broken
       executable?，officecli 那一轮就是这个）。摘掉之后：
         - bionic 子进程：exec 前由 fixup_exec_env 按 g_preload 补回来，围栏照旧；
         - 外部 libc 子进程：本来也装不了 bionic 库，干干净净地跑。 */
    unsetenv("LD_PRELOAD");

    /* 硬链接替身（默认开）：安卓的 SELinux 不给 untrusted_app 对 app_data_file 的 link
       权限（avc: denied { link }），真 link() 必然 EACCES。设 0 就回到「老实报错」。 */
    const char *no_link = env_or_null("ADSH_LINK_EMULATE");
    if (no_link != NULL && no_link[0] == '0' && no_link[1] == '\0') g_link_emulate = false;

    /* shebang 自愈的自测旋钮（默认关；见 g_shebang_force 的注释） */
    const char *force = env_or_null("ADSH_SHEBANG_FORCE");
    if (force != NULL && force[0] == '1' && force[1] == '\0') g_shebang_force = true;

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

    /* 门闸：**只有显式给了约束模式才启用写判决**。
       完全权限（danger-full-access）、终端页与安装器都不给约束模式 —— 那些进程里 shim
       照样待在 LD_PRELOAD 里（/tmp 映射与硬链接替身还要用），但一次写判决都不做。
       第 64 轮真机教训：以前这里无条件 g_enabled = true，于是「挂了 shim 但没给模式」的
       进程以**空白的可写根列表**运行 —— 连工作区、$TMPDIR、$PREFIX/tmp 都写不了，拒绝
       标记里的模式名还是空的（App 侧在完全权限下正是只挂 shim、不给模式，见
       TermuxRuntime.environment 与 fenceEnv）。 */
    if (mode == NULL || strcmp(mode, "danger-full-access") == 0) {
        g_enabled = false;
        return;
    }

    /* 白名单：**空串是合法形态**（read-only：命令照跑，任何写都被拒）。
       以前用「roots == NULL 就不启用」的写法，read-only 的空白名单会被当成「没挂围栏」，
       等于把只读预设变成完整写权限 —— 这条不能退回去。 */
    const char *roots = env_or_null("ADSH_FENCE_ROOTS");
    char list[PATH_MAX * 2];
    snprintf(list, sizeof(list), "%s", roots != NULL ? roots : "");
    char *save = NULL;
    for (char *part = strtok_r(list, ":", &save); part != NULL; part = strtok_r(NULL, ":", &save)) {
        if (g_root_count >= MAX_ROOTS) break;
        char real[PATH_MAX];
        if (!canonical(part, real, sizeof(real))) continue;
        snprintf(g_roots[g_root_count], PATH_MAX, "%s", real);
        g_root_count++;
    }
    /* g_root_count 可以为 0：read-only 就是「没有任何可写根」，allowed() 会让每一次写都失败 */
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

/**
 * 极简 constructor：只做一件事 —— 把 LD_PRELOAD 从**本进程**的环境里抄下来并摘掉（第 66 轮）。
 *
 * 为什么必须在 constructor 里做：有的 exec 路径不走 LD_PRELOAD 拦截（toybox 的
 * /system/bin/env 实测如此），那时子进程拿到的就是本进程的 environ。如果惰性初始化还没跑
 * 进程就 exec 了，bionic 的 preload 会原样传给 glibc/musl 子进程，直接把它搞死
 * （liblog.so not found / Could not find a PHDR）。constructor 里**不调 dlsym**（真机实测那会
 * 卡死在 linker 锁上），只 getenv + unsetenv —— 这两件事是安全的。
 */
__attribute__((constructor)) static void adsh_fence_ctor(void) {
    const char *lp = getenv("LD_PRELOAD");
    if (lp != NULL && lp[0] != '\0') {
        snprintf(g_preload, sizeof(g_preload), "%s", lp);
        unsetenv("LD_PRELOAD");
    }
}

/* 其余初始化仍然是**惰性**的：真实符号解析（dlsym）只能等 linker 锁松开之后做 —— 这就是上面
   那个 constructor 里不能碰 dlsym 的原因。见 init_env 与 ensure_init 的注释。 */

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

/**
 * 文件系统**静默改写** chmod 时打一行（第 66 轮）。
 *
 * 真机实测：工作区在 Android 的模拟存储（FUSE）上时 `chmod 755` 返回成功、权限位却停在 0660
 * （执行位消失），于是「命令成功了但 ./script 还是 Permission denied」。这不是沙箱拒绝 ——
 * 所以**不用** [sandbox: …] 标记（提示词的 android 段也写明 FUSE 限制不带那个标记），但也不能
 * 静默：打一行请求值与实际值，模型才知道该把需要执行位的活挪到 $ADSH_SCRATCH。
 */
static void report_mode_ignored(mode_t want, mode_t got) {
    bool print = false;
    pthread_mutex_lock(&g_lock);
    if (g_mode_reports < MAX_REPORTS) {
        g_mode_reports++;
        print = true;
    }
    pthread_mutex_unlock(&g_lock);
    if (!print) return;
    fprintf(stderr,
            "[fs: chmod requested %04o but the file reports %04o — Android's emulated storage ignores "
            "chmod (no exec bits, no symlinks); work that needs them belongs under $ADSH_SCRATCH]\n",
            (unsigned)(want & 07777), (unsigned)(got & 07777));
}

/* chmod / chown 家族：既要做 /tmp 映射，也要受围栏管辖（它们是「修改」而不是读） */
int chmod(const char *path, mode_t mode) {
    REDIRECT(path)
    if (!allowed(path)) return -1;
    int rc = g_chmod != NULL ? g_chmod(path, mode) : (int)syscall(SYS_fchmodat, AT_FDCWD, path, mode, 0);
    if (rc == 0) {
        struct stat st;
        if (syscall(SYS_newfstatat, AT_FDCWD, path, &st, 0) == 0 && (st.st_mode & 07777) != (mode & 07777)) {
            report_mode_ignored(mode, st.st_mode);
        }
    }
    return rc;
}

int fchmod(int fd, mode_t mode) {
    if (!allowed_fd(fd)) return -1;
    int rc = g_fchmod != NULL ? g_fchmod(fd, mode) : (int)syscall(SYS_fchmod, fd, mode);
    if (rc == 0) {
        struct stat st;
        if (syscall(SYS_fstat, fd, &st) == 0 && (st.st_mode & 07777) != (mode & 07777)) {
            report_mode_ignored(mode, st.st_mode);
        }
    }
    return rc;
}

int fchmodat(int dirfd, const char *path, mode_t mode, int flags) {
    REDIRECT_AT(dirfd, path)
    if (!allowed_at(dirfd, path)) return -1;
    int rc = g_fchmodat != NULL ? g_fchmodat(dirfd, path, mode, flags)
                                : (int)syscall(SYS_fchmodat, dirfd, path, mode, flags);
    if (rc == 0) {
        struct stat st;
        if (syscall(SYS_newfstatat, dirfd, path, &st, flags) == 0 && (st.st_mode & 07777) != (mode & 07777)) {
            report_mode_ignored(mode, st.st_mode);
        }
    }
    return rc;
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


/* ---------------------------------------------------- exec 的目标判定与环境修补 */

/** 路径里有没有 /glibc/ 这个组件（Termux glibc 前缀的约定） */
static bool path_in_glibc_prefix(const char *path) {
    if (path == NULL) return false;
    return strstr(path, "/glibc/") != NULL;
}

/**
 * ELF 的 PT_INTERP 是不是**外部 libc 的加载器**（bionic 的 preload 装不进去）。
 *
 * 只对 PT_INTERP 成立：那里写的确实是加载器路径（`/system/bin/linker64`、glibc 的
 * `ld-linux-*.so`）。**不能拿它判 shebang** —— shebang 里写的是**解释器程序**
 * （`$PREFIX/bin/sh`），那是个普通的 bionic 程序，按「不是 linker64 就算外部」判会把所有
 * Termux 脚本误判成外部 libc（第 72 轮实测：`[fixup] … foreign=1 … -> (empty)`，
 * 于是每个脚本子进程都丢掉了围栏 shim 与 termux-exec，read-only 下脚本照样能写）。
 * shebang 走 [target_is_foreign] 递归判。
 */
static bool interp_is_foreign(const char *interp) {
    if (interp == NULL || interp[0] == '\0') return false;
    size_t bl = strlen(g_bionic_linker);
    if (bl > 0 && strncmp(interp, g_bionic_linker, bl) == 0) return false;
    if (strncmp(interp, "/system/bin/linker", 18) == 0) return false;
    return true;
}

/** 用原始系统调用读文件开头（不惊动自己的 open/read 拦截） */
static ssize_t raw_read_at(const char *path, void *buf, size_t n) {
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return -1;
    ssize_t got = syscall(SYS_read, fd, buf, n);
    syscall(SYS_close, fd);
    return got;
}

/**
 * 目标可执行文件是不是非 bionic（glibc / musl）程序。
 *
 * 第 66 轮真机实测的三种入口都要覆盖：`$PREFIX/glibc/bin/ls`（解释器是 glibc 的 ld.so）、
 * `$PREFIX/glibc/lib/ld-linux-aarch64.so.1`（本身就是静态的 glibc 加载器）、以及 glibc-runner
 * 包装出来的脚本。读不到/认不出来一律当 bionic —— 宁可继续挂围栏，也别把 bionic 的库塞进
 * 外部 libc 的进程。
 *
 * @param depth shebang 递归深度（脚本的解释器可能又是脚本），上限见 [FOREIGN_DEPTH_MAX]
 */
static bool target_is_foreign(const char *path, int depth) {
    unsigned char buf[512];
    ssize_t n = raw_read_at(path, buf, sizeof(buf));
    if (n < 4) return path_in_glibc_prefix(path);
    if (buf[0] == '#' && buf[1] == '!') {
        size_t i = 2;
        while (i < (size_t)n && (buf[i] == ' ' || buf[i] == '\t')) i++;
        size_t start = i;
        while (i < (size_t)n && buf[i] != '\n' && buf[i] != ' ' && buf[i] != '\t') i++;
        char interp[PATH_MAX];
        size_t len = i - start;
        if (len == 0 || len >= sizeof(interp)) return false;
        memcpy(interp, buf + start, len);
        interp[len] = '\0';
        /* shebang 里是**解释器程序**，不是加载器：要按它自己是不是外部 libc 来判
           （`#!/data/data/com.termux/files/usr/bin/sh` → 普通的 bionic 程序 → 不是外部）。
           深度用完就当 bionic：宁可多挂一层围栏，也别把 bionic 的库塞给 glibc 进程。 */
        if (path_in_glibc_prefix(interp)) return true;
        if (depth <= 0) return false;
        return target_is_foreign(interp, depth - 1);
    }
    if (!(buf[0] == 0x7f && buf[1] == 'E' && buf[2] == 'L' && buf[3] == 'F')) return false;
    bool is64 = buf[4] == 2;
    if (buf[4] != 1 && buf[4] != 2) return false;
    bool le = buf[5] == 1;
    off_t phoff = 0;
    uint16_t phentsize = 0, phnum = 0;
    if (is64) {
        uint64_t v64 = 0;
        uint16_t v16 = 0;
        memcpy(&v64, buf + 32, 8);
        if (!le) v64 = __builtin_bswap64(v64);
        phoff = (off_t)v64;
        memcpy(&v16, buf + 54, 2);
        if (!le) v16 = __builtin_bswap16(v16);
        phentsize = v16;
        memcpy(&v16, buf + 56, 2);
        if (!le) v16 = __builtin_bswap16(v16);
        phnum = v16;
    } else {
        uint32_t v32 = 0;
        uint16_t v16 = 0;
        memcpy(&v32, buf + 28, 4);
        if (!le) v32 = __builtin_bswap32(v32);
        phoff = (off_t)v32;
        memcpy(&v16, buf + 42, 2);
        if (!le) v16 = __builtin_bswap16(v16);
        phentsize = v16;
        memcpy(&v16, buf + 44, 2);
        if (!le) v16 = __builtin_bswap16(v16);
        phnum = v16;
    }
    if (phentsize == 0 || phnum == 0 || phnum > 64 || phentsize > 64) return path_in_glibc_prefix(path);
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return path_in_glibc_prefix(path);
    bool foreign = false;
    bool saw_interp = false;
    unsigned char ph[64];
    for (uint16_t i = 0; i < phnum; i++) {
        off_t off = phoff + (off_t)i * phentsize;
        if (syscall(SYS_lseek, fd, off, SEEK_SET) < 0) break;
        if (syscall(SYS_read, fd, ph, phentsize) != (ssize_t)phentsize) break;
        uint32_t type = 0;
        uint64_t poffset = 0, pfilesz = 0;
        memcpy(&type, ph, 4);
        if (!le) type = __builtin_bswap32(type);
        if (is64) {
            memcpy(&poffset, ph + 8, 8);
            if (!le) poffset = __builtin_bswap64(poffset);
            memcpy(&pfilesz, ph + 32, 8);
            if (!le) pfilesz = __builtin_bswap64(pfilesz);
        } else {
            uint32_t o32 = 0, s32 = 0;
            memcpy(&o32, ph + 4, 4);
            if (!le) o32 = __builtin_bswap32(o32);
            poffset = o32;
            memcpy(&s32, ph + 16, 4);
            if (!le) s32 = __builtin_bswap32(s32);
            pfilesz = s32;
        }
        if (type != 3) continue;   /* PT_INTERP */
        if (poffset == 0 || pfilesz == 0 || pfilesz >= PATH_MAX) break;
        char interp[PATH_MAX];
        if (syscall(SYS_lseek, fd, (off_t)poffset, SEEK_SET) < 0) break;
        ssize_t got = syscall(SYS_read, fd, interp, (size_t)pfilesz);
        if (got <= 0) break;
        interp[got < (ssize_t)sizeof(interp) ? got : (ssize_t)sizeof(interp) - 1] = '\0';
        foreign = interp_is_foreign(interp) || path_in_glibc_prefix(interp);
        saw_interp = true;
        break;
    }
    syscall(SYS_close, fd);
    /* 静态 ELF（没有 PT_INTERP）：只有落在 glibc 前缀下的才算外部（ld-linux-*.so 就是这种） */
    if (!saw_interp) return path_in_glibc_prefix(path);
    return foreign;
}

/** shebang 递归判定的深度上限（脚本 → 脚本 → …）：与内核 BINPRM_MAX_RECURSION 同量级 */
#define FOREIGN_DEPTH_MAX 4

/** 目标可执行文件是不是非 bionic（glibc / musl）程序（shebang 递归的入口） */
static bool is_foreign_executable(const char *path) {
    return target_is_foreign(path, FOREIGN_DEPTH_MAX);
}

/** 现成的 LD_PRELOAD 里有没有我们自己的围栏库（按文件名认，安装路径每次都会变） */
static bool preload_has_ours(const char *value) {
    if (value == NULL) return false;
    if (strstr(value, "libadshfence.so") != NULL) return true;
    return g_self[0] != '\0' && strstr(value, g_self) != NULL;
}

/** 现成的 LD_PRELOAD 里有没有 bionic 侧的条目（不在 /glibc/ 下的都算） */
static bool preload_has_bionic(const char *value) {
    if (value == NULL) return false;
    char list[PATH_MAX * 2];
    snprintf(list, sizeof(list), "%s", value);
    char *save = NULL;
    for (char *part = strtok_r(list, " :", &save); part != NULL; part = strtok_r(NULL, " :", &save)) {
        if (strstr(part, "/glibc/") == NULL) return true;
    }
    return false;
}

/** 只留 glibc 前缀下的 preload 条目（grun 会设 $PREFIX/glibc/lib/libtermux-exec.so） */
static void keep_glibc_preload(const char *value, char *out, size_t size) {
    out[0] = '\0';
    if (value == NULL) return;
    char list[PATH_MAX * 2];
    snprintf(list, sizeof(list), "%s", value);
    char *save = NULL;
    for (char *part = strtok_r(list, " :", &save); part != NULL; part = strtok_r(NULL, " :", &save)) {
        if (strstr(part, "/glibc/") == NULL) continue;
        if (out[0] != '\0') strncat(out, " ", size - strlen(out) - 1);
        strncat(out, part, size - strlen(out) - 1);
    }
}

/** 去掉我们自己那两个 preload 条目（/system 下的进程装不了 app 私有库，留着只会往孙子进程漏） */
static void drop_ours_preload(const char *value, char *out, size_t size) {
    out[0] = '\0';
    if (value == NULL) return;
    char list[PATH_MAX * 2];
    snprintf(list, sizeof(list), "%s", value);
    char *save = NULL;
    for (char *part = strtok_r(list, " :", &save); part != NULL; part = strtok_r(NULL, " :", &save)) {
        if (strstr(part, "libadshfence.so") != NULL) continue;
        if (strstr(part, "libtermux-exec") != NULL) continue;
        if (out[0] != '\0') strncat(out, " ", size - strlen(out) - 1);
        strncat(out, part, size - strlen(out) - 1);
    }
}

/** /system、/apex、/vendor 下的可执行文件跑在系统命名空间里：app 私有目录的库**根本加载不进去**
    （真机实测：preload 静默失败），而且它们还会把 environ 原样交给自己的子进程 —— 留着 bionic
    的 preload，孙子里的 glibc 程序就直接死了。所以这类目标不挂围栏、也不给它带我们的清单。 */
static bool in_system_namespace(const char *path) {
    if (path == NULL) return false;
    return strncmp(path, "/system/", 8) == 0 || strncmp(path, "/apex/", 6) == 0 ||
           strncmp(path, "/vendor/", 8) == 0 || strncmp(path, "/product/", 9) == 0;
}

/**
 * exec 之前把环境修一遍（第 66 轮）。三种方向：
 *
 *  - **bionic 子进程**：确保 LD_PRELOAD 里有围栏 shim —— 用户 `env -u LD_PRELOAD cmd` 只能摘掉
 *    那一层 exec 的继承，shim（在**父进程**里已经加载）会在 exec 前把它重新写回去；同时把围栏
 *    变量按当前进程内存里的真相重写（模式 / 白名单 / tmp 映射 / 硬链接替身 / 哨兵）。
 *  - **非 bionic（glibc / musl）子进程**：把 bionic 的 preload 与围栏变量全部删掉 —— glibc 的
 *    动态链接器装不了 bionic 库，留着就是真机实测的两句报错：
 *    `error while loading shared libraries: liblog.so: cannot open shared object file` 与
 *    `Could not find a PHDR: broken executable?`。这也是 glibc-runner / grun 与一切 glibc
 *    程序在带围栏的环境里跑不动的根因（officecli 那一轮就是这么卡的）。
 *
 * @return 新的 envp（static 缓冲，exec 成功后进程映像整体换掉，无所谓泄漏），NULL = 原样传
 */
static char **fixup_exec_env(const char *path, char *const envp[], bool *foreign_out) {
    if (envp == NULL) return NULL;
    bool foreign = is_foreign_executable(path);
    if (foreign_out != NULL) *foreign_out = foreign;
    size_t n = 0;
    while (envp[n] != NULL) n++;
    if (n > 4096) return NULL;
    const char *cur_preload = NULL;
    bool have_mode = false, have_roots = false, have_tmp = false, have_mark = false, have_link = false;
    for (size_t i = 0; i < n; i++) {
        if (strncmp(envp[i], "LD_PRELOAD=", 11) == 0) cur_preload = envp[i] + 11;
        else if (strncmp(envp[i], "ADSH_FENCE_MODE=", 16) == 0) have_mode = true;
        else if (strncmp(envp[i], "ADSH_FENCE_ROOTS=", 17) == 0) have_roots = true;
        else if (strncmp(envp[i], "ADSH_TMP_REDIRECT=", 18) == 0) have_tmp = true;
        else if (strncmp(envp[i], "ADSH_FENCE_MARK=", 16) == 0) have_mark = true;
        else if (strncmp(envp[i], "ADSH_LINK_EMULATE=", 18) == 0) have_link = true;
    }

    bool system_target = in_system_namespace(path);
    static char s_preload[PATH_MAX * 3];
    s_preload[0] = '\0';
    bool rewrite_preload = false;
    if (foreign) {
        rewrite_preload = preload_has_bionic(cur_preload);
        if (!rewrite_preload && !have_mode && !have_roots && !have_tmp && !have_mark && !have_link) return NULL;
        keep_glibc_preload(cur_preload, s_preload, sizeof(s_preload));
    } else if (system_target) {
        /* 系统命名空间：挂不上围栏，但也别把我们的库传下去（会漏给它的子进程） */
        rewrite_preload = preload_has_ours(cur_preload);
        if (!rewrite_preload) return NULL;
        drop_ours_preload(cur_preload, s_preload, sizeof(s_preload));
    } else {
        /* bionic：**总是**重写 —— 把围栏清单与围栏变量按当前进程内存里的真相写齐。
           用户 `env -u LD_PRELOAD` 只是把继承的变量抹掉，这里补回来（第 66 轮）。 */
        rewrite_preload = true;
        const char *base = g_preload[0] != '\0' ? g_preload : g_self;
        snprintf(s_preload, sizeof(s_preload), "%s", base);
        if (cur_preload != NULL && cur_preload[0] != '\0' && strstr(cur_preload, "libadshfence.so") == NULL) {
            strncat(s_preload, " ", sizeof(s_preload) - strlen(s_preload) - 1);
            strncat(s_preload, cur_preload, sizeof(s_preload) - strlen(s_preload) - 1);
        }
    }

    {
        /* 诊断（ADSH_SHIM_DIAG）：这一行是「子进程环境到底被改成什么样」的唯一现场记录 */
        char d[1024];
        snprintf(d, sizeof(d),
                 "[fixup] pid=%d path=%s foreign=%d cur=%s g_preload=%s g_self=%s -> %s\n",
                 (int)getpid(), path, (int)foreign, cur_preload != NULL ? cur_preload : "(none)",
                 g_preload[0] != '\0' ? g_preload : "(empty)", g_self[0] != '\0' ? g_self : "(empty)",
                 s_preload[0] != '\0' ? s_preload : "(empty)");
        (void)0;
        diag(d);
    }

    static char s_mode[96], s_roots[PATH_MAX * 2 + 32], s_tmp[PATH_MAX + 32], s_mark[PATH_MAX + 32];
    /* 注意：s_preload 里存的是**值**，写进环境时要带 "LD_PRELOAD=" 前缀 —— 第 66 轮第一版
       漏了这个前缀，子进程环境里多了一条没有 key 的垃圾项、真 preload 反而没了（真机实测：
       child-lp 为空、maps 里没有 shim，围栏对一切子进程失效）。 */
    static char s_preload_entry[PATH_MAX * 3 + 16];
    snprintf(s_preload_entry, sizeof(s_preload_entry), "LD_PRELOAD=%s", s_preload);
    char roots_val[PATH_MAX * 2];
    roots_val[0] = '\0';
    for (size_t i = 0; i < g_root_count; i++) {
        if (i > 0) strncat(roots_val, ":", sizeof(roots_val) - strlen(roots_val) - 1);
        strncat(roots_val, g_roots[i], sizeof(roots_val) - strlen(roots_val) - 1);
    }
    snprintf(s_mode, sizeof(s_mode), "ADSH_FENCE_MODE=%s", g_mode_given ? g_mode : "danger-full-access");
    snprintf(s_roots, sizeof(s_roots), "ADSH_FENCE_ROOTS=%s", roots_val);
    snprintf(s_tmp, sizeof(s_tmp), "ADSH_TMP_REDIRECT=%s", g_tmp);
    snprintf(s_mark, sizeof(s_mark), "ADSH_FENCE_MARK=%s", g_mark);

    char **out = (char **)malloc((n + 8) * sizeof(char *));
    if (out == NULL) return NULL;
    size_t k = 0;
    for (size_t i = 0; i < n; i++) {
        const char *e = envp[i];
        if (strncmp(e, "LD_PRELOAD=", 11) == 0) {
            /* 统一在循环之后按 rewrite_preload 决定：这里只跳过旧的 */
            if (!rewrite_preload) out[k++] = envp[i];
            continue;
        }
        if (strncmp(e, "ADSH_FENCE_MODE=", 16) == 0 || strncmp(e, "ADSH_FENCE_ROOTS=", 17) == 0 ||
            strncmp(e, "ADSH_TMP_REDIRECT=", 18) == 0 || strncmp(e, "ADSH_FENCE_MARK=", 16) == 0 ||
            strncmp(e, "ADSH_LINK_EMULATE=", 18) == 0) {
            continue;   /* 由下面按内存里的真相重写；外部 libc 则一条都不写 */
        }
        out[k++] = envp[i];
    }
    if (!foreign && !system_target) {
        if (rewrite_preload && s_preload[0] != '\0') out[k++] = s_preload_entry;
        out[k++] = s_mode;
        out[k++] = s_roots;
        if (g_tmp[0] != '\0') out[k++] = s_tmp;
        if (g_mark[0] != '\0') out[k++] = s_mark;
        if (!g_link_emulate) out[k++] = "ADSH_LINK_EMULATE=0";
    }
    out[k] = NULL;
    return out;
}

/* ------------------------------------------------------------------ exec */

/* ---------------------------------------------------- shebang 自愈（第 67 轮）

   背景（真机报告 + 实机核对）：App 的 targetSdk 是 37，安卓 10 起「targetSdk ≥ 29 的应用不能
   exec 自己 home 目录里的文件」（AOSP sepolicy 的 execve 限制，落在 SELinux 的
   app_data_file:execute 上）。termux-exec 的 `/system/bin/linker64 <path>` 改写只救得了 ELF：
   内核处理 `#!` 的第一步是 exec **脚本自己**，脚本在 app 私有目录里就直接 EACCES，解释器根本
   轮不上。于是**后来装出来**的脚本全军覆没：npm / npx、pip 的 console script、
   node_modules/.bin、./configure、dpkg 的 postinst/prerm —— 而 bootstrap 自带的那 79 个脚本
   没事，因为它们在 nativeLibraryDir（apk_data_file，可执行）里、由符号链接农场指过去。

   自愈做法与内核语义一致、且**只在被拒绝时**才走：脚本 exec 返回 EACCES/EPERM 时，自己解析
   shebang 并按内核的 argv 规则直接 exec 解释器：

       argv = [解释器, (可选参数), 脚本路径, 原 argv[1..]]

   第一步（exec 脚本）被跳过，解释器照常能跑（bootstrap 的 ELF 在 nativeLibraryDir；apt 装出来
   的 ELF 在 app 私有目录，由 termux-exec 改写 成 linker64）。等价于用户手写 `sh script.sh`
   —— 那是报告里实测可用的替代路径，只是这里由 shim 透明地做掉。

   边界（都是为了不改动既有语义）：
    - 只在 EACCES/EPERM 上兜底：ENOENT/ENOEXEC… 原样返回，调用方（shell 的「ENOEXEC 就自己
      解释」、脚本探测）行为不变；
    - 脚本必须真有可执行位（stat 检查）：否则 `./x.sh` 仍应报 EACCES，chmod 的语义不能被绕过；
    - 模拟存储（/storage、/sdcard、/mnt、/media）不兜底：那是 noexec 挂载，提示词与文档写明
      「工作区执行不了脚本，重活去 \$ADSH_SCRATCH」，不能偷偷绕过去；
    - 只认 `#!` 开头；读不到内容、解释器为空、参数超长都放弃；
    - 递归上限 4（内核 BINPRM_MAX_RECURSION 同值）：解释器本身还是脚本时继续兜底，超过就放弃。 */

/** 是不是安卓的模拟存储（noexec 挂载，文档里写明的限制，自愈不碰） */
static bool in_emulated_storage(const char *path) {
    if (path == NULL) return false;
    return strncmp(path, "/storage/", 9) == 0 || strncmp(path, "/sdcard", 7) == 0 ||
           strncmp(path, "/mnt/", 5) == 0 || strncmp(path, "/media/", 7) == 0;
}

/** 解析 `#!` 头：成功返回 true。解释器与「可选参数」（内核只传一个）分别写出。 */
static bool parse_shebang(const char *path, char *interp, size_t isize, char *arg, size_t asize) {
    char buf[512];   /* 内核读的也是开头一屏（BINPRM_BUF_SIZE = 256） */
    ssize_t n = raw_read_at(path, buf, sizeof(buf));
    if (n < 3 || buf[0] != '#' || buf[1] != '!') return false;
    size_t i = 2;
    while (i < (size_t)n && (buf[i] == ' ' || buf[i] == '\t')) i++;
    size_t s = i;
    while (i < (size_t)n && buf[i] != ' ' && buf[i] != '\t' && buf[i] != '\n' && buf[i] != '\r') i++;
    if (i == s || (i - s) >= isize) return false;   /* `#!` 后面是空的：内核也返回 ENOEXEC */
    memcpy(interp, buf + s, i - s);
    interp[i - s] = '\0';
    while (i < (size_t)n && (buf[i] == ' ' || buf[i] == '\t')) i++;
    size_t a = i;
    while (i < (size_t)n && buf[i] != '\n') i++;
    size_t e = i;
    while (e > a && (buf[e - 1] == ' ' || buf[e - 1] == '\t' || buf[e - 1] == '\r')) e--;
    if ((e - a) >= asize) return false;
    memcpy(arg, buf + a, e - a);
    arg[e - a] = '\0';
    return true;
}

/** 文件真有可执行位吗（原始 stat，不走自己的拦截）—— 自愈的前置条件 */
static bool exec_bit_set(const char *path) {
    struct stat st;
    if (syscall(SYS_newfstatat, AT_FDCWD, path, &st, 0) != 0) return false;
    return S_ISREG(st.st_mode) && (st.st_mode & 0111) != 0;
}

/**
 * 兜底 exec（只在失败路径上调用）：成功时进程映像整个换掉、不会返回；不满足条件或兜底也失败
 * 时原样返回，errno 保持调用方那次的失败原因（「内核直接拒绝」的语义不变）。
 */
static void shebang_retry(const char *path, char *const argv[], char *const envp[], int failed_errno) {
    int keep = errno;
    if (path == NULL || argv == NULL || failed_errno == 0) { errno = keep; return; }
    if (failed_errno != EACCES && failed_errno != EPERM) { errno = keep; return; }
    if (in_emulated_storage(path) || g_shebang_depth >= MAX_SHEBANG_DEPTH) { errno = keep; return; }
    char interp[PATH_MAX], arg[PATH_MAX];
    if (!parse_shebang(path, interp, sizeof(interp), arg, sizeof(arg))) { errno = keep; return; }
    if (!exec_bit_set(path)) { errno = keep; return; }

    size_t argc = 0;
    while (argv[argc] != NULL) argc++;
    if (argc > 4096) { errno = keep; return; }
    bool has_arg = arg[0] != '\0';
    char **na = (char **)malloc((argc + (has_arg ? 3 : 2)) * sizeof(char *));
    if (na == NULL) { errno = keep; return; }
    size_t k = 0;
    na[k++] = interp;                 /* argv[0] 按内核的做法换成解释器 */
    if (has_arg) na[k++] = arg;       /* 内核只认一个参数：`#!interp ARG` */
    na[k++] = (char *)path;           /* argv[1] 是脚本路径 */
    for (size_t i = 1; i < argc; i++) na[k++] = argv[i];
    na[k] = NULL;

    {
        char d[PATH_MAX + 160];
        snprintf(d, sizeof(d), "[shebang] pid=%d script=%s -> interp=%s arg=%s\n",
                 (int)getpid(), path, interp, has_arg ? arg : "(none)");
        diag(d);
    }

    g_shebang_depth++;
    execve(interp, na, envp);         /* 走完整链路：/tmp 映射 + 环境修补 + termux-exec 接力 */
    g_shebang_depth--;
    errno = keep;                     /* 兜底也失败：把原始错误还给调用方 */
}

/** exec 链路的最后一棒：外部 libc 走原始系统调用，其余交给下一个 preload 库（termux-exec）。 */
static int exec_chain(const char *path, char *const argv[], char *const use[], bool foreign) {
    if (foreign) {
        /* glibc / musl 的程序**必须**由 ELF 自己声明的解释器（$PREFIX/glibc/lib/ld-linux-*.so）
           来跑；下一棒的 termux-exec 会把 app 私有目录里的可执行文件改写成
           `/system/bin/linker64 <path>`，那是 bionic 的连接器 —— glibc 程序被它跑起来就是
           `libcap.so.2 not found` / `CANNOT LINK EXECUTABLE`（真机实测）。所以这里直接走原始
           系统调用，让内核按 ELF 头里的解释器去加载（App 私有目录里的 glibc 程序实测可以这样跑）。 */
        return (int)syscall(SYS_execve, path, argv, use);
    }
    if (g_execve != NULL) return g_execve(path, argv, use);
    return (int)syscall(SYS_execve, path, argv, use);
}

/**
 * execve：先做 /tmp 映射，再按目标是不是 bionic 修一遍环境（见 fixup_exec_env），然后把链路交给
 * 下一棒 termux-exec（app 私有目录里 ELF 的 linker64 改写）；**脚本被内核拒绝时**最后走
 * shebang 自愈（见上面的长注释）。
 */
int execve(const char *path, char *const argv[], char *const envp[]) {
    REDIRECT(path)
    ensure_init();
    bool foreign = false;
    char **fixed = fixup_exec_env(path, envp, &foreign);
    char *const *use = fixed != NULL ? (char *const *)fixed : envp;
    if (g_shebang_force) {
        /* 自测：只对脚本假装内核拒绝了这次 exec（真机上就是 app 私有目录 + targetSdk 37 的
           EACCES）。ELF 目标不受影响，否则旋钮会把它下面整条 exec 链一起掐死。 */
        char i0[PATH_MAX], a0[PATH_MAX];
        if (!in_emulated_storage(path) && parse_shebang(path, i0, sizeof(i0), a0, sizeof(a0))) {
            shebang_retry(path, argv, use, EACCES);
            errno = EACCES;
            return -1;
        }
    }
    int rc = exec_chain(path, argv, use, foreign);
    int failed = errno;
    shebang_retry(path, argv, use, failed);
    errno = failed;
    return rc;
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
