package com.SkrinVex.OfoxMessenger.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.SkrinVex.OfoxMessenger.ProfileViewActivity
import android.net.Uri
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.regex.Pattern
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun SmartLinkText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontSize: TextUnit = 16.sp,
    lineHeight: TextUnit = 20.sp,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Вычисляем автоматически подходящий цвет ссылок и упоминаний
    val defaultLinkColor = Color(0xFFFF6B35) // оранжевый
    val fallbackLinkColor = Color.White

    // Проверка контрастности с фоном
    fun adjustColorForBackground(baseColor: Color, backgroundColor: Color): Color {
        val contrast = kotlin.math.abs(baseColor.luminance() - backgroundColor.luminance())
        return if (contrast < 0.4f) fallbackLinkColor else baseColor
    }

    // Автоматически подстраиваем цвет ссылок и упоминаний под цвет фона
    val effectiveLinkColor = adjustColorForBackground(defaultLinkColor, color)
    val effectiveMentionColor = adjustColorForBackground(defaultLinkColor, color)

    val linkColor = Color(0xFFFF6B35) // оранжевый

    var dialogUrl by remember { mutableStateOf<String?>(null) }
    var mentionsMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

    // Этап 1: Извлечение всех упоминаний
    val allMentions = remember(text) {
        val pattern = Pattern.compile("@[a-zA-Z0-9_]+")
        val matcher = pattern.matcher(text)
        val mentions = mutableSetOf<String>()
        while (matcher.find()) {
            mentions.add(matcher.group()) // Сохраняем сразу с @
        }
        mentions
    }

    // Этап 2: Поиск UID по username и запоминание
    LaunchedEffect(allMentions) {
        val map = mutableMapOf<String, String?>()
        for (usernameWithAt in allMentions) {
            val uid = getUidByUsername(usernameWithAt)
            Log.d("SmartLinkText", "UID lookup: $usernameWithAt → $uid")
            map[usernameWithAt] = uid
        }
        mentionsMap = map
    }

    // Этап 3: Подсветка текста
    val annotatedText = remember(text, mentionsMap) {
        buildAnnotatedString {
            val matcher = Pattern.compile(
                "(https?://[\\w-._~:/?#\\[\\]@!$&'()*+,;=%]+|(?:[a-zA-Z0-9,]+\\.)+[a-zA-Z]{2,}(?:/[\\w-._~:/?#@!$&'()*+,;=%]*)?)|(@[a-zA-Z0-9_]+)"
            ).matcher(text)

            var lastIndex = 0

            while (matcher.find()) {
                val start = matcher.start()
                val end = matcher.end()
                append(text.substring(lastIndex, start))
                val match = text.substring(start, end)

                if (match.startsWith("@")) {
                    val usernameWithAt = match // уже с @
                    val uid = mentionsMap[usernameWithAt]

                    if (uid != null) {
                        pushStringAnnotation("MENTION", usernameWithAt)
                        withStyle(
                            SpanStyle(
                                color = effectiveMentionColor,
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append(match)
                        }
                        pop()
                    } else {
                        withStyle(SpanStyle(color = Color.Gray)) {
                            append(match)
                        }
                    }
                } else {
                    val url = if (match.startsWith("http")) match else "https://$match"
                    pushStringAnnotation("URL", url)
                    withStyle(
                        SpanStyle(
                            color = effectiveLinkColor,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(match)
                    }
                    pop()
                }
                lastIndex = end
            }
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }

    SelectionContainer {
        ClickableText(
            text = annotatedText,
            modifier = modifier,
            style = style.copy(
                fontSize = fontSize,
                color = color,
                lineHeight = lineHeight,
                fontWeight = fontWeight ?: style.fontWeight
            ),
            onClick = { offset ->
                annotatedText.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                    dialogUrl = it.item
                }
                annotatedText.getStringAnnotations("MENTION", offset, offset).firstOrNull()?.let { annotation ->
                    val usernameWithAt = annotation.item // уже с @
                    val uid = mentionsMap[usernameWithAt]
                    if (uid != null) {
                        val intent = Intent(context, ProfileViewActivity::class.java).apply {
                            putExtra("uid", FirebaseAuth.getInstance().currentUser?.uid)
                            putExtra("friend_uid", uid)
                        }
                        context.startActivity(intent)
                    } else {
                        Toast.makeText(context, "Пользователь не найден", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Диалог подтверждения перехода по URL
    if (dialogUrl != null) {
        AlertDialog(
            onDismissRequest = { dialogUrl = null },
            confirmButton = {
                Button(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dialogUrl)))
                        dialogUrl = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35))
                ) {
                    Text("Перейти", color = Color.Black)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { dialogUrl = null },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF333333),
                        contentColor = Color.White
                    )
                ) {
                    Text("Отмена", fontWeight = FontWeight.Bold)
                }
            },
            title = {
                Text("Переход по ссылке", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    buildAnnotatedString {
                        append("Вы уверены, что хотите перейти по ссылке?\n\n")
                        withStyle(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append(dialogUrl!!)
                        }
                    },
                    color = Color.White
                )
            },
            containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f)
        )
    }
}

suspend fun getUidByUsername(username: String): String? {
    return try {
        val snapshot = FirebaseDatabase.getInstance()
            .getReference("users")
            .orderByChild("username")
            .startAt(username)
            .endAt(username + "\uf8ff")
            .get()
            .await()

        for (child in snapshot.children) {
            val dbUsername = child.child("username").getValue(String::class.java)
            if (dbUsername == username) {
                return child.key
            }
        }

        null
    } catch (e: Exception) {
        Log.e("SmartLinkText", "Ошибка поиска UID для username: $username", e)
        null
    }
}

@Composable
fun CopyableText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val context = LocalContext.current

    ClickableText(
        text = text,
        modifier = modifier,
        style = style.copy(color = Color(0xFFFF6B35)), // Оранжевый
        maxLines = maxLines,
        overflow = overflow,
        onClick = {
            val plainText = text.text
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("CopiedText", plainText)
            clipboard.setPrimaryClip(clip)

            Toast
                .makeText(context, "Текст скопирован", Toast.LENGTH_SHORT)
                .show()
        }
    )
}