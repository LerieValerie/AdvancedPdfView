package org.benjinus.pdfium.search

import android.graphics.RectF

data class SearchRect(
    val page: Int,
    val rect: RectF
)

data class SearchRectWithIndex(
    val searchRect: SearchRect,
    val index: Int
)

enum class SearchRectVisibilityMode {
    Invisible,
    Visible,
    VisibleWithScroll
}

data class SearchRectWithVisibility(
    val searchRect: SearchRect,
    val index: Int,
    val deviceSearchRect: RectF,
    val visibilityMode: SearchRectVisibilityMode
)