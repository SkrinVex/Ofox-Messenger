package com.SkrinVex.OfoxMessenger

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.SkrinVex.OfoxMessenger.utils.CrashHandler
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DatabaseException
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONObject
import java.io.InputStream

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

class App : Application() {

    lateinit var imageLoader: ImageLoader
        private set

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
}