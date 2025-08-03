package com.SkrinVex.OfoxMessenger.ui.dialogs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object DialogController {
    private val _showComingSoon = MutableStateFlow(false)
    val showComingSoon: StateFlow<Boolean> = _showComingSoon

    fun showComingSoonDialog() {
        _showComingSoon.value = true
    }

    fun dismissComingSoonDialog() {
        _showComingSoon.value = false
    }
}