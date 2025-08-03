package com.SkrinVex.OfoxMessenger
import android.app.Application
import com.SkrinVex.OfoxMessenger.utils.CrashHandler
import com.google.firebase.FirebaseApp

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        FirebaseApp.initializeApp(this)
    }
}
