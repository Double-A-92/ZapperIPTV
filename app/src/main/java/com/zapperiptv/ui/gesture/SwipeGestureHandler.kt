package com.zapperiptv.ui.gesture

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs

class SwipeGestureHandler(
    context: Context,
    private val onSwipeUp: () -> Unit,
    private val onSwipeDown: () -> Unit,
    private val onSwipeLeft: () -> Unit,
    private val onSwipeRight: () -> Unit,
    private val onSingleTap: () -> Unit,
    private val onLongPress: () -> Unit
) : GestureDetector.SimpleOnGestureListener() {

    private val gestureDetector = GestureDetector(context, this)
    private val density = context.resources.displayMetrics.density
    private val swipeThreshold = 80 * density
    private val swipeVelocityThreshold = 120 * density

    fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    override fun onDown(e: MotionEvent): Boolean = true

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        onSingleTap()
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        onLongPress()
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        vx: Float,
        vy: Float
    ): Boolean {
        if (e1 == null) return false

        val dx = e2.x - e1.x
        val dy = e2.y - e1.y
        val absDx = abs(dx)
        val absDy = abs(dy)

        return if (absDx > absDy) {
            if (absDx > swipeThreshold && abs(vx) > swipeVelocityThreshold) {
                if (dx < 0) onSwipeLeft() else onSwipeRight()
                true
            } else false
        } else {
            if (absDy > swipeThreshold && abs(vy) > swipeVelocityThreshold) {
                if (dy < 0) onSwipeUp() else onSwipeDown()
                true
            } else false
        }
    }
}
