package com.SkrinVex.OfoxMessenger

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.SkrinVex.OfoxMessenger.utils.CrashHandler
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseException
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.database
import com.google.firebase.database.ktx.database
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

object PermissionState {
    var needsNotificationPermission = false
}

object ImageCacheConfig {
    // Основные параметры кэша
    var memoryCachePercent: Double = 0.25
    var diskCacheSize: Long = 512L * 1024 * 1024
    var enableMemory: Boolean = true
    var enableDisk: Boolean = true

    // Детальные настройки кэширования
    var cacheMessages: Boolean = false
    var cacheGroupMessages: Boolean = false
    var cacheNotifications: Boolean = true
    var cacheMedia: Boolean = true
    var cacheAvatars: Boolean = true
    var cachePosts: Boolean = false

    // Размеры для разных типов
    var messagesCacheSize: Long = 128L * 1024 * 1024
    var groupCacheSize: Long = 128L * 1024 * 1024
    var notificationsCacheSize: Long = 64L * 1024 * 1024
    var mediaCacheSize: Long = 512L * 1024 * 1024
    var avatarsCacheSize: Long = 128L * 1024 * 1024
    var postsCacheSize: Long = 256L * 1024 * 1024
}

object DbConfig {
    var useDev = true
}

class App : Application(), Application.ActivityLifecycleCallbacks {

    lateinit var imageLoader: ImageLoader
        private set

    private val activityCount = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)

        // Загрузить настройки кэша из SharedPreferences
        CacheManager.loadSettings(this)

        FirebaseApp.initializeApp(this)

        try {
            val db = FirebaseDatabase.getInstance()
            db.setPersistenceEnabled(true)

            // Динамический размер кэша базы данных
            val dbCacheSize = calculateDbCacheSize()
            db.setPersistenceCacheSizeBytes(dbCacheSize)

            // Синхронизация только для включенных типов данных
            if (ImageCacheConfig.cacheMessages) {
                db.getReference("chats").keepSynced(true)
                db.getReference("messages").keepSynced(true)
            }
            if (ImageCacheConfig.cacheGroupMessages) {
                db.getReference("group_chats").keepSynced(true)
                db.getReference("group_messages").keepSynced(true)
                db.getReference("user_groups").keepSynced(true)
            }
            if (ImageCacheConfig.cacheNotifications) {
                db.getReference("notifications").keepSynced(true)
                db.getReference("friend_requests").keepSynced(true)
            }
            if (ImageCacheConfig.cachePosts) {
                db.getReference("posts").keepSynced(true)
                db.getReference("news").keepSynced(true)
            }

            // Всегда синхронизируем пользователей и настройки
            db.getReference("users").keepSynced(true)
            db.getReference("user_settings").keepSynced(true)

            if (ImageCacheConfig.cacheMedia) {
                db.getReference("media").keepSynced(true)
            }
        } catch (e: DatabaseException) {
            // игнорируем
        }

        initImageLoader()
        checkNotificationPermission()
        registerActivityLifecycleCallbacks(this)
    }

    private fun calculateDbCacheSize(): Long {
        var total = 50L * 1024 * 1024 // базовый размер 50MB

        if (ImageCacheConfig.cacheMessages) total += 30L * 1024 * 1024
        if (ImageCacheConfig.cacheGroupMessages) total += 30L * 1024 * 1024
        if (ImageCacheConfig.cacheNotifications) total += 20L * 1024 * 1024
        if (ImageCacheConfig.cachePosts) total += 40L * 1024 * 1024
        if (ImageCacheConfig.cacheMedia) total += 80L * 1024 * 1024

        return total
    }

    private fun updateLastActivity() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance()
            .getReference("users/$uid/lastActivity")
            .setValue(System.currentTimeMillis())
    }

    override fun onActivityResumed(activity: Activity) {
        updateLastActivity()
    }

    fun initImageLoader() {
        val customLoader = ImageLoader.Builder(this)
            .memoryCache {
                if (ImageCacheConfig.enableMemory) {
                    MemoryCache.Builder(this)
                        .maxSizePercent(ImageCacheConfig.memoryCachePercent)
                        .build()
                } else {
                    MemoryCache.Builder(this).maxSizeBytes(0).build()
                }
            }
            .diskCache {
                if (ImageCacheConfig.enableDisk) {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizeBytes(ImageCacheConfig.diskCacheSize)
                        .build()
                } else null
            }
            .components {
                add(SvgDecoder.Factory())
            }
            .respectCacheHeaders(false)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .networkCachePolicy(coil.request.CachePolicy.ENABLED)
            .crossfade(true)
            .build()

        imageLoader = customLoader
        coil.Coil.setImageLoader(customLoader)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            PermissionState.needsNotificationPermission = !granted
        }
    }

    fun clearImageCache() {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }

    fun clearSpecificCache(type: String) {
        when (type) {
            "messages" -> clearCacheDirectory("messages_cache")
            "groups" -> clearCacheDirectory("group_cache")
            "notifications" -> clearCacheDirectory("notification_cache")
            "media" -> clearCacheDirectory("media_cache")
            "avatars" -> clearCacheDirectory("avatar_cache")
            "posts" -> clearCacheDirectory("posts_cache")
        }
    }

    private fun clearCacheDirectory(dirName: String) {
        val dir = cacheDir.resolve(dirName)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    override fun onActivityStarted(activity: Activity) {
        val count = activityCount.incrementAndGet()
        if (count == 1) {
            setUserOnline(true)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val count = activityCount.decrementAndGet()
        if (count == 0) {
            setUserOnline(false)
        }
    }

    private fun setUserOnline(isOnline: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = Firebase.database.getReference("users/$uid/online")
        ref.setValue(isOnline)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}