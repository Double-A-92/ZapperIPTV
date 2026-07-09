package com.amedeo.zapperiptv.ui.gesture

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs

class SwipeGestureHandler(
    context: Context,
    private val listener: SwipeListener,
) : GestureDetector.SimpleOnGestureListener() {
    interface SwipeListener {
        fun onSwipeUp()

        fun onSwipeDown()

        fun onSwipeLeft()

        fun onSwipeRight()

        fun onSingleTap()

        fun onLongPress()
    }

    private val gestureDetector = GestureDetector(context, this)
    private val density = context.resources.displayMetrics.density
    private val swipeThreshold = SWIPE_THRESHOLD_DP * density
    private val swipeVelocityThreshold = SWIPE_VELOCITY_THRESHOLD_DP * density

    companion object {
        private const val SWIPE_THRESHOLD_DP = 80
        private const val SWIPE_VELOCITY_THRESHOLD_DP = 120
    }

    fun onTouchEvent(event: MotionEvent): Boolean = gestureDetector.onTouchEvent(event)

    override fun onDown(e: MotionEvent): Boolean = true

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        listener.onSingleTap()
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        listener.onLongPress()
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        vx: Float,
        vy: Float,
    ): Boolean {
        if (e1 == null) return false

        val dx = e2.x - e1.x
        val dy = e2.y - e1.y
        val absDx = abs(dx)
        val absDy = abs(dy)

        return if (absDx > absDy) {
            handleHorizontalSwipe(dx, vx, absDx)
        } else {
            handleVerticalSwipe(dy, vy, absDy)
        }
    }

    private fun handleHorizontalSwipe(
        dx: Float,
        vx: Float,
        absDx: Float,
    ): Boolean {
        if (absDx > swipeThreshold && abs(vx) > swipeVelocityThreshold) {
            if (dx < 0) {
                listener.onSwipeLeft()
            } else {
                listener.onSwipeRight()
            }
            return true
        }
        return false
    }

    private fun handleVerticalSwipe(
        dy: Float,
        vy: Float,
        absDy: Float,
    ): Boolean {
        if (absDy > swipeThreshold && abs(vy) > swipeVelocityThreshold) {
            if (dy < 0) {
                listener.onSwipeUp()
            } else {
                listener.onSwipeDown()
            }
            return true
        }
        return false
    }
}
