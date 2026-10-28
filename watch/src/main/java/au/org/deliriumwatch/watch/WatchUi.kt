package au.org.deliriumwatch.watch

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.roundToInt

object WatchUi {
    fun dp(context: Context, value: Float) = (value * context.resources.displayMetrics.density).roundToInt()
    fun scroll(context: Context, root: LinearLayout): ScrollView {
        val metrics = context.resources.displayMetrics
        val round = context.resources.configuration.isScreenRound
        // Inset content by ~16% of diameter; top/bottom spacers let every row
        // scroll through the widest central part of the round display.
        val side = if (round) (metrics.widthPixels * 0.16f).roundToInt() else dp(context, 12f)
        val vertical = if (round) (metrics.heightPixels * 0.20f).roundToInt() else dp(context, 12f)
        root.minimumWidth = 0
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(side, vertical, side, vertical)
        root.setBackgroundColor(Color.BLACK)
        return object : ScrollView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                // Use the actual viewport, including emulator/window insets.
                val horizontal = if (round) (w * 0.18f).roundToInt() else dp(context, 12f)
                val spacer = if (round) (h * 0.22f).roundToInt() else dp(context, 12f)
                root.setPadding(horizontal, spacer, horizontal, spacer)
            }
        }.apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(root, FrameLayout.LayoutParams(-1, -2))
        }
    }
}
