package com.SkrinVex.OfoxMessenger

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ModernToggle(
    selectedTab: String,
    onTabSelected: (String) -> Unit,
    hasNotifications: Boolean,
    tab1Text: String,
    tab1Value: String,
    tab2Text: String,
    tab2Value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1E1E1E)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToggleButton(
            text = tab1Text,
            value = tab1Value,
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            modifier = Modifier.weight(1f),
            hasNotifications = hasNotifications && tab2Value == "notifications"
        )
        ToggleButton(
            text = tab2Text,
            value = tab2Value,
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            modifier = Modifier.weight(1f),
            hasNotifications = hasNotifications && tab2Value == "notifications"
        )
    }
}

@Composable
fun ToggleButton(
    text: String,
    value: String,
    selectedTab: String,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    hasNotifications: Boolean = false
) {
    val isSelected = selectedTab == value
    val infiniteTransition = rememberInfiniteTransition(label = "toggleButtonAnimation")
    val animatedColor by infiniteTransition.animateColor(
        initialValue = Color(0xFFFF6B35),
        targetValue = Color(0xFFFFD1B2),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "colorAnimation"
    )
    val animatedScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scaleAnimation"
    )
    val backgroundColor = if (isSelected) {
        Color(0xFFFF6B35)
    } else if (hasNotifications && !isSelected) {
        animatedColor
    } else {
        Color.Transparent
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .clickable { onTabSelected(value) }
            .then(
                if (hasNotifications && !isSelected) {
                    Modifier.scale(animatedScale)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.7f),
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetContent(
    item: Any, // Поддерживает Message или PostItem
    isOwnItem: Boolean,
    onCopy: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onShare: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            text = if (item is Message) "Действия с сообщением" else "Опции поста",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        if (onCopy != null && item is Message) {
            OptionButton(
                icon = Icons.Rounded.FileCopy,
                label = "Копировать",
                onClick = onCopy
            )
        }
        if (isOwnItem) {
            if (onEdit != null && item is PostItem) {
                Spacer(modifier = Modifier.height(12.dp))
                OptionButton(
                    icon = Icons.Rounded.Edit,
                    label = "Изменить",
                    onClick = onEdit
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OptionButton(
                icon = Icons.Rounded.Delete,
                label = "Удалить",
                onClick = onDelete
            )
        }
        // Show Share button for all users (author or not) when onShare is provided
        if (onShare != null && item is PostItem) {
            Spacer(modifier = Modifier.height(12.dp))
            OptionButton(
                icon = Icons.Rounded.Share,
                label = "Поделиться",
                onClick = onShare
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
fun OptionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = Color(0xFF2A2A2A).copy(alpha = 0.95f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFFF6B35),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun DeleteConfirmationDialog(
    itemType: String = "item", // "message" или "post"
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Удалить $itemType",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "Вы уверены, что хотите удалить $itemType?",
                color = Color.White
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35))
            ) {
                Text("Удалить", color = Color.Black)
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
                Text("Отмена", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f)
    )
}