package com.shantanu.shield.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One step in a coach-mark tour.
 *
 * @param targetId  Layout target ID to highlight via [Modifier.coachTarget]. Null = no
 *                  highlight; the card is centered on screen (used for welcome / done
 *                  steps that introduce or wrap-up the tour).
 */
data class CoachStep(
    val id: String,
    val title: String,
    val body: String,
    val targetId: String? = null
)

private enum class CoachCardPlacement { Center, Above, Below }

object CoachTours {
    const val FIRST_RUN_ID = "first-run-v2"
    const val SETTINGS_ID = "settings-tour-v1"

    val SETTINGS: List<CoachStep> = listOf(
        CoachStep(
            id = "settings-welcome",
            title = "Settings — quick tour",
            body = "A 4-stop walkthrough of what each section here does. Skip anytime.",
            targetId = null
        ),
        CoachStep(
            id = "settings-kid-mode",
            title = "Kid Mode",
            body = "Set a daily screen-time budget for your kid, pick which apps stay always-allowed (calls, messages), and turn on a night-time lock. Tap into Kid Mode any time to adjust.",
            targetId = "settings-kid-mode"
        ),
        CoachStep(
            id = "settings-intruder-style",
            title = "Intruder Feedback Style",
            body = "Pick what the locked app shows when someone other than you tries to open it: a fake hardware error, a polite wellness warning, or a spiritual quote.",
            targetId = "settings-intruder-style"
        ),
        CoachStep(
            id = "settings-tamper",
            title = "Tamper Protection",
            body = "One master switch turns on three protections. Tap it for max safety, or expand to flip each one individually — let's look at them.",
            targetId = "settings-tamper"
        ),
        CoachStep(
            id = "tamper-lock-settings",
            title = "Lock System Settings",
            body = "Require Face ID to open the device Settings shortcut — so a child can't reach Force-Stop, app permissions, or Uninstall on their own.",
            targetId = "tamper-lock-settings"
        ),
        CoachStep(
            id = "tamper-protect-app",
            title = "Protect This App",
            body = "Require Face ID to open Kids Shield itself. Even if your child finds the icon, they can't disable budgets or allowed apps without your face.",
            targetId = "tamper-protect-app"
        ),
        CoachStep(
            id = "tamper-prevent-uninstall",
            title = "Prevent Uninstall",
            body = "Register as Device Admin so Android blocks the Uninstall action entirely. Turn off any time from Settings > Device Admin if you need to remove the app.",
            targetId = "tamper-prevent-uninstall"
        ),
        CoachStep(
            id = "settings-security-foundation",
            title = "Security Foundation",
            body = "The five system permissions Kids Shield needs to keep running 24/7. Each row jumps you straight into the right OS settings page.",
            targetId = "settings-security-foundation"
        ),
        CoachStep(
            id = "settings-done",
            title = "That's Settings",
            body = "Replay this tour anytime from Settings > Help & Onboarding.",
            targetId = null
        )
    )

    val FIRST_RUN: List<CoachStep> = listOf(
        CoachStep(
            id = "welcome",
            title = "Welcome to Kids Face Shield",
            body = "A quick guided tour of the main features. You can skip anytime.",
            targetId = null
        ),
        CoachStep(
            id = "status",
            title = "Status banner",
            body = "Always shows what's protecting your phone right now — setup pending, Free Play active, or shield ON.",
            targetId = "status-banner"
        ),
        CoachStep(
            id = "protect-item",
            title = "Tap to require face unlock",
            body = "Toggle any app in this list. Once on, your face is required every time that app opens.",
            targetId = "protect-item"
        ),
        CoachStep(
            id = "free-play",
            title = "Hand-off mode",
            body = "Tap Free Play before handing the phone to your kid. All apps unlock for the chosen time — protection resumes automatically.",
            targetId = "free-play-fab"
        ),
        CoachStep(
            id = "stats-tab",
            title = "Daily usage",
            body = "Switch here to see how much each app was used today and on previous days.",
            targetId = "stats-tab"
        ),
        CoachStep(
            id = "settings-tab",
            title = "Kid Mode + advanced",
            body = "Daily screen-time budgets, allowed-apps list, and tamper protection live in Settings.",
            targetId = "settings-tab"
        ),
        CoachStep(
            id = "done",
            title = "You're all set",
            body = "You can replay this tour anytime from Settings > Help & Onboarding.",
            targetId = null
        )
    )
}

