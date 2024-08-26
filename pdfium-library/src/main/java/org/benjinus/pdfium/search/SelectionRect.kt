package org.benjinus.pdfium.search

import android.graphics.Rect
import android.graphics.RectF

data class SelectionRect(
    val page: Int,
    val rect: RectF
)

data class SelectionRectWithIndex(
    val selectionRect: SelectionRect,
    val charIndex: Int,
    val iconRect: Rect?
)