package com.SkrinVex.OfoxMessenger

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.SkrinVex.OfoxMessenger.utils.CrashHandler
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DatabaseException
import com.google.firebase.database.FirebaseDatabase

object PermissionState {
    var needsNotificationPermission = false
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        FirebaseApp.initializeApp(this)

        // ✅ Включаем оффлайн-кэш для всей базы
        try {
            val db = FirebaseDatabase.getInstance()
            db.setPersistenceEnabled(true)                     // оффлайн доступ
            db.setPersistenceCacheSizeBytes(50L * 1024 * 1024) // увеличиваем лимит кэша до 50MB

            // ✅ Эти ветки всегда будут синхронизированы и мгновенно доступны из локального кэша
            db.getReference("users").keepSynced(true)
            db.getReference("posts").keepSynced(true)
            db.getReference("chats").keepSynced(true)
            db.getReference("news").keepSynced(true)

        } catch (e: DatabaseException) {
            // Если кто-то обратился к БД раньше, чем инициализация — просто игнорируем
        }

        // ✅ Проверка разрешения на уведомления (как у тебя было)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            PermissionState.needsNotificationPermission = !granted
        }
    }
}