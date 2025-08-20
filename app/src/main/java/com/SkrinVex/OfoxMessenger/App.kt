package com.SkrinVex.OfoxMessenger

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.SkrinVex.OfoxMessenger.utils.CrashHandler
import com.google.firebase.FirebaseApp

object PermissionState {
    var needsNotificationPermission = false
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        FirebaseApp.initializeApp(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            PermissionState.needsNotificationPermission = !granted
        }
    }
}