package com.adsh.app

import android.app.Application
import android.content.ComponentName
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 硬约束 24（`docs/ARCHITECTURE.md`）：`MainActivity` 只能有一份实例 —— 会话宿主 `ChatViewModel` 是
 * Activity 作用域的，`standard` 会让桌面图标 / `NEW_TASK` 启动再开一份宿主：新实例里 `sending=false`
 * （发送键变灰）、正文停在开实例那一刻，而 agent 那一轮还在旧实例里跑。
 *
 * `application = Application::class`：`AdshApp.onCreate` 会 loadLibrary(quickjs-android-wrapper)，宿主 JVM 上没有这个 .so。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MainActivityLaunchModeTest {

    @Test
    fun mainActivityIsSingleTask() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
    }
}
