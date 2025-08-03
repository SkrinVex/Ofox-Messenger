package com.SkrinVex.OfoxMessenger

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.text.HtmlCompat

fun showAuthErrorDialog(context: Context, currentUid: String?, receivedUid: String?) {
    val message = buildString {
        appendLine("❌ Ошибка авторизации!")
        appendLine("currentUser?.uid = $currentUid")
        appendLine("uid из Intent = $receivedUid")
        appendLine()
        appendLine("Пожалуйста, перезапустите приложение или обратитесь в техподдержку.")
    }

    AlertDialog.Builder(context)
        .setTitle("Ошибка доступа")
        .setMessage(message)
        .setCancelable(false)
        .setPositiveButton("Закрыть") { _, _ ->
            (context as? Activity)?.finish()
        }
        .show()
}