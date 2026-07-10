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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-page ink surface tuned for e-ink. A plain View, deliberately: SurfaceView
 * buffer swaps make the Supernote's e-ink compositor run flashing full refreshes,
 * whereas dirty-rect invalidation gets quiet partial refreshes.
 *  - strokes render into a backing bitmap; only the dirty rectangle invalidates;
 *  - stylus events delivered unbuffered, segments midpoint-smoothed;
 *  - a short velocity-based prediction tail is drawn ahead of the pen and
 *    repaired on the next sample, hiding a beat of pipeline delay;
 *  - only the EMR stylus draws, so a resting wrist never inks.
 * Fires [onInkRested] once the pen has been still long enough.
 */
class InkView(context: Context) : View(context) {

    var onInkRested: ((Bitmap) -> Unit)? = null
    var onTwoFingerHold: (() -> Unit)? = null

    /** While false, ink still renders but the rest-timer won't fire (e.g. mid-consultation). */
    var restTimerEnabled = true

    private class Stroke(val path: Path = Path(), val points: MutableList<PointF> = mutableListOf())

    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null
    private var lastX = 0f
    private var lastY = 0f
    private var prevMidX = 0f
    private var prevMidY = 0f
    private var lastT = 0L
    private var velX = 0f
    private var velY = 0f

    /** Committed ink; prediction stays out of it. */
    private var inkBitmap: Bitmap? = null
    private var inkCanvas: Canvas? = null
    private val bitmapPaint = Paint()
    private var inkAlpha = 255

    /** Prediction tail drawn in onDraw, ahead of the committed ink. */
    private var hasPrediction = false
    private var predX = 0f
    private var predY = 0f
    private val predicted = RectF()

