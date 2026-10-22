// ADSH PTY bridge —— 照抄 Termux terminal-emulator/src/main/jni/termux.c 的做法：
//   open("/dev/ptmx") + grantpt/unlockpt/ptsname_r + fork() + TIOCSCTTY + dup2 + execvp
// 刻意与 Termux 保持一致（不用 forkpty / posix_spawn），便于后续直接对接 terminal-view。
#define _GNU_SOURCE
#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#ifndef IUTF8
#define IUTF8 0x4000
#endif

#define LOG_TAG "ADSH_PTY"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern char **environ;

static char *dup_jstring(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    const char *chars = (*env)->GetStringUTFChars(env, s, NULL);
    if (chars == NULL) return NULL;
    char *copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, s, chars);
    return copy;
}

/**
 * 在 fork **之前**收集「除 0/1/2 之外要关掉的 fd」。
 *
 * 不能在子进程里 opendir/readdir：fork 之后只剩 async-signal-safe 调用可用，而
 * opendir 会 malloc —— JVM 是多线程的，另一个线程可能正握着 malloc 锁，子进程会直接死锁。
 * 列表在父进程里准备好，子进程只做 close()。
 *
 * @return 收集到的 fd 数（上限 max 个；超出部分留给 exec 的 CLOEXEC 语义）
 */
static int collect_extra_fds(int *fds, int max) {
    DIR *dir = opendir("/proc/self/fd");
    if (dir == NULL) return 0;
    int dir_fd = dirfd(dir);
    int count = 0;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        int fd = atoi(entry->d_name);
        if (fd > 2 && fd != dir_fd && count < max) fds[count++] = fd;
    }
    closedir(dir);
    return count;
}

JNIEXPORT jint JNICALL
Java_com_adsh_app_runtime_termux_Pty_createSubprocess(
        JNIEnv *env, jclass clazz,
        jstring cmd, jstring cwd, jobjectArray args, jobjectArray envVars,
        jintArray pidOut, jint rows, jint cols, jint xpix, jint ypix) {

    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) { LOGE("open /dev/ptmx failed: %s", strerror(errno)); return -1; }
    if (grantpt(ptm) != 0) { LOGE("grantpt failed: %s", strerror(errno)); close(ptm); return -1; }
    if (unlockpt(ptm) != 0) { LOGE("unlockpt failed: %s", strerror(errno)); close(ptm); return -1; }

    char devname[128];
    memset(devname, 0, sizeof(devname));
    if (ptsname_r(ptm, devname, sizeof(devname)) != 0) {
        LOGE("ptsname_r failed: %s", strerror(errno));
        close(ptm);
        return -1;
    }

    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;                 // UTF-8 输入
        tios.c_iflag &= ~(IXON | IXOFF);       // 防止 Ctrl+S 卡死
        tcsetattr(ptm, TCSANOW, &tios);
    }

    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short) rows;
    size.ws_col = (unsigned short) cols;
    size.ws_xpixel = (unsigned short) xpix;
    size.ws_ypixel = (unsigned short) ypix;
    ioctl(ptm, TIOCSWINSZ, &size);

    // fork 之前把所有 JNI 数据拷成 C 字符串（fork 后不可再调用 JNI），
    // 顺便把要关的 fd 列表也准备好（子进程里不能再 opendir，见 collect_extra_fds）
    int extra_fds[1024];
    int extra_fd_count = collect_extra_fds(extra_fds, (int)(sizeof(extra_fds) / sizeof(extra_fds[0])));
    char *command = dup_jstring(env, cmd);
    char *workdir = dup_jstring(env, cwd);

    jsize nargs = args != NULL ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = calloc((size_t) nargs + 2, sizeof(char *));
    argv[0] = command;
    for (jsize i = 0; i < nargs; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        argv[i + 1] = dup_jstring(env, item);
        (*env)->DeleteLocalRef(env, item);
    }
    argv[nargs + 1] = NULL;

    jsize nenv = envVars != NULL ? (*env)->GetArrayLength(env, envVars) : 0;
    char **envp = calloc((size_t) nenv + 1, sizeof(char *));
    for (jsize i = 0; i < nenv; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, envVars, i);
        envp[i] = dup_jstring(env, item);
        (*env)->DeleteLocalRef(env, item);
    }
    envp[nenv] = NULL;

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(ptm);
        return -1;
    }

    if (pid == 0) {
        // 子进程：解开 JVM 屏蔽的信号，会话首进程，把 pty slave 接到 0/1/2
        sigset_t set;
        sigfillset(&set);
        sigprocmask(SIG_UNBLOCK, &set, NULL);

        close(ptm);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(127);
        ioctl(pts, TIOCSCTTY, 0);

        dup2(pts, STDIN_FILENO);
        dup2(pts, STDOUT_FILENO);
        dup2(pts, STDERR_FILENO);
        if (pts > 2) close(pts);

        for (int i = 0; i < extra_fd_count; i++) close(extra_fds[i]);

        // 直接挂上父进程准备好的 envp：fork 之后不能调 clearenv/putenv（它们会 malloc）。
        // 真正的 exec 用的是下面的 envp 参数，这里只为 execvp 的 PATH 搜索与 exec 前的库代码兜底。
        environ = envp;
        if (workdir != NULL && chdir(workdir) != 0) {
            static const char no_cwd[] = "adsh-pty: cannot enter the session working directory\n";
            ssize_t ignored = write(STDERR_FILENO, no_cwd, sizeof(no_cwd) - 1);
            (void)ignored;
        }

        // 绝对路径走 execv：execvp 会为了 PATH 搜索调 malloc，fork 后不安全
        if (command != NULL && command[0] == '/') {
            execv(command, argv);
        } else {
            execvp(command, argv);
        }
        /* exec 失败必须说一句：以前是静默 _exit(127)，终端里只剩一片空白，谁也分不清是
           「命令没起来」还是「解释器崩了」。stderr 此刻就是 PTY slave，用户能直接看到。
           fork 之后只能做异步信号安全调用：write 常量串，不碰 stdio / strerror。 */
        {
            static const char exec_failed[] = "adsh-pty: failed to execute the shell\n";
            ssize_t ignored = write(STDERR_FILENO, exec_failed, sizeof(exec_failed) - 1);
            (void)ignored;
        }
        _exit(127);
    }

    if (pidOut != NULL) {
        jint value = (jint) pid;
        (*env)->SetIntArrayRegion(env, pidOut, 0, 1, &value);
    }
    // 父进程只保留 master fd；slave 只在子进程里持有
    return ptm;
}

JNIEXPORT void JNICALL
Java_com_adsh_app_runtime_termux_Pty_setPtyWindowSize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols, jint xpix, jint ypix) {
    (void) env; (void) clazz;
    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short) rows;
    size.ws_col = (unsigned short) cols;
    size.ws_xpixel = (unsigned short) xpix;
    size.ws_ypixel = (unsigned short) ypix;
    // rows/cols 变化时内核会向前台进程组发 SIGWINCH
    if (ioctl(fd, TIOCSWINSZ, &size) != 0) {
        LOGE("TIOCSWINSZ failed: %s", strerror(errno));
    }
}

JNIEXPORT jint JNICALL
Java_com_adsh_app_runtime_termux_Pty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

