package com.SkrinVex.OfoxMessenger

import android.content.Context
import android.content.SharedPreferences

object CacheManager {
    private const val PREFS_NAME = "cache_settings"

    // Ключи для SharedPreferences
    private const val KEY_MEMORY_ENABLED = "memory_enabled"
    private const val KEY_DISK_ENABLED = "disk_enabled"
    private const val KEY_MEMORY_PERCENT = "memory_percent"
    private const val KEY_DISK_SIZE = "disk_size"

    private const val KEY_CACHE_MESSAGES = "cache_messages"
    private const val KEY_CACHE_GROUPS = "cache_groups"
    private const val KEY_CACHE_NOTIFICATIONS = "cache_notifications"
    private const val KEY_CACHE_MEDIA = "cache_media"
    private const val KEY_CACHE_AVATARS = "cache_avatars"
    private const val KEY_CACHE_POSTS = "cache_posts"

    private const val KEY_MESSAGES_SIZE = "messages_size"
    private const val KEY_GROUPS_SIZE = "groups_size"
    private const val KEY_NOTIFICATIONS_SIZE = "notifications_size"
    private const val KEY_MEDIA_SIZE = "media_size"
    private const val KEY_AVATARS_SIZE = "avatars_size"
    private const val KEY_POSTS_SIZE = "posts_size"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveSettings(context: Context) {
        val prefs = getPrefs(context)
        with(prefs.edit()) {
            // Основные настройки
            putBoolean(KEY_MEMORY_ENABLED, ImageCacheConfig.enableMemory)
            putBoolean(KEY_DISK_ENABLED, ImageCacheConfig.enableDisk)
            putFloat(KEY_MEMORY_PERCENT, ImageCacheConfig.memoryCachePercent.toFloat())
            putLong(KEY_DISK_SIZE, ImageCacheConfig.diskCacheSize)

            // Детальные настройки
            putBoolean(KEY_CACHE_MESSAGES, ImageCacheConfig.cacheMessages)
            putBoolean(KEY_CACHE_GROUPS, ImageCacheConfig.cacheGroupMessages)
            putBoolean(KEY_CACHE_NOTIFICATIONS, ImageCacheConfig.cacheNotifications)
            putBoolean(KEY_CACHE_MEDIA, ImageCacheConfig.cacheMedia)
            putBoolean(KEY_CACHE_AVATARS, ImageCacheConfig.cacheAvatars)
            putBoolean(KEY_CACHE_POSTS, ImageCacheConfig.cachePosts)

            // Размеры
            putLong(KEY_MESSAGES_SIZE, ImageCacheConfig.messagesCacheSize)
            putLong(KEY_GROUPS_SIZE, ImageCacheConfig.groupCacheSize)
            putLong(KEY_NOTIFICATIONS_SIZE, ImageCacheConfig.notificationsCacheSize)
            putLong(KEY_MEDIA_SIZE, ImageCacheConfig.mediaCacheSize)
            putLong(KEY_AVATARS_SIZE, ImageCacheConfig.avatarsCacheSize)
            putLong(KEY_POSTS_SIZE, ImageCacheConfig.postsCacheSize)

            apply()
        }
    }

    fun loadSettings(context: Context) {
        val prefs = getPrefs(context)

        // Загрузка основных настроек
        ImageCacheConfig.enableMemory = prefs.getBoolean(KEY_MEMORY_ENABLED, true)
        ImageCacheConfig.enableDisk = prefs.getBoolean(KEY_DISK_ENABLED, true)
        ImageCacheConfig.memoryCachePercent = prefs.getFloat(KEY_MEMORY_PERCENT, 0.25f).toDouble()
        ImageCacheConfig.diskCacheSize = prefs.getLong(KEY_DISK_SIZE, 512L * 1024 * 1024)

        // Загрузка детальных настроек
        ImageCacheConfig.cacheMessages = prefs.getBoolean(KEY_CACHE_MESSAGES, true)
        ImageCacheConfig.cacheGroupMessages = prefs.getBoolean(KEY_CACHE_GROUPS, true)
        ImageCacheConfig.cacheNotifications = prefs.getBoolean(KEY_CACHE_NOTIFICATIONS, true)
        ImageCacheConfig.cacheMedia = prefs.getBoolean(KEY_CACHE_MEDIA, true)
        ImageCacheConfig.cacheAvatars = prefs.getBoolean(KEY_CACHE_AVATARS, true)
        ImageCacheConfig.cachePosts = prefs.getBoolean(KEY_CACHE_POSTS, true)

        // Загрузка размеров
        ImageCacheConfig.messagesCacheSize = prefs.getLong(KEY_MESSAGES_SIZE, 128L * 1024 * 1024)
        ImageCacheConfig.groupCacheSize = prefs.getLong(KEY_GROUPS_SIZE, 128L * 1024 * 1024)
        ImageCacheConfig.notificationsCacheSize = prefs.getLong(KEY_NOTIFICATIONS_SIZE, 64L * 1024 * 1024)
        ImageCacheConfig.mediaCacheSize = prefs.getLong(KEY_MEDIA_SIZE, 512L * 1024 * 1024)
        ImageCacheConfig.avatarsCacheSize = prefs.getLong(KEY_AVATARS_SIZE, 128L * 1024 * 1024)
        ImageCacheConfig.postsCacheSize = prefs.getLong(KEY_POSTS_SIZE, 256L * 1024 * 1024)
    }

    fun resetToDefaults(context: Context) {
        ImageCacheConfig.enableMemory = true
        ImageCacheConfig.enableDisk = true
        ImageCacheConfig.memoryCachePercent = 0.25
        ImageCacheConfig.diskCacheSize = 512L * 1024 * 1024

        ImageCacheConfig.cacheMessages = true
        ImageCacheConfig.cacheGroupMessages = true
        ImageCacheConfig.cacheNotifications = true
        ImageCacheConfig.cacheMedia = true
        ImageCacheConfig.cacheAvatars = true
        ImageCacheConfig.cachePosts = true

        ImageCacheConfig.messagesCacheSize = 128L * 1024 * 1024
        ImageCacheConfig.groupCacheSize = 128L * 1024 * 1024
        ImageCacheConfig.notificationsCacheSize = 64L * 1024 * 1024
        ImageCacheConfig.mediaCacheSize = 512L * 1024 * 1024
        ImageCacheConfig.avatarsCacheSize = 128L * 1024 * 1024
        ImageCacheConfig.postsCacheSize = 256L * 1024 * 1024

        saveSettings(context)
    }

    fun getTotalCacheSize(): Long {
        var total = 0L
        if (ImageCacheConfig.cacheMessages) total += ImageCacheConfig.messagesCacheSize
        if (ImageCacheConfig.cacheGroupMessages) total += ImageCacheConfig.groupCacheSize
        if (ImageCacheConfig.cacheNotifications) total += ImageCacheConfig.notificationsCacheSize
        if (ImageCacheConfig.cacheMedia) total += ImageCacheConfig.mediaCacheSize
        if (ImageCacheConfig.cacheAvatars) total += ImageCacheConfig.avatarsCacheSize
        if (ImageCacheConfig.cachePosts) total += ImageCacheConfig.postsCacheSize
        return total
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} МБ"
            else -> String.format("%.1f ГБ", bytes.toDouble() / (1024 * 1024 * 1024))
        }
    }
}