    // Anti-aliasing buys nothing on a grayscale e-ink panel and costs rasterization time.
    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val segmentPath = Path()
    private val dirty = RectF()

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        inkBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            inkCanvas = Canvas(it)
        }
        redrawAll()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = inkBitmap ?: return
        bitmapPaint.alpha = inkAlpha
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        if (hasPrediction) canvas.drawLine(lastX, lastY, predX, predY, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)

        // Fingers never ink — they exist only for the two-finger settings hold.
        // The palm rests on the capacitive layer while the EMR pen writes; gating
        // on tool type is what keeps wrist contact off the page.
        if (tool == MotionEvent.TOOL_TYPE_FINGER) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
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

        if (tool == MotionEvent.TOOL_TYPE_ERASER) {
            eraseNear(event.x, event.y)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Ask for stylus events as they happen instead of batched per frame.
                requestUnbufferedDispatch(event)
                removeCallbacks(restRunnable)
                if (inkAlpha != 255) {
                    inkAlpha = 255
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
                prevMidX = lastX
                prevMidY = lastY
                lastT = event.eventTime
                velX = 0f
                velY = 0f
                hasPrediction = false
                predicted.setEmpty()
                current = Stroke().also {
                    it.path.moveTo(lastX, lastY)
                    it.points.add(PointF(lastX, lastY))
                    strokes.add(it)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = current ?: return true
                dirty.setEmpty()
                if (!predicted.isEmpty) {
                    dirty.union(predicted)   // repair last frame's prediction tail
                    predicted.setEmpty()
                }
                for (i in 0 until event.historySize) {
                    appendSegment(stroke, event.getHistoricalX(i), event.getHistoricalY(i),
                        event.getHistoricalEventTime(i))
                }
                appendSegment(stroke, event.x, event.y, event.eventTime)

                // Predict a short tail ahead of the pen from its current velocity.
                var px = lastX + velX * PREDICT_MS
                var py = lastY + velY * PREDICT_MS
                val dist = hypot((px - lastX).toDouble(), (py - lastY).toDouble()).toFloat()
                if (dist > PREDICT_MAX) {
                    val s = PREDICT_MAX / dist
                    px = lastX + (px - lastX) * s
                    py = lastY + (py - lastY) * s
                }
                hasPrediction = dist > 1f
                if (hasPrediction) {
                    predX = px
                    predY = py
                    predicted.set(min(lastX, px), min(lastY, py), max(lastX, px), max(lastY, py))
                    dirty.union(predicted)
                }

                if (!dirty.isEmpty) {
                    val pad = (paint.strokeWidth + 4f).toInt()
                    invalidate(
                        dirty.left.toInt() - pad, dirty.top.toInt() - pad,
                        dirty.right.toInt() + pad, dirty.bottom.toInt() + pad
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
                if (hasPrediction) {
                    // Erase the tail that ran ahead of where the pen actually lifted.
                    hasPrediction = false
                    val pad = (paint.strokeWidth + 4f).toInt()
                    invalidate(
                        predicted.left.toInt() - pad, predicted.top.toInt() - pad,
                        predicted.right.toInt() + pad, predicted.bottom.toInt() + pad
                    )
                    predicted.setEmpty()
                }
                removeCallbacks(restRunnable)
                postDelayed(restRunnable, REST_MS)
            }
        }
        return true
    }

    /**
     * Midpoint-smoothed segment committed into the backing bitmap; also updates
     * the pen velocity estimate used for the prediction tail.
     */
    private fun appendSegment(stroke: Stroke, x: Float, y: Float, t: Long) {
        val midX = (lastX + x) / 2f
        val midY = (lastY + y) / 2f

        segmentPath.rewind()
        segmentPath.moveTo(prevMidX, prevMidY)
        segmentPath.quadTo(lastX, lastY, midX, midY)
        inkCanvas?.drawPath(segmentPath, paint)

        stroke.path.quadTo(lastX, lastY, midX, midY)
        stroke.points.add(PointF(x, y))

        dirty.union(min(prevMidX, x), min(prevMidY, y))
        dirty.union(max(prevMidX, x), max(prevMidY, y))

        val dt = (t - lastT).toFloat()
        if (dt > 0f) {
            // Light smoothing keeps the tail from whipping on noisy samples.
            velX = 0.6f * ((x - lastX) / dt) + 0.4f * velX
            velY = 0.6f * ((y - lastY) / dt) + 0.4f * velY
        }
        lastT = t
        prevMidX = midX
        prevMidY = midY
        lastX = x
        lastY = y
    }

    private fun cancelHold() {
        twoFingerDownAt = 0L
        twoFingerStart = null
        removeCallbacks(holdRunnable)
    }

    private fun eraseNear(x: Float, y: Float) {
        val before = strokes.size
        strokes.removeAll { stroke -> stroke.points.any { abs(it.x - x) < ERASE_R && abs(it.y - y) < ERASE_R } }
        if (strokes.size != before) {
            redrawAll()
            invalidate()
        }
        removeCallbacks(restRunnable)
    }

    /** Rebuild the backing bitmap from the stroke list (erase and resize only — never per frame). */
    private fun redrawAll() {
        val canvas = inkCanvas ?: return
        inkBitmap?.eraseColor(Color.TRANSPARENT)
        for (stroke in strokes) canvas.drawPath(stroke.path, paint)
    }

    /** Bottom edge of the most recently exported ink, in view coordinates (-1 when none). */
    var lastInkBottom = -1f
        private set

    /** Render the current ink onto a white bitmap, cropped to the writing plus a margin. */
    fun exportInk(): Bitmap? {
        if (strokes.isEmpty()) return null
        val bounds = RectF()
        val strokeBounds = RectF()
        for (stroke in strokes) {
            stroke.path.computeBounds(strokeBounds, true)
            bounds.union(strokeBounds)
        }
        lastInkBottom = bounds.bottom
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
                    inkBitmap?.eraseColor(Color.TRANSPARENT)
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
        inkBitmap?.eraseColor(Color.TRANSPARENT)
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
        // Long-edge cap for the exported snapshot: smaller uploads and roughly half
        // the vision tokens vs 1568px, while handwriting stays comfortably legible.
        const val MAX_EDGE = 1120f
        // Prediction tail: ~one event-batch of lead, capped so misprediction stays subtle.
        const val PREDICT_MS = 20f
        const val PREDICT_MAX = 24f
    }
}
