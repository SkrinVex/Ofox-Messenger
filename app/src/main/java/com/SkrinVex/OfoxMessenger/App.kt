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
    var memoryCachePercent: Double = 0.25
    var diskCacheSize: Long = 250L * 1024 * 1024
    var enableDisk: Boolean = true
    var enableMemory: Boolean = true
}

/**
 * true = dev (google-dev.json), false = prod (google-services.json)
 */
object DbConfig {
    var useDev = true
}

class App : Application(), Application.ActivityLifecycleCallbacks {

    lateinit var imageLoader: ImageLoader
        private set

    // счётчик активных Activity
    private val activityCount = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)

        // --- Firebase init ---
        FirebaseApp.initializeApp(this)

        // --- Firebase Database ---
        try {
            val db = FirebaseDatabase.getInstance()
            db.setPersistenceEnabled(true)
            db.setPersistenceCacheSizeBytes(50L * 1024 * 1024)

            db.getReference("users").keepSynced(true)
            db.getReference("posts").keepSynced(true)
            db.getReference("chats").keepSynced(true)
            db.getReference("news").keepSynced(true)
        } catch (e: DatabaseException) {
            // если кто-то обратился раньше — игнорируем
        }

        initImageLoader()
        checkNotificationPermission()

        // регистрируем коллбэки жизненного цикла Activity
        registerActivityLifecycleCallbacks(this)
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

    private fun initImageLoader() {
        imageLoader = ImageLoader.Builder(this)
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
            .crossfade(true)
            .build()
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

    // ------------------------------
    // ActivityLifecycleCallbacks
    // ------------------------------

    override fun onActivityStarted(activity: Activity) {
        val count = activityCount.incrementAndGet()
        if (count == 1) {
            // приложение стало видимым — ставим ONLINE
            setUserOnline(true)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val count = activityCount.decrementAndGet()
        if (count == 0) {
            // приложение полностью скрыто — ставим OFFLINE
            setUserOnline(false)
        }
    }

    private fun setUserOnline(isOnline: Boolean) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = Firebase.database.getReference("users/$uid/online")
        ref.setValue(isOnline)
    }

    // Остальные коллбэки можно оставить пустыми
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}