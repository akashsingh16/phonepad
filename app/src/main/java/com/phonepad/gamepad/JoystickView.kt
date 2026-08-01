package com.phonepad.gamepad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draggable analog stick. Reports normalized -1f..1f offsets via [onMove].
 *
 * When [editMode] is true, touches instead reposition/resize the view itself
 * (single-finger drag to move, pinch to resize) and [onPositionChanged] fires
 * on release with the new dp position/size — the stick stops reporting
 * analog input while editMode is on, since dragging IS the input then.
 * Requires a FrameLayout parent (uses FrameLayout.LayoutParams for position).
 */
class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMove: ((nx: Float, ny: Float) -> Unit)? = null
    var onPositionChanged: ((xDp: Int, yDp: Int, sizeDp: Int) -> Unit)? = null
    var editMode: Boolean = false

    private val density = context.resources.displayMetrics.density
    private fun px2dp(px: Int) = (px / density).toInt()

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2A31") }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3D8BFD") }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22C55E"); style = Paint.Style.STROKE; strokeWidth = 4f
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f
    private var knobX = 0f
    private var knobY = 0f

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartLeft = 0
    private var dragStartTop = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val lp = layoutParams as? FrameLayout.LayoutParams ?: return true
            val minSize = (90 * density).toInt()
            val maxSize = (260 * density).toInt()
            val newSize = (width * detector.scaleFactor).toInt().coerceIn(minSize, maxSize)
            lp.width = newSize; lp.height = newSize
            layoutParams = lp
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = min(w, h) / 2f
        knobRadius = baseRadius * 0.45f
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        if (editMode) canvas.drawCircle(centerX, centerY, baseRadius - 3f, editPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return handleEditTouch(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = event.x - centerX
                var dy = event.y - centerY
                val dist = sqrt(dx * dx + dy * dy)
                val maxDist = baseRadius - knobRadius
                if (dist > maxDist && dist > 0f) {
                    dx = dx / dist * maxDist
                    dy = dy / dist * maxDist
                }
                knobX = centerX + dx
                knobY = centerY + dy
                invalidate()
                onMove?.invoke(dx / maxDist, dy / maxDist)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = centerX
                knobY = centerY
                invalidate()
                onMove?.invoke(0f, 0f)
            }
        }
        return true
    }

    private fun handleEditTouch(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX; dragStartRawY = event.rawY
                dragStartLeft = left; dragStartTop = top
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val lp = layoutParams as? FrameLayout.LayoutParams ?: return true
                    lp.gravity = 0
                    lp.leftMargin = (dragStartLeft + (event.rawX - dragStartRawX)).toInt().coerceAtLeast(0)
                    lp.topMargin = (dragStartTop + (event.rawY - dragStartRawY)).toInt().coerceAtLeast(0)
                    layoutParams = lp
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val lp = layoutParams as? FrameLayout.LayoutParams ?: return true
                onPositionChanged?.invoke(px2dp(lp.leftMargin), px2dp(lp.topMargin), px2dp(lp.width))
            }
        }
        invalidate()
        return true
    }
}
