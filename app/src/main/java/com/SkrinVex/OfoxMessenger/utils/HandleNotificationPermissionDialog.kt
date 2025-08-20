package com.SkrinVex.OfoxMessenger.utils

import android.app.Activity
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.ActivityCompat
import com.SkrinVex.OfoxMessenger.PermissionState

@Composable
fun HandleNotificationPermissionDialog() {
    val context = LocalContext.current
    var showDialog by remember {
        mutableStateOf(
            PermissionState.needsNotificationPermission &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        )
    }

    if (showDialog) {
        NotificationPermissionDialog(
            onConfirm = {
                PermissionState.needsNotificationPermission = false
                showDialog = false

                ActivityCompat.requestPermissions(
                    context as Activity,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            },
            onDismiss = {
                PermissionState.needsNotificationPermission = false
                showDialog = false
            }
        )
    }
}

@Composable
fun NotificationPermissionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Разрешение на уведомления",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "Для отображения уведомлений, пожалуйста, разрешите их в следующем запросе.",
                color = Color.White
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35))
            ) {
                Text("Разрешить", color = Color.Black)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF333333),
                    contentColor = Color.White
                )
            ) {
                Text("Не сейчас", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f)
    )
}
