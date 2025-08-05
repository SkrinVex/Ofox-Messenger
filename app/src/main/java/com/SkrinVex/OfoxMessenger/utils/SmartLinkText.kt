package com.SkrinVex.OfoxMessenger.utils

import android.content.Intent
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
import java.util.regex.Pattern

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
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5
    val linkColor = Color(0xFFFF6B35) // Ярко-оранжевый как в дизайне
    var dialogUrl by remember { mutableStateOf<String?>(null) }

    val annotatedText = remember(text, linkColor) {
        buildAnnotatedString {
            val matcher = Pattern.compile(
                "(https?://[\\w-._~:/?#\\[\\]@!$&'()*+,;=%]+|(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(?:/[\\w-._~:/?#@!$&'()*+,;=%]*)?)",
                Pattern.CASE_INSENSITIVE
            ).matcher(text)
            var lastIndex = 0

            while (matcher.find()) {
                val start = matcher.start()
                val end = matcher.end()
                append(text.substring(lastIndex, start))
                val url = text.substring(start, end)
                pushStringAnnotation("URL", if (url.startsWith("http")) url else "https://$url")
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(url)
                }
                pop()
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
            }
        )
    }

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
