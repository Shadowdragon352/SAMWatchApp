package au.org.deliriumwatch.watch

import android.content.Context
import android.graphics.Color
import android.view.Gravity
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
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(side, vertical, side, vertical)
        root.setBackgroundColor(Color.BLACK)
        return ScrollView(context).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(root)
        }
    }
}
