package com.adsh.app

import android.app.Application
import com.whl.quickjs.android.QuickJSLoader

class AdshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // QuickJS 原生库只需初始化一次
        QuickJSLoader.init()
    }
}
