package com.tabbit.sensorguard

import android.app.Application
import com.tabbit.sensorguard.jni.SgNative

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SgNative.init()
    }
}
