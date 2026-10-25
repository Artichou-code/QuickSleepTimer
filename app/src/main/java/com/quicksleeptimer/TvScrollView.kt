package com.quicksleeptimer

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * Custom ScrollView for Android TV that smoothly centers the focused child
 * vertically on screen instead of scrolling only to the edge.
 */
class TvScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0) return 0

        val viewHeight = height
        val screenTop = scrollY
        val screenBottom = screenTop + viewHeight

        // Center of the child rect in ScrollView content coordinates
        val childCenterY = (rect.top + rect.bottom) / 2
        // Current center of the visible viewport
        val viewportCenterY = screenTop + (viewHeight / 2)

        var delta = childCenterY - viewportCenterY

        // Clamp at top
        if (screenTop + delta < 0) {
            delta = -screenTop
        } else {
            // Clamp at bottom
            val contentBottom = getChildAt(0).bottom
            val maxScroll = (contentBottom - screenBottom).coerceAtLeast(0)
            val currentScrollToMax = maxScroll
            if (delta > currentScrollToMax) {
                delta = currentScrollToMax
            }
        }

        return delta
    }
}
