package com.ommahida.inkling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-page ink surface. Captures stylus (and single-finger) strokes, erases with the
 * pen's eraser end, and fires [onInkRested] once the pen has been still long enough.
 */
class InkView(context: Context) : View(context) {

    var onInkRested: ((Bitmap) -> Unit)? = null
    var onTwoFingerHold: (() -> Unit)? = null

    /** While false, ink still renders but the rest-timer won't fire (e.g. mid-consultation). */
    var restTimerEnabled = true

    private class Stroke(val path: Path = Path(), val points: MutableList<PointF> = mutableListOf())

    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null
    private var inkAlpha = 255

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val restRunnable = Runnable {
        if (strokes.isNotEmpty() && restTimerEnabled) {
            exportInk()?.let { onInkRested?.invoke(it) }
        }
    }

    private var twoFingerDownAt = 0L
    private var twoFingerStart: PointF? = null
    private val holdRunnable = Runnable {
        if (twoFingerDownAt > 0) onTwoFingerHold?.invoke()
    }

    fun hasInk(): Boolean = strokes.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.alpha = inkAlpha
        for (stroke in strokes) canvas.drawPath(stroke.path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) {
            cancelStroke()
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    twoFingerDownAt = System.currentTimeMillis()
                    twoFingerStart = PointF(event.getX(0), event.getY(0))
                    postDelayed(holdRunnable, HOLD_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val start = twoFingerStart
                    if (start != null &&
                        (abs(event.getX(0) - start.x) > HOLD_SLOP || abs(event.getY(0) - start.y) > HOLD_SLOP)
                    ) {
                        cancelHold()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
            }
            return true
        }
        cancelHold()

        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
            eraseNear(event.x, event.y)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(restRunnable)
                inkAlpha = 255
                current = Stroke().also {
                    it.path.moveTo(event.x, event.y)
                    it.points.add(PointF(event.x, event.y))
                    strokes.add(it)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = current ?: return true
                for (i in 0 until event.historySize) {
                    stroke.path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                    stroke.points.add(PointF(event.getHistoricalX(i), event.getHistoricalY(i)))
                }
                stroke.path.lineTo(event.x, event.y)
                stroke.points.add(PointF(event.x, event.y))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
                removeCallbacks(restRunnable)
                postDelayed(restRunnable, REST_MS)
            }
        }
        return true
    }

    private fun cancelStroke() {
        val stroke = current ?: return
        // A palm/two-finger touch mid-stroke: keep what was drawn, stop extending it.
        current = null
        if (stroke.points.size < 2) strokes.remove(stroke)
        invalidate()
    }

    private fun cancelHold() {
        twoFingerDownAt = 0L
        twoFingerStart = null
        removeCallbacks(holdRunnable)
    }

    private fun eraseNear(x: Float, y: Float) {
        val before = strokes.size
        strokes.removeAll { stroke -> stroke.points.any { abs(it.x - x) < ERASE_R && abs(it.y - y) < ERASE_R } }
        if (strokes.size != before) invalidate()
        removeCallbacks(restRunnable)
    }

    /** Render the current ink onto a white bitmap, cropped to the writing plus a margin. */
    fun exportInk(): Bitmap? {
        if (strokes.isEmpty()) return null
        val bounds = RectF()
        val strokeBounds = RectF()
        for (stroke in strokes) {
            stroke.path.computeBounds(strokeBounds, true)
            bounds.union(strokeBounds)
        }
        bounds.inset(-PAD, -PAD)
        bounds.intersect(0f, 0f, width.toFloat(), height.toFloat())
        val w = max(1f, bounds.width())
        val h = max(1f, bounds.height())
        val scale = min(1f, MAX_EDGE / max(w, h))

        val bitmap = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        canvas.translate(-bounds.left, -bounds.top)
        paint.alpha = 255
        for (stroke in strokes) canvas.drawPath(stroke.path, paint)
        return bitmap
    }

    /** E-ink friendly stepped fade: a few discrete gray levels, then gone. */
    fun fadeInk(onDone: (() -> Unit)? = null) {
        removeCallbacks(restRunnable)
        val steps = intArrayOf(150, 70, 0)
        steps.forEachIndexed { i, alpha ->
            postDelayed({
                if (alpha == 0) {
                    strokes.clear()
                    inkAlpha = 255
                    onDone?.invoke()
                } else {
                    inkAlpha = alpha
                }
                invalidate()
            }, FADE_STEP_MS * (i + 1))
        }
    }

    fun clearInk() {
        removeCallbacks(restRunnable)
        strokes.clear()
        inkAlpha = 255
        invalidate()
    }

    private companion object {
        const val REST_MS = 2500L
        const val HOLD_MS = 900L
        const val HOLD_SLOP = 48f
        const val ERASE_R = 28f
        const val PAD = 32f
        const val FADE_STEP_MS = 350L
        const val MAX_EDGE = 1568f
    }
}
