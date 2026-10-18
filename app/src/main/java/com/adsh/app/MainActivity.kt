package com.adsh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adsh.app.ui.AppRoot
import com.adsh.app.ui.chat.ChatViewModel
import com.adsh.app.ui.blocks.AdshSelectionToolbar
import com.adsh.app.ui.theme.AdshTheme

class MainActivity : ComponentActivity() {

    /** 组合根里的对象全 App 共用；工厂只建一次（每次重组都新建一个是白扔对象） */
    private val viewModelFactory: ChatViewModelFactory by lazy {
        ChatViewModelFactory(application, (application as AdshApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 返回键只退到后台，不退出：会话宿主 ChatViewModel 是 Activity 作用域的，finish() 会走到
        // onCleared → Jobs.cancelAll，把正在跑的那一轮连同它的后台任务一起掐掉（岛就是为「后台继续跑」才存在）。
        // 抽屉 / 浮层 / 菜单的返回键处理登记得比它晚，返回键仍由它们先接管（见 ui/AppRoot.kt、ui/OverlayDismiss.kt）。
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            },
        )
        enableEdgeToEdge()
        setContent {
            // 视图模型在 Activity 作用域里只建一次：主题偏好与 AppRoot 共用同一个实例
            val chatViewModel = viewModel<ChatViewModel>(factory = viewModelFactory)
            val state by chatViewModel.state.collectAsStateWithLifecycle()
            AdshTheme(preference = state.themePreference) {
                // 选中文本的浮动栏统一走这一层：拦掉"没有选中任何内容"的那一排（见 ui/SelectionToolbar.kt）
                AdshSelectionToolbar {
                    AppRoot(viewModel = chatViewModel)
                }
            }
        }
    }
}
