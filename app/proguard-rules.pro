# ADSH 的 R8 规则（release 打开 isMinifyEnabled / isShrinkResources 后生效）
#
# 依赖自带的 consumer 规则已经覆盖了 Room、kotlinx.serialization、Compose、AndroidX，
# 这里只补 R8 静态看不出来的入口：JNI（native 方法按名字解析）和少量反射。

# 1) JNI 通用规则：带 native 方法的类不能改名，否则 C 侧 RegisterNatives 找不到
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 2) 我们自己的 PTY 桥（libadshpty.so 里按类名/方法名 JNI 调用）
-keep class com.adsh.app.runtime.termux.Pty { *; }
# 第 69 轮删掉的一条（死规则）：
#   -keep class com.adsh.app.runtime.termux.PtyProcess { *; } —— 全仓库（连 cpp 的 JNI 符号
#   一起 grep）都没有 PtyProcess：会话类是 PtySession，JNI 只认 runtime.termux.Pty
#   （见 pty_bridge.c 的 Java_com_adsh_app_runtime_termux_Pty_* 符号），这条 keep 一直在空转。

# 3) QuickJS 的 Java 包装层（native 侧反向回调这些类，按名字查方法）
-keep class com.whl.quickjs.wrapper.** { *; }
-keep class com.whl.quickjs.android.** { *; }

# 4) 注解与内部类属性：kotlinx.serialization / Room 生成的代码依赖它们
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, AnnotationDefault

# 第 68 轮删掉的两条（死规则）：
#   -keep class com.termux.terminal.** / com.termux.view.**  —— 第五十四轮就把
#   termux-app:terminal-view 依赖删了，全仓库没有任何 com.termux.* 类，这两条 keep 是空转
#   -dontwarn org.slf4j/bouncycastle/conscrypt/openjsse/javax.annotation —— 试删后 R8 不再报
#   Missing class（这几个包以前是 okhttp / terminal-view 带进来的），release 构建通过
