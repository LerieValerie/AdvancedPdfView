package com.github.barteksc.pdfviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.toRectF
import com.github.barteksc.pdfviewer.decoding.DecodingRunner
import com.github.barteksc.pdfviewer.exception.PageRenderingException
import com.github.barteksc.pdfviewer.link.DefaultLinkHandler
import com.github.barteksc.pdfviewer.link.LinkHandler
import com.github.barteksc.pdfviewer.listener.Callbacks
import com.github.barteksc.pdfviewer.listener.OnDrawListener
import com.github.barteksc.pdfviewer.listener.OnErrorListener
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
import com.github.barteksc.pdfviewer.listener.OnLoadStartListener
import com.github.barteksc.pdfviewer.listener.OnLongPressListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.github.barteksc.pdfviewer.listener.OnPageErrorListener
import com.github.barteksc.pdfviewer.listener.OnPageScrollListener
import com.github.barteksc.pdfviewer.listener.OnRenderListener
import com.github.barteksc.pdfviewer.listener.OnTapListener
import com.github.barteksc.pdfviewer.listener.OnTextSearchListener
import com.github.barteksc.pdfviewer.listener.OnTextSelectListener
import com.github.barteksc.pdfviewer.model.LinkTapEvent
import com.github.barteksc.pdfviewer.model.PagePart
import com.github.barteksc.pdfviewer.scroll.ScrollHandle
import com.github.barteksc.pdfviewer.source.DocumentSource
import com.github.barteksc.pdfviewer.source.RandomAccessReaderSource
import com.github.barteksc.pdfviewer.util.Color.textSelectionColor
import com.github.barteksc.pdfviewer.util.ColorScheme
import com.github.barteksc.pdfviewer.util.Constants
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.github.barteksc.pdfviewer.util.MathUtils.limit
import com.github.barteksc.pdfviewer.util.SnapEdge
import com.github.barteksc.pdfviewer.util.Util.getDP
import com.sovworks.projecteds.domain.common.required
import com.sovworks.projecteds.domain.common.util.LoadingState
import com.sovworks.projecteds.domain.common.util.orFalse
import com.sovworks.projecteds.domain.filemanager.MediaRandomAccessReaderDataSource
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.benjinus.pdfium.Bookmark
import org.benjinus.pdfium.Link
import org.benjinus.pdfium.Meta
import org.benjinus.pdfium.search.SearchRect
import org.benjinus.pdfium.search.SearchRectVisibilityMode
import org.benjinus.pdfium.search.SearchRectWithVisibility
import org.benjinus.pdfium.search.SelectionRect
import org.benjinus.pdfium.search.SelectionRectWithIndex
import org.benjinus.pdfium.util.Size
import org.benjinus.pdfium.util.SizeF
import kotlin.math.max
import kotlin.math.min


/**
 * It supports animations, zoom, cache, and swipe.
 *
 *
 * To fully understand this class you must know its principles :
 * - The PDF document is seen as if we always want to draw all the pages.
 * - The thing is that we only draw the visible parts.
 * - All parts are the same size, this is because we can't interrupt a native page rendering,
 * so we need these renderings to be as fast as possible, and be able to interrupt them
 * as soon as we can.
 * - The parts are loaded when the current offset or the current zoom level changes
 *
 *
 * Important :
 * - DocumentPage = A page of the PDF document.
 * - UserPage = A page as defined by the user.
 * By default, they're the same. But the user can change the pages order
 * using [.load]. In this
 * particular case, a userPage of 5 can refer to a documentPage of 17.
 */
