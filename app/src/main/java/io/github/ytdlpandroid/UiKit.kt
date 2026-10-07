package io.github.ytdlpandroid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.LinearLayout

/**
 * Programmatic-UI primitives shared by `MainActivity` and the list adapters.
 *
 * These are top-level and package-internal rather than `MainActivity` members so
 * that adapters can style their item views without holding an Activity reference,
 * which Requirement 5.11 forbids.
 */

internal const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
internal const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
internal const val BUTTON_PRESS_MS = 110L
internal const val SUCCESS_ANIMATION_MS = 260L

internal val COLOR_BACKGROUND = Color.rgb(17, 20, 32)
internal val COLOR_NAVIGATION = Color.rgb(17, 20, 32)
internal val COLOR_SURFACE = Color.rgb(35, 41, 61)
internal val COLOR_CARD = Color.rgb(26, 31, 48)
internal val COLOR_PLAYER_BAR = Color.rgb(38, 45, 67)
internal val COLOR_TEXT = Color.rgb(247, 247, 255)
internal val COLOR_MUTED = Color.rgb(183, 190, 211)
internal val COLOR_ACCENT = Color.rgb(93, 230, 190)
internal val COLOR_ACCENT_SECONDARY = Color.rgb(193, 164, 255)
internal val COLOR_ACCENT_WARM = Color.rgb(255, 190, 122)
internal val COLOR_ACCENT_BLUE = Color.rgb(112, 186, 255)
internal val COLOR_ACCENT_PINK = Color.rgb(255, 137, 181)
internal val COLOR_HERO_START = Color.rgb(89, 52, 180)
internal val COLOR_HERO_MIDDLE = Color.rgb(126, 67, 189)
internal val COLOR_HERO_END = Color.rgb(34, 162, 160)
internal val COLOR_HERO_TEXT = Color.WHITE

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius.toFloat()
}

internal fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

internal fun Context.styleActionButton(button: Button, primary: Boolean) {
    button.isAllCaps = false
    button.textSize = 14f
    button.setTextColor(if (primary) COLOR_BACKGROUND else COLOR_TEXT)
    button.background = roundedBackground(if (primary) COLOR_ACCENT else COLOR_SURFACE, dp(12))
    button.setPadding(dp(12), 0, dp(12), 0)
    button.minHeight = dp(48)
    button.minWidth = 0
    button.stateListAnimator = null
    button.setOnTouchListener { view, event ->
        if (animationsEnabled()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(BUTTON_PRESS_MS)
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(BUTTON_PRESS_MS)
                    .start()
            }
        }
        false
    }
}

/** Overshoot pop that draws the eye to a row whose state just changed. */
internal fun View.animateStateChange() {
    if (!animationsEnabled()) return
    animate().cancel()
    alpha = 0.75f
    scaleX = 0.97f
    scaleY = 0.97f
    animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(SUCCESS_ANIMATION_MS)
        .setInterpolator(OvershootInterpolator(1.15f))
        .start()
}
