package com.psave.tubesave

import android.app.Application

class TubeSaveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateCheckWorker.schedule(this)
    }
}
