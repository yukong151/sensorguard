package com.yuexiao12.sensorguard

import android.app.Application
import com.yuexiao12.sensorguard.jni.SgNative

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SgNative.init()
    }
}
