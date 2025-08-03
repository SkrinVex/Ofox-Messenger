package com.SkrinVex.OfoxMessenger.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.SkrinVex.OfoxMessenger.ui.common.CrashActivity
import kotlin.system.exitProcess

object CrashHandler : Thread.UncaughtExceptionHandler {
    private lateinit var defaultHandler: Thread.UncaughtExceptionHandler
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler() ?: this
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val pm = appContext.packageManager
            val packageInfo = pm.getPackageInfo(appContext.packageName, 0)
            val appName = appContext.applicationInfo.loadLabel(pm).toString()
            val version = packageInfo.versionName
            val build = packageInfo.longVersionCode

            // Строка с данными приложения
            val appInfo = """
                Приложение: $appName
                Версия: $version
                Билд: $build
            """.trimIndent()

            // Шифруем в base64
            val encryptedInfo = Base64.encodeToString(appInfo.toByteArray(), Base64.NO_WRAP)

            // Финальный лог
            val crashLog = "${Log.getStackTraceString(throwable)}\n---APP_INFO---$encryptedInfo"

            // Передаём видимый стек и полный лог
            val intent = Intent(appContext, CrashActivity::class.java).apply {
                putExtra("error", Log.getStackTraceString(throwable)) // пользователь видит это
                putExtra("full_log", crashLog) // это копируется и отправляется
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                appContext.startActivity(intent)
            } else {
                Looper.prepare()
                appContext.startActivity(intent)
                Looper.loop()
            }

            Thread.sleep(1000)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(1)
        }
    }
}