class PDFView(
    context: Context?,
    set: AttributeSet?
) : RelativeLayout(
    context,
    set
) {
    var minZoom = DEFAULT_MIN_SCALE
    var midZoom = DEFAULT_MID_SCALE
    var maxZoom = DEFAULT_MAX_SCALE
    fun getSearchQuery(): String {
        return searchQuery
    }

    fun updatePagesWithSearch(searchQuery: String) {
        this.searchQuery = searchQuery
        loadPages()
    }

    /**
     * START - scrolling in first page direction
     * END - scrolling in last page direction
     * NONE - not scrolling
     */
    internal enum class ScrollDir {
        NONE,
        START,
        END
    }

    /**
     * Rendered parts go to the cache manager
     */
    var cacheManager: CacheManager

    /**
     * Animation manager manage all offset and zoom animation
     */
    private val animationManager: AnimationManager

    /**
     * Drag manager manage all touch events
     */
    private val dragPinchManager: DragPinchManager

    fun scrollView(distanceX: Float, distanceY: Float) {
        dragPinchManager.scrollView(distanceX, distanceY)
    }

    fun scrollToCurrentSearchItem(
        searchRectWithVisibility: SearchRectWithVisibility,
        isForward: Boolean
    ) {
        if (searchRectWithVisibility.visibilityMode == SearchRectVisibilityMode.VisibleWithScroll) {
            val diffX = if (searchRectWithVisibility.deviceSearchRect.left < 0) {
                searchRectWithVisibility.deviceSearchRect.left
            } else if (searchRectWithVisibility.deviceSearchRect.right > width) {
                -(searchRectWithVisibility.deviceSearchRect.right - width)
            } else 0f

            val diffY = if (isForward) {
                searchRectWithVisibility.deviceSearchRect.top - height + height / 2
            } else {
                searchRectWithVisibility.deviceSearchRect.top - height / 2
            }
            scrollView(diffX, diffY)
        }
    }

    @JvmField
    var pdfFile: PdfFile? = null

    val pdfFileRequired: PdfFile
        get() = pdfFile.required

    /**
     * The index of the current sequence
     */
    var currentPage = 0
        private set

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    var currentXOffset = 0f
        private set

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    var currentYOffset = 0f
        private set

    /**
     * The zoom level, always >= 1
     */
    var zoom = 1f
        private set

    /**
     * True if the PDFView has been recycled
     */
    private var isRecycled = true

    /**
     * Current state of the view
     */
    private var state = State.DEFAULT

    /**
     * The thread [.renderingHandler] will run on
     */
    private var renderingHandlerThread: HandlerThread?

    /**
     * Handler always waiting in the background and rendering tasks
     */
    internal var renderingHandler: RenderingHandler? = null
    private val pagesLoader: PagesLoader
    var callbacks = Callbacks()

    /**
     * Paint object for drawing
     */
    private val paint: Paint

    private val selectionPaint: Paint

    /**
     * Paint object for drawing debug stuff
     */
    private val debugPaint: Paint

    /**
     * Policy for fitting pages to screen
     */
    var pageFitPolicy = FitPolicy.WIDTH
        private set
    var isFitEachPage = false
        private set
    private var defaultPage = 0

    /**
     * True if should scroll through pages vertically instead of horizontally
     */
    var isSwipeVertical = true
        private set
    private var enableSwipe = true
    var isDoubletapEnabled = true
        private set
    private var nightMode = false
    private var searchQuery = ""

    var isPageSnap = true
    var scrollHandle: ScrollHandle? = null
        private set
    private var isScrollHandleInit = false

    /**
     * True if bitmap should use ARGB_8888 format and take more memory
     * False if bitmap should be compressed by using RGB_565 format and take less memory
     */
    var isBestQuality = false
        private set

    /**
     * True if annotations should be rendered
     * False otherwise
     */
    var isAnnotationRendering = false
        private set

    /**
     * True if the view should render during scaling<br></br>
     * Can not be forced on older API versions (< Build.VERSION_CODES.KITKAT) as the GestureDetector does
     * not detect scrolling while scaling.<br></br>
     * False otherwise
     */
    private var renderDuringScale = false

    /**
     * Antialiasing and bitmap filtering
     */
    private var enableAntialiasing = true
    private val antialiasFilter = PaintFlagsDrawFilter(
        0,
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    /**
     * Spacing between pages, in px
     */
    var spacingPx = 0
        private set

    /**
     * Add dynamic spacing to fit each page separately on the screen.
     */
    private var autoSpacing = false

    /**
     * Fling a single page at a time
     */
    private var pageFling = true

    /**
     * Pages numbers used when calling onDrawAllListener
     */
    private val onDrawPagesNums: MutableList<Int> = ArrayList(10)

    /**
     * Holds info whether view has been added to layout and has width and height
     */
    private var hasSize = false

    /**
     * Holds last used Configurator that should be loaded when view has size
     */
    private var waitingDocumentConfigurator: Configurator? = null

    var startSelectionRect: SelectionRectWithIndex? = null

    var endSelectionRect: SelectionRectWithIndex? = null

    var selectionRects = listOf<RectF>()

    fun clearSelection() {
        startSelectionRect = null
        endSelectionRect = null
        selectionRects = listOf()
        redraw()
    }

    /**
     * Override defualt color scheme.
     */
    var colorScheme: ColorScheme? = null
        private set


    private fun load(
        docSource: DocumentSource,
        password: String?,
        userPages: IntArray? = null
    ) {
        check(isRecycled) { "Don't call load on a PDF View without recycling it first." }
        isRecycled = false
        callbacks.callOnLoadStart()
        val runner = DecodingRunner(
            docSource,
            password,
            userPages,
            this
        )
        runner.executeAsync(object : DecodingRunner.Callback {
            override fun onComplete(result: PdfFile?) {
                loadComplete(result)
            }

            override fun onError(t: Throwable) {
                loadError(t)
            }
        })
    }

    /**
     * Go to the given page.
     *
     * @param page Page index.
     */
    @JvmOverloads
    fun jumpTo(
        page: Int,
        withAnimation: Boolean = false
    ) {
        var destPage = page
        if (pdfFile == null) {
            return
        }
        destPage = pdfFile?.determineValidPageNumberFrom(destPage) ?: 0
        val offset: Float = if (destPage == 0) 0f else -(pdfFile?.getPageOffset(
            destPage,
            zoom
        ) ?: 0f)
        if (isSwipeVertical) {
            if (withAnimation) {
                animationManager.startYAnimation(
                    currentYOffset,
                    offset
                )
            } else {
                moveTo(
                    currentXOffset,
                    offset
                )
            }
        } else {
            if (withAnimation) {
                animationManager.startXAnimation(
                    currentXOffset,
                    offset
                )
            } else {
                moveTo(
                    offset,
                    currentYOffset
                )
            }
        }
        showPage(destPage)
    }

    fun showPage(pageNb: Int) {
        var pageNumber = pageNb
        if (isRecycled) {
            return
        }

        // Check the page number and makes the
        // difference between UserPages and DocumentPages
        pdfFile?.determineValidPageNumberFrom(pageNumber)?.let {
            pageNumber = it
        }
        currentPage = pageNumber
        loadPages()
        scrollHandle?.let { sh ->
            if (!documentFitsView()) {
                sh.setPageNum(currentPage + 1)
            }
        }
        callbacks.callOnPageChange(
            currentPage,
            pdfFile?.pagesCount ?: 0
        )
    }

    /**
     * Get current position as ratio of document length to visible area.
     * 0 means that document start is visible, 1 that document end is visible
     *
     * @return offset between 0 and 1
     */
    fun getPositionOffset(): Float {
        val offset: Float = if (isSwipeVertical) {
            -currentYOffset / (pdfFile?.getDocLen(zoom) ?: 0f - height.toFloat())
        } else {
            -currentXOffset / (pdfFile?.getDocLen(zoom) ?: 0f - width.toFloat())
        }
        return limit(
            offset,
            0f,
            1f
        )
    }

    /**
     * @param progress   must be between 0 and 1
     * @param moveHandle whether to move scroll handle
     * @see PDFView.getPositionOffset
     */
    fun setPositionOffset(
        progress: Float,
        moveHandle: Boolean
    ) {
        if (isSwipeVertical) {
            moveTo(
                currentXOffset,
                (-(pdfFile?.getDocLen(zoom) ?: 0f) + height.toFloat()) * progress,
                moveHandle
            )
        } else {
            moveTo(
                (-(pdfFile?.getDocLen(zoom) ?: 0f) + width.toFloat()) * progress,
                currentYOffset,
                moveHandle
            )
        }
        loadPageByOffset()
    }

    fun setPositionOffset(progress: Float) {
        setPositionOffset(
            progress,
            true
        )
    }

    fun stopFling() {
        animationManager.stopFling()
    }

    fun getPageCount(): Int {
        return if (pdfFile == null) {
            0
        } else pdfFile?.pagesCount ?: 0
    }

    fun getTotalPagesCount(): Int {
        return pdfFile?.getTotalPagesCount() ?: 0
    }

    fun setSwipeEnabled(enableSwipe: Boolean) {
        this.enableSwipe = enableSwipe
    }

    fun setNightMode(nightMode: Boolean) {
        this.nightMode = nightMode
        if (nightMode) {
            val colorMatrixInverted = ColorMatrix(
                floatArrayOf(
                    -1f,
                    0f,
                    0f,
                    0f,
                    255f,
                    0f,
                    -1f,
                    0f,
                    0f,
                    255f,
                    0f,
                    0f,
                    -1f,
                    0f,
                    255f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f
                )
            )
            val filter = ColorMatrixColorFilter(colorMatrixInverted)
            paint.colorFilter = filter
        } else {
            paint.colorFilter = null
        }
    }

    fun enableDoubletap(enableDoubletap: Boolean) {
        isDoubletapEnabled = enableDoubletap
    }

    fun onPageError(ex: PageRenderingException) {
        if (!callbacks.callOnPageError(
                ex.page,
                ex.cause
            )
        ) {
            Log.e(
                TAG,
                "Cannot open page " + ex.page,
                ex.cause
            )
        }
    }

    fun recycle() {
        waitingDocumentConfigurator = null
        animationManager.stopAll()
        dragPinchManager.disable()

        // Stop tasks
        if (renderingHandler != null) {
            renderingHandler?.stop()
            renderingHandler?.removeMessages(RenderingHandler.MSG_RENDER_PART_TASK)
            renderingHandler?.removeMessages(RenderingHandler.MSG_RENDER_PAGE_TASK)
        }
        //        if (decodingAsyncTask != null) {
        //            decodingAsyncTask.cancel(true);
        //        }

        // Clear caches
        cacheManager.recycle()
        if (scrollHandle != null && isScrollHandleInit) {
            scrollHandle?.destroyLayout()
        }
        if (pdfFile != null) {
            pdfFile?.dispose()
            pdfFile = null
        }
        renderingHandler = null
        scrollHandle = null
        isScrollHandleInit = false
        currentYOffset = 0f
        currentXOffset = currentYOffset
        zoom = 1f
        isRecycled = true
        callbacks = Callbacks()
        state = State.DEFAULT
    }

    /**
     * Handle fling animation
     */
    override fun computeScroll() {
        super.computeScroll()
        if (isInEditMode) {
            return
        }
        animationManager.computeFling()
    }

    override fun onDetachedFromWindow() {
        recycle()
        if (renderingHandlerThread != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                renderingHandlerThread?.quitSafely()
            } else {
                renderingHandlerThread?.quit()
            }
            renderingHandlerThread = null
        }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        hasSize = true
        if (waitingDocumentConfigurator != null) {
            waitingDocumentConfigurator?.load()
        }
        if (isInEditMode || state != State.SHOWN) {
            return
        }

        // calculates the position of the point which in the center of view relative to big strip
        val centerPointInStripXOffset = -currentXOffset + oldw * 0.5f
        val centerPointInStripYOffset = -currentYOffset + oldh * 0.5f
        var relativeCenterPointInStripXOffset: Float = 0f
        var relativeCenterPointInStripYOffset: Float = 0f
        val maxPageWidth = pdfFile?.maxPageWidth ?: 0f
        val docLength = pdfFile?.getDocLen(zoom) ?: 0f
        val maxPageHeight = pdfFile?.maxPageHeight ?: 0f
        if (isSwipeVertical && maxPageWidth != 0f && docLength != 0f) {
            relativeCenterPointInStripXOffset = centerPointInStripXOffset / maxPageWidth
            relativeCenterPointInStripYOffset = centerPointInStripYOffset / docLength
        } else if (docLength != 0f && maxPageHeight != 0f) {
            relativeCenterPointInStripXOffset = centerPointInStripXOffset / docLength
            relativeCenterPointInStripYOffset = centerPointInStripYOffset / maxPageHeight
        }
        animationManager.stopAll()
        pdfFile?.recalculatePageSizes(
            Size(
                w,
                h
            )
        )
        if (isSwipeVertical) {
            currentXOffset = -relativeCenterPointInStripXOffset * maxPageWidth + w * 0.5f
            currentYOffset = -relativeCenterPointInStripYOffset * docLength + h * 0.5f
        } else {
            currentXOffset = -relativeCenterPointInStripXOffset * docLength + w * 0.5f
            currentYOffset = -relativeCenterPointInStripYOffset * maxPageHeight + h * 0.5f
        }
        moveTo(
            currentXOffset,
            currentYOffset
        )
        loadPageByOffset()
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        if (pdfFile == null) {
            return true
        }
        return if (isSwipeVertical) {
            if (direction < 0 && currentXOffset < 0) {
                true
            } else direction > 0 && currentXOffset + toCurrentScale(
                pdfFile?.maxPageWidth ?: 0f
            ) > width
        } else {
            if (direction < 0 && currentXOffset < 0) {
                true
            } else direction > 0 && currentXOffset + (pdfFile?.getDocLen(zoom) ?: 0f) > width
        }
    }

    override fun canScrollVertically(direction: Int): Boolean {
        if (pdfFile == null) {
            return true
        }
        return if (isSwipeVertical) {
            if (direction < 0 && currentYOffset < 0) {
                true
            } else direction > 0 && currentYOffset + (pdfFile?.getDocLen(zoom) ?: 0f) > height
        } else {
            if (direction < 0 && currentYOffset < 0) {
                true
            } else direction > 0 && currentYOffset + toCurrentScale(
                pdfFile?.maxPageHeight ?: 0f
            ) > height
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (isInEditMode) {
            return
        }
        // As I said in this class javadoc, we can think of this canvas as a huge
        // strip on which we draw all the images. We actually only draw the rendered
        // parts, of course, but we render them in the place they belong in this huge
        // strip.

        // That's where Canvas.translate(x, y) becomes very helpful.
        // This is the situation :
        //  _______________________________________________
        // |   			 |					 			   |
        // | the actual  |					The big strip  |
        // |	canvas	 | 								   |
        // |_____________|								   |
        // |_______________________________________________|
        //
        // If the rendered part is on the bottom right corner of the strip
        // we can draw it but we won't see it because the canvas is not big enough.

        // But if we call translate(-X, -Y) on the canvas just before drawing the object :
        //  _______________________________________________
        // |   			  					  _____________|
        // |   The big strip     			 |			   |
        // |		    					 |	the actual |
        // |								 |	canvas	   |
        // |_________________________________|_____________|
        //
        // The object will be on the canvas.
        // This technique is massively used in this method, and allows
        // abstraction of the screen position when rendering the parts.

        // Draws background
        if (enableAntialiasing) {
            canvas.drawFilter = antialiasFilter
        }
        val bg = background
        if (bg == null) {
            canvas.drawColor(if (nightMode) Color.BLACK else Color.WHITE)
        } else {
            bg.draw(canvas)
        }
        if (isRecycled) {
            return
        }
        if (state != State.SHOWN) {
            return
        }

        // Moves the canvas before drawing any element
        val currentXOffset = currentXOffset
        val currentYOffset = currentYOffset
        canvas.translate(
            currentXOffset,
            currentYOffset
        )

        // Draws thumbnails
        for (part in cacheManager.getThumbnails()) {
            drawPart(
                canvas,
                part
            )
        }

        // Draws parts
        for (part in cacheManager.pageParts) {
            drawPart(
                canvas,
                part
            )
            if (callbacks.onDrawAll != null && !onDrawPagesNums.contains(part.page)) {
                onDrawPagesNums.add(part.page)
            }
        }
        for (page in onDrawPagesNums) {
            drawWithListener(
                canvas,
                page,
                callbacks.onDrawAll
            )
        }
        onDrawPagesNums.clear()
        drawWithListener(
            canvas,
            currentPage,
            callbacks.onDraw
        )

        drawTextSelection(canvas)

        // Restores the canvas position
        canvas.translate(
            -currentXOffset,
            -currentYOffset
        )
    }

    private fun drawTextSelection(canvas: Canvas) {
        selectionRects.forEachIndexed { index, rect ->
            endSelectionRect?.selectionRect?.page?.let {
                convertPageRectToDevice(rect, it)?.let { deviceRect ->
                    canvas.drawRect(deviceRect, selectionPaint)
                }
            }
        }

        startSelectionRect?.let { selectionRect ->
            convertPageRectToDevice(selectionRect.selectionRect.rect, selectionRect.selectionRect.page)?.let { deviceRect ->
                val isStart =
                    if (endSelectionRect != null) selectionRect.charIndex < endSelectionRect.required.charIndex else true
                getDrawableWithBounds(deviceRect, isStart)?.let { drawable ->
                    startSelectionRect = selectionRect.copy(
                        iconRect = drawable.bounds
                    )
                    drawable.draw(canvas)
                }
            }
        }

        endSelectionRect?.let { selectionRect ->
            convertPageRectToDevice(selectionRect.selectionRect.rect, selectionRect.selectionRect.page)?.let { deviceRect ->
                val isStart =
                    if (startSelectionRect != null) selectionRect.charIndex < startSelectionRect.required.charIndex else true
                getDrawableWithBounds(deviceRect, isStart)?.let { drawable ->
                    endSelectionRect = selectionRect.copy(
                        iconRect = drawable.bounds
                    )
                    drawable.draw(canvas)
                }
            }
        }
    }

    private fun convertPageRectToDevice(rectF: RectF, page: Int): RectF? {
        val pageSize = pdfFileRequired.getScaledPageSize(
            page,
            zoom
        )
        val pagePoint = getPagePointAtOffsetAndZoom(page)

        val mapped = pdfFileRequired.mapRectToDevice(
            page,
            pagePoint.x,
            pagePoint.y,
            pageSize.width.toInt(),
            pageSize.height.toInt(),
            rectF
        )
        return mapped
    }

    private fun drawWithListener(
        canvas: Canvas,
        page: Int,
        listener: OnDrawListener?
    ) {
        if (listener != null) {
            val translateX: Float
            val translateY: Float
            if (isSwipeVertical) {
                translateX = 0f
                translateY = pdfFile?.getPageOffset(
                    page,
                    zoom
                ) ?: 0f
            } else {
                translateY = 0f
                translateX = pdfFile?.getPageOffset(
                    page,
                    zoom
                ) ?: 0f
            }
            canvas.translate(
                translateX,
                translateY
            )
            val size = pdfFile?.getPageSize(page) ?: SizeF(
                0f,
                0f
            )
            listener.onLayerDrawn(
                canvas,
                toCurrentScale(size.width),
                toCurrentScale(size.height),
                page
            )
            canvas.translate(
                -translateX,
                -translateY
            )
        }
    }

    /**
     * Draw a given PagePart on the canvas
     */
    private fun drawPart(
        canvas: Canvas,
        part: PagePart
    ) {
        // Can seem strange, but avoid lot of calls
        val pageRelativeBounds = part.pageRelativeBounds
        val renderedBitmap = part.renderedBitmap
        if (renderedBitmap != null && renderedBitmap.isRecycled) {
            return
        }

        // Move to the target page
        val localTranslationX: Float
        val localTranslationY: Float
        val size = pdfFile?.getPageSize(part.page) ?: SizeF(
            0f,
            0f
        )
        if (isSwipeVertical) {
            localTranslationY = pdfFile?.getPageOffset(
                part.page,
                zoom
            ) ?: 0f
            val maxWidth = pdfFile?.maxPageWidth ?: 0f
            localTranslationX = toCurrentScale(maxWidth - size.width) / 2
        } else {
            localTranslationX = pdfFile?.getPageOffset(
                part.page,
                zoom
            ) ?: 0f
            val maxHeight = pdfFile?.maxPageHeight ?: 0f
            localTranslationY = toCurrentScale(maxHeight - size.height) / 2
        }
        canvas.translate(
            localTranslationX,
            localTranslationY
        )
        var srcRect: Rect? = null
        if (renderedBitmap != null) {
            srcRect = Rect(
                0,
                0,
                renderedBitmap.width,
                renderedBitmap.height
            )
        }
        val offsetX = toCurrentScale(pageRelativeBounds.left * size.width).toInt()
        val offsetY = toCurrentScale(pageRelativeBounds.top * size.height).toInt()
        val width = toCurrentScale(pageRelativeBounds.width() * size.width).toInt()
        val height = toCurrentScale(pageRelativeBounds.height() * size.height).toInt()

        // If we use float values for this rectangle, there will be
        // a possible gap between page parts, especially when
        // the zoom level is high.
        val dstRect = RectF(
            offsetX.toFloat(),
            offsetY.toFloat(),
            (offsetX + width).toFloat(),
            (offsetY + height).toFloat()
        )

        // Check if bitmap is in the screen
        val translationX = currentXOffset + localTranslationX
        val translationY = currentYOffset + localTranslationY
        if (translationX + dstRect.left >= getWidth() || translationX + dstRect.right <= 0 || translationY + dstRect.top >= getHeight() || translationY + dstRect.bottom <= 0) {
            canvas.translate(
                -localTranslationX,
                -localTranslationY
            )
            return
        }
        canvas.drawBitmap(
            renderedBitmap!!,
            srcRect,
            dstRect,
            paint
        )
        if (Constants.DEBUG_MODE) {
            debugPaint.color = if (part.page % 2 == 0) Color.RED else Color.BLUE
            canvas.drawRect(
                dstRect,
                debugPaint
            )
        }

        // Restore the canvas position
        canvas.translate(
            -localTranslationX,
            -localTranslationY
        )
    }

    /**
     * Load all the parts around the center of the screen,
     * taking into account X and Y offsets, zoom level, and
     * the current page displayed
     */
    fun loadPages() {
        if (pdfFile == null || renderingHandler == null) {
            return
        }

        // Cancel all current tasks
        renderingHandler?.removeMessages(RenderingHandler.MSG_RENDER_PART_TASK)
        cacheManager.makeANewSet()
        pagesLoader.loadPages(searchQuery)
        redraw()
    }

    interface OnTextParseListener {
        fun onTextParseSuccess(
            pageIndex: Int,
            text: String
        )

        fun onTextParseError(pageIndex: Int)
    }

    private var parseListener: OnTextParseListener? = null
    fun parseText(
        pagesIndexes: List<Int>,
        listener: OnTextParseListener
    ) {
        if (pdfFile == null) {
            return
        }
        if (renderingHandler == null) {
            return
        }
        parseListener = listener

        // Cancel all current tasks
        renderingHandler?.removeMessages(RenderingHandler.MSG_PARSE_TEXT_TASK)
        pagesLoader.parseText(pagesIndexes)
        redraw()
    }

    /**
     * Called when the PDF is loaded
     */
    fun loadComplete(pdfFile: PdfFile?) {
        state = State.LOADED
        this.pdfFile = pdfFile
        if (renderingHandlerThread?.isAlive != true) {
            renderingHandlerThread?.start()
        }
        renderingHandler = renderingHandlerThread?.looper?.let {
            RenderingHandler(
                it,
                this
            )
        }
        renderingHandler?.start()
        if (scrollHandle != null) {
            scrollHandle?.setupLayout(this)
            isScrollHandleInit = true
        }
        dragPinchManager.enable()
        jumpTo(
            defaultPage,
            false
        )
        callbacks.callOnLoadComplete(pdfFile?.pagesCount ?: 0)
    }

    fun loadError(t: Throwable?) {
        state = State.ERROR
        // store reference, because callbacks will be cleared in recycle() method
        val onErrorListener = callbacks.onError
        recycle()
        invalidate()
        onErrorListener?.onError(t) ?: Log.e(
            "PDFView",
            "load pdf error",
            t
        )
    }

    fun redraw() {
        invalidate()
    }

    /**
     * Called when a rendering task is over and
     * a PagePart has been freshly created.
     *
     * @param part The created PagePart.
     */
    fun onBitmapRendered(part: PagePart) {
        // when it is first rendered part
        if (state == State.LOADED) {
            state = State.SHOWN
            callbacks.callOnRender(pdfFile?.pagesCount ?: 0)
        }
        if (part.isThumbnail) {
            cacheManager.cacheThumbnail(part)
        } else {
            cacheManager.cachePart(part)
        }
        redraw()
    }

    fun onTextParsed(
        pageIndex: Int,
        text: String?
    ) {
        if (text == null) {
            Log.e(
                "PDFView",
                "Page " + (pageIndex + 1) + ". CharCount: NULL"
            )
            parseListener?.onTextParseError(pageIndex)
        } else {
            Log.e(
                "PDFView",
                "Page " + (pageIndex + 1) + ". CharCount: " + text.length
            )
            parseListener?.onTextParseSuccess(
                pageIndex,
                text
            )
        }
    }

    interface PageRenderingListener {
        fun onPageRendered(
            pageIndex: Int,
            bitmap: Bitmap?
        )
    }

    var pageRenderingListener: PageRenderingListener? = null

    internal fun onPageRendered(
        pageIndex: Int,
        bitmap: Bitmap?
    ) {
        pageRenderingListener?.onPageRendered(
            pageIndex = pageIndex,
            bitmap = bitmap
        )
    }

    fun renderPageBitmap(
        pageIndex: Int,
        isThumbnail: Boolean = false,
        size: SizeF = pdfFile?.getPageSize(pageIndex) ?: SizeF(
            0f,
            0f
        )
    ) {
        if (pdfFile == null) {
            return
        }
        if (renderingHandler == null) {
            return
        }

        // Cancel all current tasks
//        renderingHandler?.removeMessages(RenderingHandler.MSG_RENDER_PAGE_TASK)
        pagesLoader.renderPage(page = pageIndex, isThumbnail = isThumbnail, size = size)
        redraw()
    }

    /**
     * Move to the given X and Y offsets, but check them ahead of time
     * to be sure not to go outside the the big strip.
     *
     * @param offsetX    The big strip X offset to use as the left border of the screen.
     * @param offsetY    The big strip Y offset to use as the right border of the screen.
     * @param moveHandle whether to move scroll handle or not
     */
    @JvmOverloads
    fun moveTo(
        offsetX: Float,
        offsetY: Float,
        moveHandle: Boolean = true
    ) {
        var offX = offsetX
        var offY = offsetY
        var scrollDir = ScrollDir.NONE
        if (isSwipeVertical) {
            // Check X offset
            val scaledPageWidth = toCurrentScale(pdfFile?.maxPageWidth ?: 0f)
            if (scaledPageWidth < width) {
                offX = width.toFloat() / 2f - scaledPageWidth / 2f
            } else {
                if (offX > 0) {
                    offX = 0f
                } else if (offX + scaledPageWidth < width) {
                    offX = width - scaledPageWidth
                }
            }

            // Check Y offset
            val contentHeight = pdfFile?.getDocLen(zoom) ?: 0f
            if (contentHeight < height) { // whole document height visible on screen
                offY = (height - contentHeight) / 2
            } else {
                if (offY > 0) { // top visible
                    offY = 0f
                } else if (offY + contentHeight < height) { // bottom visible
                    offY = -contentHeight + height
                }
            }
            scrollDir = if (offY < currentYOffset) {
                ScrollDir.END
            } else if (offY > currentYOffset) {
                ScrollDir.START
            } else {
                ScrollDir.NONE
            }
        } else {
            // Check Y offset
            val scaledPageHeight = toCurrentScale(pdfFile?.maxPageHeight ?: 0f)
            if (scaledPageHeight < height) {
                offY = height.toFloat() / 2f - scaledPageHeight / 2f
            } else {
                if (offY > 0) {
                    offY = 0f
                } else if (offY + scaledPageHeight < height) {
                    offY = height - scaledPageHeight
                }
            }

            // Check X offset
            val contentWidth = pdfFile?.getDocLen(zoom) ?: 0f
            if (contentWidth < width) { // whole document width visible on screen
                offX = (width - contentWidth) / 2
            } else {
                if (offX > 0) { // left visible
                    offX = 0f
                } else if (offX + contentWidth < width) { // right visible
                    offX = -contentWidth + width
                }
            }
            scrollDir = when {
                offX < currentXOffset -> {
                    ScrollDir.END
                }

                offX > currentXOffset -> {
                    ScrollDir.START
                }

                else -> {
                    ScrollDir.NONE
                }
            }
        }
        currentXOffset = offX
        currentYOffset = offY
        val positionOffset = getPositionOffset()
        if (moveHandle && scrollHandle != null && !documentFitsView()) {
            scrollHandle?.setScroll(positionOffset)
        }
        callbacks.callOnPageScroll(
            currentPage,
            positionOffset
        )
        redraw()
    }

    fun loadPageByOffset() {
        if (0 == pdfFile?.pagesCount) {
            return
        }
        val offset: Float
        val screenCenter: Float
        if (isSwipeVertical) {
            offset = currentYOffset
            screenCenter = height.toFloat() / 2
        } else {
            offset = currentXOffset
            screenCenter = width.toFloat() / 2
        }
        val page = pdfFile?.getPageAtOffset(
            -(offset - screenCenter),
            zoom
        ) ?: 0
        if (page >= 0 && page <= (pdfFile?.pagesCount ?: 0) - 1 && page != currentPage) {
            showPage(page)
        } else {
            loadPages()
        }
    }

    /**
     * Animate to the nearest snapping position for the current SnapPolicy
     */
    fun performPageSnap() {
        if (!isPageSnap || pdfFile == null || pdfFile?.pagesCount == 0) {
            return
        }
        val centerPage = findFocusPage(
            currentXOffset,
            currentYOffset
        )
        val edge = findSnapEdge(centerPage)
        if (edge === SnapEdge.NONE) {
            return
        }
        val offset = snapOffsetForPage(
            centerPage,
            edge
        )
        if (isSwipeVertical) {
            animationManager.startYAnimation(
                currentYOffset,
                -offset
            )
        } else {
            animationManager.startXAnimation(
                currentXOffset,
                -offset
            )
        }
    }

    /**
     * Find the edge to snap to when showing the specified page
     */
    fun findSnapEdge(page: Int): SnapEdge {
        if (!isPageSnap || page < 0) {
            return SnapEdge.NONE
        }
        val currentOffset = if (isSwipeVertical) currentYOffset else currentXOffset
        val offset = -(pdfFile?.getPageOffset(
            page,
            zoom
        ) ?: 0f)
        val length = if (isSwipeVertical) height else width
        val pageLength = pdfFile?.getPageLength(
            page,
            zoom
        ) ?: 0f
        return when {
            length >= pageLength -> {
                SnapEdge.CENTER
            }

            currentOffset >= offset -> {
                SnapEdge.START
            }

            offset - pageLength > currentOffset - length -> {
                SnapEdge.END
            }

            else -> {
                SnapEdge.NONE
            }
        }
    }

    /**
     * Get the offset to move to in order to snap to the page
     */
    fun snapOffsetForPage(
        pageIndex: Int,
        edge: SnapEdge
    ): Float {
        var offset = pdfFile?.getPageOffset(
            pageIndex,
            zoom
        ) ?: 0f
        val length = if (isSwipeVertical) height.toFloat() else width.toFloat()
        val pageLength = pdfFile?.getPageLength(
            pageIndex,
            zoom
        ) ?: 0f
        if (edge === SnapEdge.CENTER) {
            offset = offset - length / 2f + pageLength / 2f
        } else if (edge === SnapEdge.END) {
            offset = offset - length + pageLength
        }
        return offset
    }

    fun findFocusPage(
        xOffset: Float,
        yOffset: Float
    ): Int {
        val currOffset = if (isSwipeVertical) yOffset else xOffset
        val length = if (isSwipeVertical) height.toFloat() else width.toFloat()
        // make sure first and last page can be found
        val pagesCount = pdfFile?.pagesCount ?: 0
        if (currOffset > -1) {
            return 0
        } else if (currOffset < -(pdfFile?.getDocLen(zoom) ?: 0f) + length + 1) {
            return pagesCount - 1
        }
        // else find page in center
        val center = currOffset - length / 2f
        return pdfFile?.getPageAtOffset(
            -center,
            zoom
        ) ?: 0
    }

    /**
     * @return true if single page fills the entire screen in the scrolling direction
     */
    fun pageFillsScreen(): Boolean {
        val start = -(pdfFile?.getPageOffset(
            currentPage,
            zoom
        ) ?: 0f)
        val end = start - (pdfFile?.getPageLength(
            currentPage,
            zoom
        ) ?: 0f)
        return if (isSwipeVertical) {
            start > currentYOffset && end < currentYOffset - height
        } else {
            start > currentXOffset && end < currentXOffset - width
        }
    }

    /**
     * Move relatively to the current position.
     *
     * @param dx The X difference you want to apply.
     * @param dy The Y difference you want to apply.
     * @see .moveTo
     */
    fun moveRelativeTo(
        dx: Float,
        dy: Float
    ) {
        moveTo(
            currentXOffset + dx,
            currentYOffset + dy
        )
    }

    /**
     * Change the zoom level
     */
    fun zoomTo(zoom: Float) {
        this.zoom = zoom
    }

    /**
     * Change the zoom level, relatively to a pivot point.
     * It will call moveTo() to make sure the given point stays
     * in the middle of the screen.
     *
     * @param zoom  The zoom level.
     * @param pivot The point on the screen that should stays.
     */
    fun zoomCenteredTo(
        zoom: Float,
        pivot: PointF
    ) {
        val dzoom = zoom / this.zoom
        zoomTo(zoom)
        var baseX = currentXOffset * dzoom
        var baseY = currentYOffset * dzoom
        baseX += pivot.x - pivot.x * dzoom
        baseY += pivot.y - pivot.y * dzoom
        moveTo(
            baseX,
            baseY
        )
    }

    /**
     * @see .zoomCenteredTo
     */
    fun zoomCenteredRelativeTo(
        dzoom: Float,
        pivot: PointF
    ) {
        zoomCenteredTo(
            zoom * dzoom,
            pivot
        )
    }

    /**
     * Checks if whole document can be displayed on screen, doesn't include zoom
     *
     * @return true if whole document can displayed at once, false otherwise
     */
    fun documentFitsView(): Boolean {
        val len = pdfFile?.getDocLen(1f) ?: 0f
        return if (isSwipeVertical) {
            len < height
        } else {
            len < width
        }
    }

    fun fitToWidth(page: Int) {
        if (state != State.SHOWN) {
            Log.e(
                TAG,
                "Cannot fit, document not rendered yet"
            )
            return
        }
        val w = pdfFile?.getPageSize(page)?.width ?: 0f
        if (w != 0f) {
            zoomTo(width / w)
            jumpTo(page)
        }
    }

    fun getPageSize(pageIndex: Int): SizeF {
        return pdfFile?.getPageSize(pageIndex) ?: SizeF(
            0f,
            0f
        )
    }

    fun overrideColorScheme(colorScheme: ColorScheme?) {
        this.colorScheme = colorScheme
        cacheManager.recycle()
        loadPages()
    }

    fun isColorSchemeOverridden(): Boolean {
        return colorScheme != null
    }

    fun toRealScale(size: Float): Float {
        return size / zoom
    }

    fun toCurrentScale(size: Float): Float {
        return size * zoom
    }

    fun isZooming(): Boolean {
        return zoom != minZoom
    }

    private fun setDefaultPage(defaultPage: Int) {
        this.defaultPage = defaultPage
    }

    fun resetZoom() {
        zoomTo(minZoom)
    }

    fun resetZoomWithAnimation() {
        zoomWithAnimation(minZoom)
    }

    fun zoomWithAnimation(
        centerX: Float,
        centerY: Float,
        scale: Float
    ) {
        animationManager.startZoomAnimation(
            centerX,
            centerY,
            zoom,
            scale
        )
    }

    fun zoomWithAnimation(scale: Float) {
        animationManager.startZoomAnimation(
            width.toFloat() / 2f,
            height.toFloat() / 2f,
            zoom,
            scale
        )
    }

    /**
     * Get page number at given offset
     *
     * @param positionOffset scroll offset between 0 and 1
     * @return page number at given offset, starting from 0
     */
    fun getPageAtPositionOffset(positionOffset: Float): Int {
        return pdfFile?.getPageAtOffset(
            (pdfFile?.getDocLen(zoom) ?: 0f) * positionOffset,
            zoom
        ) ?: 0
    }

    fun useBestQuality(bestQuality: Boolean) {
        isBestQuality = bestQuality
    }

    fun isSwipeEnabled(): Boolean {
        return enableSwipe
    }

    fun enableAnnotationRendering(annotationRendering: Boolean) {
        isAnnotationRendering = annotationRendering
    }

    fun enableRenderDuringScale(renderDuringScale: Boolean) {
        this.renderDuringScale = renderDuringScale
    }

    fun isAntialiasing(): Boolean {
        return enableAntialiasing
    }

    fun enableAntialiasing(enableAntialiasing: Boolean) {
        this.enableAntialiasing = enableAntialiasing
    }

    fun isAutoSpacingEnabled(): Boolean {
        return autoSpacing
    }

    fun setPageFling(pageFling: Boolean) {
        this.pageFling = pageFling
    }

    fun isPageFlingEnabled(): Boolean {
        return pageFling
    }

    private fun setSpacing(spacingDp: Int) {
        spacingPx = getDP(
            context,
            spacingDp
        )
    }

    private fun setAutoSpacing(autoSpacing: Boolean) {
        this.autoSpacing = autoSpacing
    }

    fun doRenderDuringScale(): Boolean {
        return renderDuringScale
    }

    /**
     * Returns null if document is not loaded
     */
    fun getDocumentMeta(): Meta? {
        return if (pdfFile == null) {
            null
        } else pdfFile?.getMetaData()
    }

    /**
     * Will be empty until document is loaded
     */
    fun getTableOfContents(): List<Bookmark> {
        return pdfFile?.getBookmarks() ?: emptyList()
    }

    /**
     * Will be empty until document is loaded
     */
    fun getLinks(page: Int): List<Link> {
        return pdfFile?.getPageLinks(page) ?: emptyList()
    }

    /**
     * Use a file as the pdf source
     */
    val documentSourceReadFlow = MutableSharedFlow<Unit>()

    fun fromRandomAccessReader(mediaRandomAccessReaderDataSource: MediaRandomAccessReaderDataSource): Configurator {
        return Configurator(RandomAccessReaderSource(mediaRandomAccessReaderDataSource, documentSourceReadFlow))
    }

    /**
     * Use custom source as pdf source
     */
    fun fromSource(docSource: DocumentSource): Configurator {
        return Configurator(docSource)
    }

    private enum class State {
        DEFAULT,
        LOADED,
        SHOWN,
        ERROR
    }

    inner class Configurator(private val documentSource: DocumentSource) {
        private var pageNumbers: IntArray? = null
        private var enableSwipe = true
        private var enableDoubletap = true
        private var onDrawListener: OnDrawListener? = null
        private var onDrawAllListener: OnDrawListener? = null
        private var onLoadStartListener: OnLoadStartListener? = null
        private var onLoadCompleteListener: OnLoadCompleteListener? = null
        private var onErrorListener: OnErrorListener? = null
        private var onPageChangeListener: OnPageChangeListener? = null
        private var onPageScrollListener: OnPageScrollListener? = null
        private var onRenderListener: OnRenderListener? = null
        private var onTapListener: OnTapListener? = null
        private var onTextSelectListener: OnTextSelectListener? = null
        private var onTextSearchListener: OnTextSearchListener? = null
        private var onLongPressListener: OnLongPressListener? = null
        private var onPageErrorListener: OnPageErrorListener? = null
        private var linkHandler: LinkHandler = DefaultLinkHandler(
            this@PDFView,
            "Select app for open link"
        )
        private var defaultPage = 0
        private var swipeHorizontal = false
        private var annotationRendering = false
        private var password: String? = null
        private var scrollHandle: ScrollHandle? = null
        private var antialiasing = true
        private var spacing = 0
        private var autoSpacing = false
        private var pageFitPolicy = FitPolicy.WIDTH
        private var fitEachPage = false
        private var pageFling = false
        private var pageSnap = false
        private var nightMode = false
        fun pages(vararg pageNumbers: Int): Configurator {
            this.pageNumbers = pageNumbers
            return this
        }

        fun enableSwipe(enableSwipe: Boolean): Configurator {
            this.enableSwipe = enableSwipe
            return this
        }

        fun enableDoubletap(enableDoubletap: Boolean): Configurator {
            this.enableDoubletap = enableDoubletap
            return this
        }

        fun enableAnnotationRendering(annotationRendering: Boolean): Configurator {
            this.annotationRendering = annotationRendering
            return this
        }

        fun onDraw(onDrawListener: OnDrawListener?): Configurator {
            this.onDrawListener = onDrawListener
            return this
        }

        fun onDrawAll(onDrawAllListener: OnDrawListener?): Configurator {
            this.onDrawAllListener = onDrawAllListener
            return this
        }

        fun onLoad(onLoadCompleteListener: OnLoadCompleteListener?): Configurator {
            this.onLoadCompleteListener = onLoadCompleteListener
            return this
        }

        fun onLoadStart(onLoadStartListener: OnLoadStartListener?): Configurator {
            this.onLoadStartListener = onLoadStartListener
            return this
        }

        fun onPageScroll(onPageScrollListener: OnPageScrollListener?): Configurator {
            this.onPageScrollListener = onPageScrollListener
            return this
        }

        fun onError(onErrorListener: OnErrorListener?): Configurator {
            this.onErrorListener = onErrorListener
            return this
        }

        fun onPageError(onPageErrorListener: OnPageErrorListener?): Configurator {
            this.onPageErrorListener = onPageErrorListener
            return this
        }

        fun onPageChange(onPageChangeListener: OnPageChangeListener?): Configurator {
            this.onPageChangeListener = onPageChangeListener
            return this
        }

        fun onPageBitmapRendered(onPageBitmapRenderingListener: PageRenderingListener): Configurator {
            this@PDFView.pageRenderingListener = onPageBitmapRenderingListener
            return this
        }

        fun onRender(onRenderListener: OnRenderListener?): Configurator {
            this.onRenderListener = onRenderListener
            return this
        }

        fun onTap(onTapListener: OnTapListener?): Configurator {
            this.onTapListener = onTapListener
            return this
        }

        fun onTextSelect(onTextSelectListener: OnTextSelectListener): Configurator {
            this.onTextSelectListener = onTextSelectListener
            return this
        }

        fun onTextSearch(onTextSearchListener: OnTextSearchListener): Configurator {
            this.onTextSearchListener = onTextSearchListener
            return this
        }

        fun onLongPress(onLongPressListener: OnLongPressListener?): Configurator {
            this.onLongPressListener = onLongPressListener
            return this
        }

        fun linkHandler(linkHandler: LinkHandler): Configurator {
            this.linkHandler = linkHandler
            return this
        }

        fun defaultPage(defaultPage: Int): Configurator {
            this.defaultPage = defaultPage
            return this
        }

        fun swipeHorizontal(swipeHorizontal: Boolean): Configurator {
            this.swipeHorizontal = swipeHorizontal
            return this
        }

        fun password(password: String?): Configurator {
            this.password = password
            return this
        }

        fun scrollHandle(scrollHandle: ScrollHandle?): Configurator {
            this.scrollHandle = scrollHandle
            return this
        }

        fun enableAntialiasing(antialiasing: Boolean): Configurator {
            this.antialiasing = antialiasing
            return this
        }

        /**
         * Set spacing in DP
         */
        fun spacing(spacing: Int): Configurator {
            this.spacing = spacing
            return this
        }

        fun autoSpacing(autoSpacing: Boolean): Configurator {
            this.autoSpacing = autoSpacing
            return this
        }

        fun pageFitPolicy(pageFitPolicy: FitPolicy): Configurator {
            this.pageFitPolicy = pageFitPolicy
            return this
        }

        fun fitEachPage(fitEachPage: Boolean): Configurator {
            this.fitEachPage = fitEachPage
            return this
        }

        fun pageSnap(pageSnap: Boolean): Configurator {
            this.pageSnap = pageSnap
            return this
        }

        fun pageFling(pageFling: Boolean): Configurator {
            this.pageFling = pageFling
            return this
        }

        fun nightMode(nightMode: Boolean): Configurator {
            this.nightMode = nightMode
            return this
        }

        fun disableLongpress(): Configurator {
            dragPinchManager.disableLongPress()
            return this
        }

        fun overrideColorScheme(colorScheme: ColorScheme?): Configurator {
            this@PDFView.colorScheme = colorScheme
            return this
        }

        fun load() {
            if (!hasSize) {
                waitingDocumentConfigurator = this
                return
            }
            recycle()
            callbacks.setOnLoadComplete(onLoadCompleteListener)
            callbacks.setOnLoadStart(onLoadStartListener)
            callbacks.onError = onErrorListener
            callbacks.onDraw = onDrawListener
            callbacks.onDrawAll = onDrawAllListener
            callbacks.setOnPageChange(onPageChangeListener)
            callbacks.setOnPageScroll(onPageScrollListener)
            callbacks.setOnRender(onRenderListener)
            callbacks.setOnTap(onTapListener)
            callbacks.setOnTextSelect(onTextSelectListener)
            callbacks.setOnTextSearch(onTextSearchListener)
            callbacks.setOnLongPress(onLongPressListener)
            callbacks.setOnPageError(onPageErrorListener)
            callbacks.setLinkHandler(linkHandler)
            setSwipeEnabled(enableSwipe)
            setNightMode(nightMode)
            this@PDFView.enableDoubletap(enableDoubletap)
            setDefaultPage(defaultPage)
            isSwipeVertical = !swipeHorizontal
            this@PDFView.enableAnnotationRendering(annotationRendering)
            this@PDFView.scrollHandle = scrollHandle
            this@PDFView.enableAntialiasing(antialiasing)
            setSpacing(spacing)
            setAutoSpacing(autoSpacing)
            this@PDFView.pageFitPolicy = pageFitPolicy
            isFitEachPage = fitEachPage
            isPageSnap = pageSnap
            setPageFling(pageFling)
            if (pageNumbers != null) {
                this@PDFView.load(
                    documentSource,
                    password,
                    pageNumbers
                )
            } else {
                this@PDFView.load(
                    documentSource,
                    password
                )
            }
        }
    }

    suspend fun clearSearch() {
        pdfFile?.clearSearchHighlight()
        updatePagesWithSearch("")
    }

    suspend fun highlightCurrentSearchIndex(currentIndex: Int, currentRect: RectF) {
        pdfFile?.highlightCurrentSearchIndex(currentIndex, currentRect)

        updatePagesWithSearch("$currentIndex")
    }

    private fun getSearchRectVisibilityMode(deviceSearchRect: RectF, isForward: Boolean): SearchRectVisibilityMode {
        val searchRectVisibilityMode =
            if (isForward) {
                when {
                    (deviceSearchRect.left >= 0 && deviceSearchRect.left < width && deviceSearchRect.top >= 0 && deviceSearchRect.top < height) ->
                        SearchRectVisibilityMode.Visible

                    (deviceSearchRect.top > height || deviceSearchRect.left < 0 || deviceSearchRect.right > width) ->
                        SearchRectVisibilityMode.VisibleWithScroll

                    else -> SearchRectVisibilityMode.Invisible
                }
            } else {
                when {
                    (deviceSearchRect.left >= 0 && deviceSearchRect.left < width && deviceSearchRect.top >= 0 && deviceSearchRect.top < height) ->
                        SearchRectVisibilityMode.Visible

                    (deviceSearchRect.top < 0 || deviceSearchRect.left < 0 || deviceSearchRect.right > width) ->
                        SearchRectVisibilityMode.VisibleWithScroll

                    else -> SearchRectVisibilityMode.Invisible
                }
            }

        return searchRectVisibilityMode
    }

    private fun convertPageSearchRectToDevice(pageSearchRect: SearchRect): RectF? {
        val pageSize = pdfFileRequired.getScaledPageSize(
            pageSearchRect.page,
            zoom
        )

        val pagePointAtOffsetAndZoom = getPagePointAtOffsetAndZoom(pageSearchRect.page)

        val hasPage = pdfFileRequired.hasPage(pageSearchRect.page)
        if (!hasPage) {
            pdfFileRequired.openPage(pageSearchRect.page)
        }

        val mappedRect = pdfFileRequired.mapRectToDevice(
            pageSearchRect.page,
            pagePointAtOffsetAndZoom.x,
            pagePointAtOffsetAndZoom.y,
            pageSize.width.toInt(),
            pageSize.height.toInt(),
            pageSearchRect.rect
        )

        val mappedRectWithOffset = mappedRect?.let {
            RectF(
                it.left + currentXOffset,
                it.top + currentYOffset,
                it.right + currentXOffset,
                it.bottom + currentYOffset
            )
        }

        return mappedRectWithOffset
    }

    fun getCurrentSearchRectWithVisibility(inputIndex: Int, isForward: Boolean): SearchRectWithVisibility? {
        val flattenSearchResults = pdfFile?.getFlattenSearchResults() ?: emptyList()
        val isItemWithIndexVisible = isItemWithIndexVisible(inputIndex, flattenSearchResults)

        val currentIndex = getCurrentIndex(isItemWithIndexVisible, isForward, inputIndex, flattenSearchResults)

        var searchRectWithVisibility: SearchRectWithVisibility? = null

        if (currentIndex != -1) {
            if (isForward) {
                for (i in currentIndex until flattenSearchResults.size) {
                    val currentSearchRectWithVisibility = getSearchRectWithVisibility(flattenSearchResults, i, true)
                    if (currentSearchRectWithVisibility != null) {
                        searchRectWithVisibility = currentSearchRectWithVisibility
                        break
                    }
                }
            } else {
                for (i in currentIndex downTo 0) {
                    val currentSearchRectWithVisibility = getSearchRectWithVisibility(flattenSearchResults, i, false)
                    if (currentSearchRectWithVisibility != null) {
                        searchRectWithVisibility = currentSearchRectWithVisibility
                        break
                    }
                }
            }
        }

        return searchRectWithVisibility
    }

    private fun getSearchRectWithVisibility(
        flattenSearchResults: List<SearchRect>,
        index: Int,
        isForward: Boolean
    ): SearchRectWithVisibility? {
        val searchResult = flattenSearchResults.getOrNull(index)
        val currentSearchRectWithVisibility = searchResult?.let { searchRect ->
            val deviceSearchRect = convertPageSearchRectToDevice(searchRect)
            deviceSearchRect?.let {
                val visibilityMode = getSearchRectVisibilityMode(it, isForward)
                if (visibilityMode == SearchRectVisibilityMode.Visible || visibilityMode == SearchRectVisibilityMode.VisibleWithScroll) {
                    SearchRectWithVisibility(
                        searchRect,
                        index,
                        it,
                        visibilityMode
                    )
                } else null
            }
        }
        return currentSearchRectWithVisibility
    }

    private fun getCurrentIndex(
        isItemWithIndexVisible: Boolean,
        isForward: Boolean,
        inputIndex: Int,
        flattenSearchResults: List<SearchRect>
    ): Int {
        val currentIndex = if (isItemWithIndexVisible) {
            if (isForward) {
                val index = inputIndex + 1
                if (index < 0) 0 else if (index < flattenSearchResults.size) index else flattenSearchResults.size - 1
            } else {
                val index = inputIndex - 1
                if (index < 0) 0 else if (index < flattenSearchResults.size) index else flattenSearchResults.size - 1
            }
        } else {
            var resCurrentIndex: Int = -1
            if (isForward) {
                for (i in flattenSearchResults.indices) {
                    val currentIndex = getVisibleCurrentIndex(flattenSearchResults, i, true)

                    if (currentIndex != null) {
                        resCurrentIndex = currentIndex
                        break
                    }
                }
            } else {
                for (i in flattenSearchResults.size - 1 downTo 0) {
                    val currentIndex = getVisibleCurrentIndex(flattenSearchResults, i, false)

                    if (currentIndex != null) {
                        resCurrentIndex = currentIndex
                        break
                    }
                }
            }
            resCurrentIndex
        }
        return currentIndex
    }

    private fun getVisibleCurrentIndex(
        flattenSearchResults: List<SearchRect>,
        index: Int,
        isForward: Boolean
    ): Int? {
        val searchResult = flattenSearchResults.getOrNull(index)
        val currentIndex = searchResult?.let { searchRect ->
            val deviceSearchRect = convertPageSearchRectToDevice(searchRect)
            deviceSearchRect?.let {
                val visibilityMode = getSearchRectVisibilityMode(it, isForward)
                if (visibilityMode == SearchRectVisibilityMode.Visible || visibilityMode == SearchRectVisibilityMode.VisibleWithScroll) {
                    index
                } else null
            }
        }
        return currentIndex
    }

    private fun isItemWithIndexVisible(
        inputIndex: Int,
        flattenSearchResults: List<SearchRect>
    ): Boolean {
        val isItemWithIndexVisible =
            if (inputIndex == -1) false
            else {
                val searchResult = flattenSearchResults.getOrNull(inputIndex)
                searchResult?.let { searchRect ->
                    val deviceSearchRect = convertPageSearchRectToDevice(searchRect)
                    deviceSearchRect?.let {
                        val visibilityMode = getSearchRectVisibilityMode(it, true)
                        visibilityMode == SearchRectVisibilityMode.Visible
                    }
                } ?: false
            }
        return isItemWithIndexVisible
    }

    fun getCurrentSearchRectByIndex(index: Int): SearchRect? {
        val flattenSearchResults = pdfFile?.getFlattenSearchResults() ?: emptyList()
        return flattenSearchResults.getOrNull(index)
    }

    fun selectTextWithIndexes(page: Int, startIndex: Int, endIndex: Int) {
        val startCharacterBox = pdfFileRequired.measureCharacterBox(page, startIndex)
        val endCharacterBox = pdfFileRequired.measureCharacterBox(page, endIndex)

        if (startCharacterBox != null && endCharacterBox != null) {
            startSelectionRect = SelectionRectWithIndex(SelectionRect(page, startCharacterBox), startIndex, null)
            endSelectionRect = SelectionRectWithIndex(SelectionRect(page, endCharacterBox), endIndex, null)

            selectionRects = getSelectionRects(page)
            callbacks.callOnTextSelect(selectionRects.isNotEmpty())
        }
        redraw()
    }

    fun getSelectionRects(page: Int): List<RectF> {
        return if (startSelectionRect != null && endSelectionRect != null) {
            val minIndex = min(startSelectionRect.required.charIndex, endSelectionRect.required.charIndex)
            val maxIndex = max(startSelectionRect.required.charIndex, endSelectionRect.required.charIndex)

            val count = maxIndex - minIndex + 1

            pdfFileRequired.getCountRects(page, minIndex, count)?.let { rectsCount ->
                val selectionRects = mutableListOf<RectF>()
                for (i in 0 until rectsCount) {
                    val currentRect = pdfFileRequired.getTextRect(page, i)
                    currentRect?.let {
                        selectionRects.add(it)
                    }
                }

                selectionRects
            } ?: emptyList()
        } else emptyList()
    }

    private fun getDrawableWithBounds(rectF: RectF, isStart: Boolean): Drawable? {
        return ContextCompat.getDrawable(context.required, org.benjinus.pdfium.R.drawable.ic_text_select)?.let { drawable ->
            val startX = if (isStart) rectF.left.toInt() else rectF.right.toInt()
            val startY = rectF.bottom.toInt()

            drawable.setBounds(
                startX - (drawable.intrinsicWidth * drawableWidthCoeff).toInt(),
                startY - (drawable.intrinsicHeight * drawableHeightStartCoeff).toInt(),
                startX + (drawable.intrinsicWidth * drawableWidthCoeff).toInt(),
                startY + (drawable.intrinsicHeight * drawableHeightEndCoeff).toInt()
            )
            drawable
        }
    }

    fun getSelectedText(): String? {
        return startSelectionRect?.let { startSelectionRect ->
            endSelectionRect?.let { endSelectionRect ->
                val page = startSelectionRect.selectionRect.page
                val startIndex = min(startSelectionRect.charIndex, endSelectionRect.charIndex)
                val endIndex = max(startSelectionRect.charIndex, endSelectionRect.charIndex)
                val countIndexes = endIndex - startIndex + 1

                pdfFile?.extractCharacters(page, startIndex, countIndexes)
            }
        }
    }

    suspend fun searchAndHighlight(
        searchQuery: String,
        matchCase: Boolean,
        matchWholeWord: Boolean,
        loadingState: LoadingState
    ) {
        loadingState {
            pdfFile?.searchAndHighlight(
                searchQuery = searchQuery,
                matchCase = matchCase,
                matchWholeWord = matchWholeWord
            )
        }
        updatePagesWithSearch(searchQuery)
        callbacks.callOnTextSearch(true)
    }

    private fun getPagePointAtOffsetAndZoom(pageAtOffset: Int): Point {
        return if (isSwipeVertical) {
            Point(
                /* x = */ pdfFileRequired.getSecondaryPageOffset(
                    pageAtOffset,
                    zoom
                ).toInt(),

                /* y = */ pdfFileRequired.getPageOffset(
                    pageAtOffset,
                    zoom
                ).toInt()
            )
        } else {
            Point(
                /* x = */
                pdfFileRequired.getPageOffset(
                    pageAtOffset,
                    zoom
                ).toInt(),
                /* y = */
                pdfFileRequired.getSecondaryPageOffset(
                    pageAtOffset,
                    zoom
                ).toInt(),

                )
        }
    }

    fun isTappedOnLink(eventPointF: PointF): Boolean {
        val eventPointWithOffset = getDevicePointAtOffset(eventPointF)

        val page = getPageAtOffsetPoint(eventPointWithOffset)

        val pageSize = pdfFileRequired.getScaledPageSize(
            pageIndex = page,
            zoom = zoom
        )

        val pagePointAtOffsetAndZoom = getPagePointAtOffsetAndZoom(page)

        for (link in pdfFileRequired.getPageLinks(page)) {
            val mapped = pdfFileRequired.mapRectToDevice(
                page,
                pagePointAtOffsetAndZoom.x,
                pagePointAtOffsetAndZoom.y,
                pageSize.width.toInt(),
                pageSize.height.toInt(),
                link.bounds
            )
            mapped?.sort()
            if (mapped?.contains(eventPointWithOffset.x, eventPointWithOffset.y).orFalse()) {
                callbacks.callLinkHandler(
                    LinkTapEvent(
                        originalX = x,
                        originalY = y,
                        documentX = eventPointWithOffset.x,
                        documentY = eventPointWithOffset.y,
                        mappedLinkRect = mapped.required,
                        link = link
                    )
                )
                return true
            }
        }
        return false
    }

    fun isTappedOnSelectionEdgePoints(eventPointF: PointF): Boolean {
        if (startSelectionRect != null && endSelectionRect != null) {
            val eventPointWithOffset = getDevicePointAtOffset(eventPointF)

            val page = getPageAtOffsetPoint(eventPointWithOffset)

            if (page == startSelectionRect.required.selectionRect.page) {

                val startDeviceRect = startSelectionRect.required.iconRect
                val endDeviceRect = endSelectionRect.required.iconRect

                val isOnStartClick = startDeviceRect?.let {
                    isClickOnRect(it.toRectF(), eventPointWithOffset, tapCompareValue)
                } ?: false

                val isOnEndClick = endDeviceRect?.let {
                    isClickOnRect(it.toRectF(), eventPointWithOffset, tapCompareValue)
                } ?: false

                return isOnStartClick || isOnEndClick
            }
        }

        return false
    }

    fun getDevicePointAtOffset(pointF: PointF): PointF {
        val mappedX = -currentXOffset + pointF.x
        val mappedY = -currentYOffset + pointF.y

        return PointF(mappedX, mappedY)
    }

    fun getPageAtOffsetPoint(offsetPointF: PointF): Int {
        return pdfFileRequired.getPageAtOffset(
            offset = if (isSwipeVertical) offsetPointF.y else offsetPointF.x,
            zoom = zoom
        )
    }

    private fun convertDevicePointWithOffsetToPage(pageAtOffset: Int, pointAtOffset: PointF): PointF? {
        val pageSize = pdfFileRequired.getScaledPageSize(
            pageAtOffset,
            zoom
        )

        val pagePointAtOffsetAndZoom = getPagePointAtOffsetAndZoom(pageAtOffset)

        val convertedPagePoint = pdfFileRequired.mapDeviceCoordinateToPage(
            pageAtOffset,
            pagePointAtOffsetAndZoom.x,
            pagePointAtOffsetAndZoom.y,
            pageSize.width.toInt(),
            pageSize.height.toInt(),
            pointAtOffset.x.toInt(),
            pointAtOffset.y.toInt()
        )

        return convertedPagePoint
    }

    fun setOnLongPress(eventPoint: PointF) {
        val eventPointWithOffset = getDevicePointAtOffset(eventPoint)
        val page = getPageAtOffsetPoint(eventPointWithOffset)

        val eventPagePoint = convertDevicePointWithOffsetToPage(page, eventPointWithOffset)

        eventPagePoint?.let {
            val characterIndex = pdfFileRequired.getCharacterIndex(
                page,
                eventPagePoint.x.toDouble(),
                eventPagePoint.y.toDouble(),
                eventPagePoint.x.toDouble(),
                eventPagePoint.y.toDouble()
            )
            val characterBox = pdfFileRequired.measureCharacterBox(page, characterIndex)
            characterBox?.let { characterRect ->
                if (isClickOnRect(characterRect, eventPagePoint, longPressCompareValue)) {
                    val (startIndex, endIndex) = getStartEndSelectionPoints(characterIndex, page)

                    val startCharacterBox = pdfFileRequired.measureCharacterBox(page, startIndex)
                    val endCharacterBox = pdfFileRequired.measureCharacterBox(page, endIndex)

                    if (startCharacterBox != null) {
                        startSelectionRect =
                            SelectionRectWithIndex(SelectionRect(page, startCharacterBox), startIndex, null)
                    }

                    if (endCharacterBox != null) {
                        endSelectionRect =
                            SelectionRectWithIndex(SelectionRect(page, endCharacterBox), endIndex, null)
                    }

                    when {
                        startSelectionRect != null && endSelectionRect == null -> {
                            endSelectionRect = startSelectionRect
                        }

                        endSelectionRect != null && startSelectionRect == null -> {
                            startSelectionRect = endSelectionRect
                        }

                        startSelectionRect == null -> {
                            startSelectionRect =
                                SelectionRectWithIndex(SelectionRect(page, characterBox), characterIndex, null)
                            endSelectionRect =
                                SelectionRectWithIndex(SelectionRect(page, characterBox), characterIndex, null)
                        }
                    }

                    selectionRects = getSelectionRects(page)
                    callbacks.callOnTextSelect(selectionRects.isNotEmpty())
                }
            }
        }

        redraw()
    }

    fun setOnActionDown(
        eventPoint: PointF,
        actionOnPointClick: (isFromStart: Boolean, page: Int) -> Unit,
        actionToClear: () -> Unit
    ) {
        if (startSelectionRect != null && endSelectionRect != null) {
            val eventPointWithOffset = getDevicePointAtOffset(eventPoint)
            val page = getPageAtOffsetPoint(eventPointWithOffset)

            if (page == startSelectionRect.required.selectionRect.page) {

                val startDeviceRect = startSelectionRect.required.iconRect
                val endDeviceRect = endSelectionRect.required.iconRect

                val isOnStartClick = startDeviceRect?.let {
                    isClickOnRect(it.toRectF(), eventPointWithOffset, tapCompareValue)
                } ?: false

                if (isOnStartClick) {
                    actionOnPointClick(true, page)
                } else {
                    val isOnEndClick = endDeviceRect?.let {
                        isClickOnRect(it.toRectF(), eventPointWithOffset, tapCompareValue)
                    } ?: false

                    if (isOnEndClick) {
                        actionOnPointClick(false, page)
                    } else {
                        actionToClear()
                    }
                }

            } else {
                actionToClear()
            }
        } else {
            actionToClear()
        }
    }

    fun setOnActionMove(eventPoint: PointF, pageToSelect: Int, isStartPointMoving: Boolean) {
        parent.requestDisallowInterceptTouchEvent(true)
        val eventPointWithOffset = getDevicePointAtOffset(eventPoint)
        val page = getPageAtOffsetPoint(eventPointWithOffset)

        if (page == pageToSelect) {
            val eventPagePoint = convertDevicePointWithOffsetToPage(page, eventPointWithOffset)
            eventPagePoint?.let {
                val characterIndex = pdfFileRequired.getCharacterIndex(
                    page,
                    eventPagePoint.x.toDouble(),
                    eventPagePoint.y.toDouble(),
                    eventPagePoint.x.toDouble(),
                    eventPagePoint.y.toDouble()
                )

                if (characterIndex >= 0) {
                    val characterRect = pdfFileRequired.measureCharacterBox(page, characterIndex)
                    characterRect?.let {
                        if (isStartPointMoving) {
                            startSelectionRect = SelectionRectWithIndex(
                                SelectionRect(page, characterRect),
                                characterIndex,
                                null
                            )
                        } else {
                            endSelectionRect = SelectionRectWithIndex(
                                SelectionRect(page, characterRect),
                                characterIndex,
                                null
                            )
                        }

                        selectionRects = getSelectionRects(page)
                        callbacks.callOnTextSelect(selectionRects.isNotEmpty())

                        redraw()
                    }
                }
            }
        }
    }

    private fun getStartEndSelectionPoints(clickCharIndex: Int, page: Int): Pair<Int, Int> {
        return pdfFileRequired.getCountChars(page)?.let { charCounts ->
            pdfFileRequired.extractCharacters(page, clickCharIndex, 1)?.let { clickCharacter ->
                val minIndex = 0
                val maxIndex = charCounts - 1

                val rightCount =
                    if (clickCharIndex + charsOffsetCount <= maxIndex) charsOffsetCount else maxIndex - clickCharIndex
                val rightStartIndex = if (rightCount > minIndex) clickCharIndex + 1 else null

                val leftCount = if (clickCharIndex - charsOffsetCount >= minIndex) charsOffsetCount else clickCharIndex
                val leftStartIndex = if (leftCount > minIndex) clickCharIndex - leftCount else null

                val rightCharacters = rightStartIndex?.let {
                    pdfFileRequired.extractCharacters(page, rightStartIndex, rightCount)
                }

                val leftCharacters = if (clickCharacter != space && clickCharacter != paragraph) {
                    leftStartIndex?.let {
                        pdfFileRequired.extractCharacters(page, leftStartIndex, leftCount)
                    }
                } else null

                val rightIndex = run {
                    val index = rightCharacters?.indexOfAny(listOf(space, paragraph))
                    if (index != null && index >= 0) {
                        index
                    } else if (!rightCharacters.isNullOrEmpty()) rightCharacters.length
                    else null
                }

                val leftIndex = run {
                    val index = leftCharacters?.reversed()?.indexOfAny(listOf(space, paragraph.reversed()))
                    if (index != null && index >= 0) {
                        index
                    } else if (!leftCharacters.isNullOrEmpty()) leftCharacters.length
                    else null
                }

                val startIndexRes = if (leftIndex != null && leftIndex >= 0) {
                    clickCharIndex - leftIndex
                } else clickCharIndex

                val endIndexRes = if (rightIndex != null && rightIndex >= 0) {
                    clickCharIndex + rightIndex
                } else clickCharIndex

                Pair(startIndexRes, endIndexRes)
            }
        } ?: Pair(clickCharIndex, clickCharIndex)
    }

    companion object {
        private val TAG = PDFView::class.java.simpleName
        const val DEFAULT_MAX_SCALE = 3.0f
        const val DEFAULT_MID_SCALE = 1.75f
        const val DEFAULT_MIN_SCALE = 1.0f

        private const val tapCompareValue = 2f
        const val longPressCompareValue = 20f

        private const val charsOffsetCount = 20
        private const val drawableWidthCoeff = 0.6
        private const val drawableHeightStartCoeff = 0.2
        private const val drawableHeightEndCoeff = 0.8

        private const val paragraph = "\r\n"
        private const val space = " "

        private fun isClickOnRect(rect: RectF, clickPoint: PointF, compareValue: Float): Boolean {
            return clickPoint.x >= rect.left - compareValue && clickPoint.x <= rect.right + compareValue &&
                    clickPoint.y >= rect.top - compareValue && clickPoint.y <= rect.bottom + compareValue
        }
    }

    /**
     * Construct the initial view
     */
    init {
        renderingHandlerThread = HandlerThread("PDF renderer")
//        if (isInEditMode) {
//            return
//        }
        cacheManager = CacheManager()
        animationManager = AnimationManager(this)
        dragPinchManager = DragPinchManager(
            this,
            animationManager
        )
        pagesLoader = PagesLoader(this)
        paint = Paint()

        selectionPaint = Paint().apply {
            color = textSelectionColor
        }

        debugPaint = Paint()
        debugPaint.style = Paint.Style.STROKE
        setWillNotDraw(false)
    }
}