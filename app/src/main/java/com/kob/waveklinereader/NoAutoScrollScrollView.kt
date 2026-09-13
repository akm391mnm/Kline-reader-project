package com.kob.waveklinereader

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/**
 * A ScrollView that ignores descendant requests to scroll a particular
 * child rectangle into view.
 *
 * Android ScrollViews do this automatically whenever a focusable
 * descendant's content changes — and tvLog has android:textIsSelectable=
 * "true" (so its text can be long-press-copied), which makes it focusable.
 * The result: every single log() call (even the very first line logged on
 * connect()) made the *whole page* ScrollView jump down to reveal tvLog,
 * which sits near the bottom of the layout — not something the user asked
 * for or expected.
 *
 * This override only affects this outer page-level ScrollView. Manual user
 * scrolling still works normally, and the log's own inner ScrollView
 * (scrollLog) still auto-scrolls itself to the bottom on new log lines via
 * its own explicit fullScroll(FOCUS_DOWN) call in log() — that call scrolls
 * scrollLog itself directly and does not go through this method.
 */
class NoAutoScrollScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect?, immediate: Boolean): Boolean {
        return false
    }
}
