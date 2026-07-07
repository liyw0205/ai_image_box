package com.aiimagebox

import android.app.Application
import com.aiimagebox.data.AppDirectories

class AIImageBoxApp : Application() {
    lateinit var appDirectories: AppDirectories
        private set

    override fun onCreate() {
        super.onCreate()
        appDirectories = AppDirectories.ensure(filesDir)
    }
}

