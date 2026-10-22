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

# 3) QuickJS 的 Java 包装层（native 侧反向回调这些类，按名字查方法）
-keep class com.whl.quickjs.wrapper.** { *; }
-keep class com.whl.quickjs.android.** { *; }

# 4) 注解与内部类属性：kotlinx.serialization / Room 生成的代码依赖它们
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, AnnotationDefault