class CoachMarkController {
    var visible by mutableStateOf(false)
        private set
    var currentIndex by mutableStateOf(0)
        private set
    var steps by mutableStateOf<List<CoachStep>>(emptyList())
        private set
    var activeTourId by mutableStateOf<String?>(null)
        private set

    // We store the snapshot Rect, NOT the live LayoutCoordinates, because the latter
    // tracks the node's CURRENT position — which means once the overlay's Popup is
    // shown the coords flip into the popup's coordinate space and we get garbage
    // bounds. Snapshotting on each onGloballyPositioned call freezes the value.
    val targets = mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>()

    fun start(tourId: String, stepList: List<CoachStep>) {
        if (stepList.isEmpty()) return
        activeTourId = tourId
        steps = stepList
        currentIndex = 0
        visible = true
    }

    fun next(onComplete: (tourId: String) -> Unit = {}) {
        if (currentIndex < steps.lastIndex) {
            currentIndex++
        } else {
            val finishedTour = activeTourId
            dismiss()
            if (finishedTour != null) onComplete(finishedTour)
        }
    }

    fun skip(onComplete: (tourId: String) -> Unit = {}) {
        val finishedTour = activeTourId
        dismiss()
        if (finishedTour != null) onComplete(finishedTour)
    }

    private fun dismiss() {
        visible = false
        activeTourId = null
        steps = emptyList()
        currentIndex = 0
    }
}

val LocalCoachMarks = compositionLocalOf<CoachMarkController?> { null }

/** Provided by MainScreen so any nested composable (e.g. the Settings "Replay tour"
 * button) can request the user be switched to a specific bottom-bar tab. */
val LocalRequestTab = compositionLocalOf<((Int) -> Unit)?> { null }

fun Modifier.coachTarget(id: String): Modifier = composed {
    val controller = LocalCoachMarks.current
    if (controller == null) this else this.onGloballyPositioned { coords ->
        controller.targets[id] = coords.boundsInRoot()
    }
}

