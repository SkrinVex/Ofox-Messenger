package com.SkrinVex.OfoxMessenger.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MorphLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFFF6B35),
    sizeDp: Int = 48
) {
    LoadingIndicator(
        modifier = modifier.size(sizeDp.dp),
        color = color,
        polygons = LoadingIndicatorDefaults.IndeterminateIndicatorPolygons
    )
}