package com.shantanu.shield.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Sub-screen → parent top-bar override. When a nested destination (e.g. Kid Mode
 * setup) is shown, it publishes its title + back action through this state. The
 * parent Scaffold reads it and replaces the default tab title until the sub-screen
 * un-mounts. Keeps a SINGLE header on-screen at any time so we stay uniform.
 */
data class TopBarOverride(val title: String, val onBack: () -> Unit)
val LocalTopBarOverride = compositionLocalOf<MutableState<TopBarOverride?>?> { null }

/**
 * Single app-wide top bar. Left-aligned title (Android default), back arrow on
 * sub-screens. Actions slot reserved for future contextual per-screen icons
 * (today: unused).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KfsTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.3.sp,
                style = MaterialTheme.typography.titleLarge
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val strokePx = 1f
                drawRect(
                    color = dividerColor,
                    topLeft = Offset(0f, size.height - strokePx),
                    size = Size(size.width, strokePx)
                )
            }
    )
}
