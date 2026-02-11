package com.dramebaz.app.ui.widget

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * A Canvas-based View that simulates a page curl/flip effect.
 * No external dependencies - uses only Android's built-in Canvas APIs.
 * 
 * Based on the algorithm from moritz-wundke/android-page-curl.
 */
class PageCurlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Callback interface for page change events */
    interface PageCurlListener {
        fun onPageChanged(newPageIndex: Int)
        fun getPageCount(): Int
        fun getPageBitmap(pageIndex: Int, width: Int, height: Int, callback: (Bitmap?) -> Unit)
    }

    var listener: PageCurlListener? = null

    // Current page index
    private var currentPageIndex = 0

    // Page bitmaps
    private var foregroundBitmap: Bitmap? = null
    private var backgroundBitmap: Bitmap? = null

	    // Curl speed and animation settings
	    // NOTE: Higher curlSpeed = faster page flip. Default tuned for PDF test activity.
	    // Increased default speed for snappier page flips in the PDF viewer test.
	    private var curlSpeed = 120
	    private var updateRate = 16L // ~60fps
    private var initialEdgeOffset = 20

    // Curl mode: Simple (one axis) or Dynamic (both axes)
    private var curlModeDynamic = true

    // Animation handler
    private val animationHandler = FlipAnimationHandler()

    // Curl geometry points (A, B, C, D, E, F)
    private var mA = PointF()
    private var mB = PointF()
    private var mC = PointF()
    private var mD = PointF()
    private var mE = PointF()
    private var mF = PointF()
    private var mOldF = PointF()
    private var mOrigin = PointF()

    // Movement tracking
    private var mMovement = PointF()
    private var mFinger = PointF()
    private var mOldMovement = PointF()

    // State flags
    private var flipRadius = 0f
    private var isFlipping = false
    private var isUserMoving = false
    private var flipRight = true
    private var blockTouchInput = false
    private var viewDrawn = false

    // Paints
    private val curlEdgePaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.FILL
        setShadowLayer(10f, -5f, 5f, 0x99000000.toInt())
    }

    private val bitmapPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    private val pagePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    init {
        // Required for shadow layer
        setLayerType(LAYER_TYPE_SOFTWARE, curlEdgePaint)
    }

    /**
     * Set the current page index and load bitmaps
     */
    fun setCurrentPage(pageIndex: Int) {
        currentPageIndex = pageIndex.coerceIn(0, (listener?.getPageCount() ?: 1) - 1)
        loadPageBitmaps()
        invalidate()
    }

    fun getCurrentPage(): Int = currentPageIndex

    /**
     * Load foreground and background bitmaps for current pages
     */
    private fun loadPageBitmaps() {
        val pageCount = listener?.getPageCount() ?: 0
        android.util.Log.d("PageCurlView", "loadPageBitmaps: pageCount=$pageCount, currentPage=$currentPageIndex, width=$width, height=$height")
        if (pageCount == 0 || width <= 0 || height <= 0) {
            android.util.Log.w("PageCurlView", "loadPageBitmaps: skipped - invalid state")
            return
        }

        // Load current page as foreground
        listener?.getPageBitmap(currentPageIndex, width, height) { bitmap ->
            android.util.Log.d("PageCurlView", "loadPageBitmaps: foreground received, bitmap=${if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "null"}")
            foregroundBitmap = bitmap
            post { invalidate() }
        }

        // Load next page as background (if exists)
        val nextIndex = currentPageIndex + 1
        if (nextIndex < pageCount) {
            listener?.getPageBitmap(nextIndex, width, height) { bitmap ->
                android.util.Log.d("PageCurlView", "loadPageBitmaps: background received for page $nextIndex, bitmap=${if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "null"}")
                backgroundBitmap = bitmap
            }
        } else {
            android.util.Log.d("PageCurlView", "loadPageBitmaps: no next page, clearing background")
            backgroundBitmap = null
        }
    }

    private fun loadPreviousPage() {
        val prevIndex = currentPageIndex - 1
        if (prevIndex >= 0) {
            listener?.getPageBitmap(prevIndex, width, height) { bitmap ->
                // Swap: current becomes background, previous becomes foreground
                backgroundBitmap = foregroundBitmap
                foregroundBitmap = bitmap
                post { invalidate() }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        flipRadius = w.toFloat()
        resetClipEdge()
        loadPageBitmaps()
    }

    private fun resetClipEdge() {
        mMovement.set(initialEdgeOffset.toFloat(), initialEdgeOffset.toFloat())
        mOldMovement.set(0f, 0f)

        mA.set(initialEdgeOffset.toFloat(), 0f)
        mB.set(width.toFloat(), height.toFloat())
        mC.set(width.toFloat(), 0f)
        mD.set(0f, 0f)
        mE.set(0f, 0f)
        mF.set(0f, 0f)
        mOldF.set(0f, 0f)
        mOrigin.set(width.toFloat(), 0f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (blockTouchInput) return true

        mFinger.set(event.x, event.y)
        val halfWidth = width / 2f

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mOldMovement.set(mFinger)

                if (mFinger.x > halfWidth) {
                    // Flip forward (to next page)
                    mMovement.set(initialEdgeOffset.toFloat(), initialEdgeOffset.toFloat())
                    flipRight = true
                } else {
                    // Flip backward (to previous page)
                    flipRight = false
                    if (currentPageIndex > 0) {
                        loadPreviousPage()
                        mMovement.set(
                            if (curlModeDynamic) width * 2f else width.toFloat(),
                            initialEdgeOffset.toFloat()
                        )
                    } else {
                        return true // No previous page
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                isUserMoving = true

                // Calculate movement delta
                mMovement.x -= mFinger.x - mOldMovement.x
                mMovement.y -= mFinger.y - mOldMovement.y
                capMovement(mMovement, true)

                // Ensure minimum y value
                if (mMovement.y <= 1f) mMovement.y = 1f

                // Determine flip direction
                flipRight = mFinger.x < mOldMovement.x

                mOldMovement.set(mFinger)

                doPageCurl()
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isUserMoving = false
                isFlipping = true
                flipAnimationStep()
            }
        }
        return true
    }

    private fun capMovement(point: PointF, maintainDirection: Boolean) {
        val distance = hypot(point.x - mOrigin.x, point.y - mOrigin.y)
        if (distance > flipRadius) {
            if (maintainDirection) {
                val dx = point.x - mOrigin.x
                val dy = point.y - mOrigin.y
                val scale = flipRadius / distance
                point.x = mOrigin.x + dx * scale
                point.y = mOrigin.y + dy * scale
            } else {
                if (point.x > mOrigin.x + flipRadius) {
                    point.x = mOrigin.x + flipRadius
                } else if (point.x < mOrigin.x - flipRadius) {
                    point.x = mOrigin.x - flipRadius
                }
                val dx = abs(point.x - mOrigin.x)
                point.y = sin(acos(dx / flipRadius)) * flipRadius
            }
        }
    }

    private fun flipAnimationStep() {
        if (!isFlipping) return

        blockTouchInput = true

        // Move based on flip direction
        val speed = if (flipRight) curlSpeed.toFloat() else -curlSpeed.toFloat()
        mMovement.x += speed
        capMovement(mMovement, false)

        doPageCurl()

        // Check if flip is complete
        if (mA.x < 1f || mA.x > width - 1f) {
            isFlipping = false

            if (flipRight) {
                // Moved to next page
                val pageCount = listener?.getPageCount() ?: 0
                if (currentPageIndex < pageCount - 1) {
                    currentPageIndex++
                    listener?.onPageChanged(currentPageIndex)
                    loadPageBitmaps()
                }
            } else {
                // Moved to previous page
                if (currentPageIndex > 0) {
                    currentPageIndex--
                    listener?.onPageChanged(currentPageIndex)
                    // Bitmaps already swapped in loadPreviousPage
                }
            }

            resetClipEdge()
            doPageCurl()
            blockTouchInput = false
        } else {
            animationHandler.sleep(updateRate)
        }

        invalidate()
    }

    private fun doPageCurl() {
        if (curlModeDynamic) {
            doDynamicCurl()
        } else {
            doSimpleCurl()
        }
    }

    private fun doSimpleCurl() {
        val w = width.toFloat()
        val h = height.toFloat()

        // Calculate point A
        mA.x = w - mMovement.x
        mA.y = h

        // Calculate point D
        if (mA.x > w / 2) {
            mD.x = w
            mD.y = h - (w - mA.x) * h / mA.x
        } else {
            mD.x = 2 * mA.x
            mD.y = 0f
        }

        // Calculate E and F using geometry
        val angle = atan((h - mD.y) / (mD.x + mMovement.x - w))
        val cosAngle = cos(2 * angle)
        val sinAngle = sin(2 * angle)

        mF.x = (w - mMovement.x + cosAngle * mMovement.x).toFloat()
        mF.y = (h - sinAngle * mMovement.x).toFloat()

        if (mA.x > w / 2) {
            mE.set(mD)
        } else {
            mE.x = (mD.x + cosAngle * (w - mD.x)).toFloat()
            mE.y = (-sinAngle * (w - mD.x)).toFloat()
        }
    }

    private fun doDynamicCurl() {
        val w = width.toFloat()
        val h = height.toFloat()

        // F follows the finger with small displacement
        mF.x = w - mMovement.x + 0.1f
        mF.y = h - mMovement.y + 0.1f

        // Constrain F based on A
        if (mA.x == 0f) {
            mF.x = min(mF.x, mOldF.x)
            mF.y = max(mF.y, mOldF.y)
        }

        val deltaX = w - mF.x
        val deltaY = h - mF.y
        val bh = hypot(deltaX, deltaY) / 2f
        val alpha = atan2(deltaY, deltaX)

        mA.x = (w - bh / cos(alpha)).toFloat()
        mA.y = h
        mD.x = w
        mD.y = (h - bh / sin(alpha)).toFloat()

        mA.x = max(0f, mA.x)
        if (mA.x == 0f) {
            mOldF.set(mF)
        }

        mE.set(mD)

        // Correct if D.y goes negative
        if (mD.y < 0) {
            val tanAlpha = deltaY / deltaX
            mD.x = w + tanAlpha * mD.y
            mE.y = 0f
            mE.x = w + tan(2 * alpha).toFloat() * mD.y
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!viewDrawn) {
            viewDrawn = true
            flipRadius = width.toFloat()
            resetClipEdge()
            doPageCurl()
        }

        // Draw white background
        canvas.drawColor(Color.WHITE)

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        // Draw foreground (current page)
        drawForeground(canvas, rect)

        // Draw background (next/previous page) with clipping
        drawBackground(canvas, rect)

        // Draw curl edge
        drawCurlEdge(canvas)
    }

    private fun drawForeground(canvas: Canvas, rect: RectF) {
        foregroundBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
        } ?: canvas.drawRect(rect, pagePaint)
    }

    private fun drawBackground(canvas: Canvas, rect: RectF) {
        val path = Path().apply {
            moveTo(mA.x, mA.y)
            lineTo(mB.x, mB.y)
            lineTo(mC.x, mC.y)
            lineTo(mD.x, mD.y)
            close()
        }

        canvas.save()
        canvas.clipPath(path)
        backgroundBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
        } ?: canvas.drawRect(rect, pagePaint)
        canvas.restore()
    }

    private fun drawCurlEdge(canvas: Canvas) {
        val path = Path().apply {
            moveTo(mA.x, mA.y)
            lineTo(mD.x, mD.y)
            lineTo(mE.x, mE.y)
            lineTo(mF.x, mF.y)
            close()
        }
        canvas.drawPath(path, curlEdgePaint)
    }

    private inner class FlipAnimationHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            flipAnimationStep()
        }

        fun sleep(millis: Long) {
            removeMessages(0)
            sendMessageDelayed(obtainMessage(0), millis)
        }
    }
}

