package com.dramebaz.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView
import kotlin.math.abs

/**
 * A ScrollView that properly handles touch events to allow horizontal swipes
 * to pass through to a parent ViewPager2 while still handling vertical scrolling.
 *
 * The key insight is that ViewPager2 uses horizontal swipes for page navigation,
 * while ScrollView uses vertical swipes for content scrolling. This class detects
 * the swipe direction and only intercepts vertical swipes, allowing horizontal
 * swipes to bubble up to the parent ViewPager2.
 */
class ViewPager2CompatibleScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private var startX = 0f
    private var startY = 0f
    private var isScrollingVertically = false
    private var isScrollingHorizontally = false

    // Minimum distance to determine scroll direction (in pixels)
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                isScrollingVertically = false
                isScrollingHorizontally = false
                // Allow parent to intercept if needed
                parent?.requestDisallowInterceptTouchEvent(false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isScrollingVertically && !isScrollingHorizontally) {
                    val deltaX = abs(ev.x - startX)
                    val deltaY = abs(ev.y - startY)

                    if (deltaX > touchSlop || deltaY > touchSlop) {
                        if (deltaX > deltaY) {
                            // Horizontal scroll - let parent (ViewPager2) handle it
                            isScrollingHorizontally = true
                            parent?.requestDisallowInterceptTouchEvent(false)
                            return false
                        } else {
                            // Vertical scroll - we handle it
                            isScrollingVertically = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }

                // If scrolling horizontally, don't intercept
                if (isScrollingHorizontally) {
                    return false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isScrollingVertically = false
                isScrollingHorizontally = false
            }
        }

        // Only intercept if we're scrolling vertically
        return if (isScrollingHorizontally) {
            false
        } else {
            super.onInterceptTouchEvent(ev)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isScrollingVertically && !isScrollingHorizontally) {
                    val deltaX = abs(ev.x - startX)
                    val deltaY = abs(ev.y - startY)

                    if (deltaX > touchSlop || deltaY > touchSlop) {
                        if (deltaX > deltaY) {
                            // Horizontal scroll - let parent handle it
                            isScrollingHorizontally = true
                            parent?.requestDisallowInterceptTouchEvent(false)
                            return false
                        } else {
                            // Vertical scroll - we handle it
                            isScrollingVertically = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }

                // If scrolling horizontally, pass to parent
                if (isScrollingHorizontally) {
                    return false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isScrollingVertically = false
                isScrollingHorizontally = false
            }
        }

        return super.onTouchEvent(ev)
    }
}

