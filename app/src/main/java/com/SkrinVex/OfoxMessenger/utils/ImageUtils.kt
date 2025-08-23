package com.SkrinVex.OfoxMessenger.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageUtils {
    suspend fun compressImageFile(originalFile: File, quality: Int = 70, maxDimension: Int = 1024): File = withContext(Dispatchers.IO) {
        // Декодируем оригинальное изображение
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(originalFile.path, options)

        // Вычисляем коэффициент масштабирования для уменьшения размера
        val scaleFactor = calculateInSampleSize(options, maxDimension, maxDimension)
        options.inJustDecodeBounds = false
        options.inSampleSize = scaleFactor

        val bitmap = BitmapFactory.decodeFile(originalFile.path, options)

        // Сжимаем в JPEG с заданным качеством
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

        // Создаем временный файл для сжатого изображения
        val compressedFile = File.createTempFile("compressed_${System.currentTimeMillis()}", ".jpg")
        FileOutputStream(compressedFile).use { fos ->
            fos.write(outputStream.toByteArray())
        }

        bitmap.recycle() // Освобождаем память
        return@withContext compressedFile
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}