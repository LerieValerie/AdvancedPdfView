package org.benjinus.pdfium.search

import android.graphics.RectF

interface TextSearchContext {
    fun prepareSearch()
    val pageIndex: Int
    val query: String?
    val isMatchCase: Boolean
    val isMatchWholeWord: Boolean
    operator fun hasNext(): Boolean
    fun hasPrev(): Boolean
    fun searchNext(): List<RectF>
    fun searchPrev(): List<RectF>
    fun startSearch()
    fun stopSearch()
}