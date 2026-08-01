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
import kotlin.math.abs

/**
 * A real trackpad, not a stick: relative finger-movement deltas are reported
 * directly and continuously via [onDelta] — no normalization, no deadzone,
 * no "hold at the edge to keep moving." Move your finger, the cursor moves;
 * stop, it stops. Lift and reposition to swipe again, exactly like a laptop
 * trackpad.
 *
 * Gestures:
 *  - Quick low-movement tap -> [onTap] (left click, auto press+release).
 *  - Tap, then within ~300ms touch down again near the same spot and drag ->
 *    [onDragHoldChanged](true) fires (hold left button), stays held through
 *    the drag, [onDragHoldChanged](false) fires on release — the standard
 *    trackpad click-and-drag gesture, for drag-select / drag-and-drop.
 *
 * Same edit-mode drag/pinch-resize pattern as JoystickView, but width and
 * height scale together rather than forcing a square shape.
 */
class TouchpadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onDelta: ((dx: Float, dy: Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    /** Fires true when a double-tap-then-drag starts (hold left button), false when it ends. */
    var onDragHoldChanged: ((holding: Boolean) -> Unit)? = null
    var onPositionChanged: ((xDp: Int, yDp: Int, widthDp: Int, heightDp: Int) -> Unit)? = null
    var editMode: Boolean = false

    companion object {
        private const val TAP_MAX_DURATION_MS = 200L
        private const val TAP_MAX_MOVEMENT_DP = 8f
        private const val DOUBLE_TAP_WINDOW_MS = 300L
    }

    private val density = context.resources.displayMetrics.density
    private fun px2dp(px: Int) = (px / density).toInt()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2A31") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D8BFD"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22C55E"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666"); textSize = 11f * density; textAlign = Paint.Align.CENTER
    }

    private var lastX = 0f; private var lastY = 0f
    private var downX = 0f; private var downY = 0f
    private var downTime = 0L
    private var moved = false

    // Double-tap-then-drag ("click and drag") detection
    private var lastTapUpTime = 0L
    private var lastTapX = 0f; private var lastTapY = 0f
    private var potentialDragHold = false
    private var isDragHold = false

    private var dragStartRawX = 0f; private var dragStartRawY = 0f
    private var dragStartLeft = 0; private var dragStartTop = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val lp = layoutParams as? FrameLayout.LayoutParams ?: return true
            val f = detector.scaleFactor
            val minW = (110 * density).toInt(); val maxW = (420 * density).toInt()
            val minH = (80 * density).toInt();  val maxH = (300 * density).toInt()
            lp.width = (width * f).toInt().coerceIn(minW, maxW)
            lp.height = (height * f).toInt().coerceIn(minH, maxH)
            layoutParams = lp
            return true
        }
    })

    override fun onDraw(canvas: Canvas) {
        val r = 16f * density
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)
        canvas.drawRoundRect(2f, 2f, w - 2f, h - 2f, r, r, when {
            editMode -> editPaint
            isDragHold -> holdPaint
            else -> borderPaint
        })
        canvas.drawText("tap = click \u2022 double-tap+drag = hold", w / 2f, h / 2f, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return handleEditTouch(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
                downTime = System.currentTimeMillis()
                moved = false
                val tapMovePx = TAP_MAX_MOVEMENT_DP * density
                potentialDragHold = (downTime - lastTapUpTime < DOUBLE_TAP_WINDOW_MS) &&
                    abs(downX - lastTapX) < tapMovePx && abs(downY - lastTapY) < tapMovePx
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (!moved && (abs(event.x - downX) > TAP_MAX_MOVEMENT_DP * density ||
                        abs(event.y - downY) > TAP_MAX_MOVEMENT_DP * density)
                ) {
                    moved = true
                    if (potentialDragHold && !isDragHold) {
                        isDragHold = true
                        onDragHoldChanged?.invoke(true)
                        invalidate()
                    }
                }
                if (dx != 0f || dy != 0f) onDelta?.invoke(dx, dy)
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragHold) {
                    isDragHold = false
                    onDragHoldChanged?.invoke(false)
                    lastTapUpTime = 0L // consumed — don't chain into a third gesture
                    invalidate()
                } else {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!moved && elapsed < TAP_MAX_DURATION_MS) {
                        onTap?.invoke()
                        lastTapUpTime = System.currentTimeMillis()
                        lastTapX = downX; lastTapY = downY
                    } else {
                        lastTapUpTime = 0L // a plain drag doesn't count toward double-tap
                    }
                }
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
                onPositionChanged?.invoke(px2dp(lp.leftMargin), px2dp(lp.topMargin), px2dp(lp.width), px2dp(lp.height))
            }
        }
        invalidate()
        return true
    }
}
