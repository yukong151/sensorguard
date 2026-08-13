package com.sensorguard.app

import android.app.Application
import com.sensorguard.app.jni.SgNative

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SgNative.init()
    }
}
