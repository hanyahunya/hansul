package com.hansul.app

import android.app.Application
import com.hansul.app.data.AppContainer

class HansulApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.NONE) {
        AppContainer(applicationContext)
    }
}