@Composable
fun CoachMarkOverlay(
    controller: CoachMarkController,
    onTourComplete: (tourId: String) -> Unit
) {
    if (controller.visible) {
        BackHandler(enabled = true) { controller.skip(onComplete = onTourComplete) }
    }
    AnimatedVisibility(
        visible = controller.visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150))
    ) {
        val step = controller.steps.getOrNull(controller.currentIndex) ?: return@AnimatedVisibility
        val interactionSource = remember { MutableInteractionSource() }
        // Render the overlay directly in the host composition (NOT a Popup) so the
        // canvas / Modifier coordinates match the host window's coordinate space.
        // We swallow touches via .clickable(no-op) so taps don't fall through to the
        // UI underneath.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { /* eat clicks */ }
        ) {
                val density = LocalDensity.current
                val maxW = constraints.maxWidth.toFloat()
                val maxH = constraints.maxHeight.toFloat()
                val targetRect = step.targetId?.let { controller.targets[it] }

                val scrimColor = Color.Black.copy(alpha = 0.82f)
                val highlightPaddingPx = with(density) { 10.dp.toPx() }
                val edgeMarginPx = with(density) { 12.dp.toPx() }
                val cornerPx = with(density) { 18.dp.toPx() }
                val borderColor = MaterialTheme.colorScheme.primary

                // Animated pulse on the highlight ring so the user's eye is drawn there.
                val infiniteTransition = rememberInfiniteTransition(label = "coach-pulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "alpha"
                )
                val pulseStrokePx = with(density) { 3.dp.toPx() }

                // Scrim layer (covers everything; punches a rounded hole around the target).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            if (targetRect == null) {
                                drawRect(scrimColor, Offset.Zero, this.size)
                                return@drawBehind
                            }
                            val left = (targetRect.left - highlightPaddingPx)
                                .coerceAtLeast(edgeMarginPx)
                            val top = (targetRect.top - highlightPaddingPx)
                                .coerceAtLeast(edgeMarginPx)
                            val right = (targetRect.right + highlightPaddingPx)
                                .coerceAtMost(maxW - edgeMarginPx)
                            val bottom = (targetRect.bottom + highlightPaddingPx)
                                .coerceAtMost(maxH - edgeMarginPx)

                            val cutoutPath = Path().apply {
                                fillType = PathFillType.EvenOdd
                                addRect(Rect(0f, 0f, maxW, maxH))
                                addRoundRect(
                                    RoundRect(
                                        left = left,
                                        top = top,
                                        right = right,
                                        bottom = bottom,
                                        cornerRadius = CornerRadius(cornerPx, cornerPx)
                                    )
                                )
                            }
                            drawPath(cutoutPath, scrimColor)

                            drawRoundRect(
                                color = borderColor.copy(alpha = pulseAlpha),
                                topLeft = Offset(left, top),
                                size = Size(right - left, bottom - top),
                                cornerRadius = CornerRadius(cornerPx, cornerPx),
                                style = Stroke(width = pulseStrokePx)
                            )
                        }
                )

                // Card placement:
                //   - welcome (FIRST_RUN) tour → ALWAYS the fixed lower-mid layout the
                //     user already approved (0.45 / 0.30).
                //   - settings tour → dynamic above/below the target so the card and
                //     the highlight don't overlap on lower-half targets.
                val useDynamicPlacement = controller.activeTourId == CoachTours.SETTINGS_ID
                val placement = when {
                    !useDynamicPlacement -> CoachCardPlacement.Center
                    targetRect == null -> CoachCardPlacement.Center
                    targetRect.center.y < maxH * 0.5f -> CoachCardPlacement.Below
                    else -> CoachCardPlacement.Above
                }
                val insetsPadding = WindowInsets.systemBars.asPaddingValues()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(insetsPadding)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val (topWeight, bottomWeight) = when (placement) {
                        CoachCardPlacement.Center -> 0.45f to 0.30f
                        CoachCardPlacement.Below -> 0.60f to 0.05f
                        CoachCardPlacement.Above -> 0.05f to 0.55f
                    }
                    Spacer(modifier = Modifier.weight(topWeight))
                    CoachCard(
                        step = step,
                        index = controller.currentIndex,
                        total = controller.steps.size,
                        onNext = { controller.next(onTourComplete) },
                        onSkip = { controller.skip(onTourComplete) }
                    )
                    Spacer(modifier = Modifier.weight(bottomWeight))
                }
            }
    }
}

@Composable
private fun CoachCard(
    step: CoachStep,
    index: Int,
    total: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    val isFirst = index == 0
    val isLast = index == total - 1
    val isCentered = step.targetId == null

    Card(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            // Step indicator dots — visible only for multi-step tours, omit for the
            // welcome/done bookends so they feel less "form-like".
            if (total > 1) {
                StepDots(total = total, current = index)
                Spacer(Modifier.height(16.dp))
            }

            if (isLast && isCentered) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                step.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                step.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isLast) Arrangement.End else Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isLast) {
                    TextButton(onClick = onSkip) {
                        Text("Skip tour")
                    }
                }
                Button(onClick = onNext) {
                    Text(
                        when {
                            isLast -> "Got it"
                            isFirst -> "Start tour"
                            else -> "Next"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepDots(total: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            val isActive = i == current
            val dotSize: Dp = if (isActive) 8.dp else 6.dp
            val dotColor = if (isActive)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .background(dotColor, shape = CircleShape)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "Step ${current + 1} of $total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
