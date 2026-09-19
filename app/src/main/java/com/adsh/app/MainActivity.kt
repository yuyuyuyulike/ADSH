package com.adsh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adsh.app.ui.AppRoot
import com.adsh.app.ui.ChatViewModel
import com.adsh.app.ui.theme.AdshTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 视图模型在 Activity 作用域里只建一次：主题偏好与 AppRoot 共用同一个实例
            val chatViewModel = viewModel<ChatViewModel>()
            val state by chatViewModel.state.collectAsStateWithLifecycle()
            AdshTheme(preference = state.themePreference) {
                AppRoot(viewModel = chatViewModel)
            }
        }
    }
}
