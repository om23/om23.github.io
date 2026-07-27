package com.ommahida.inkling

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View

/**
 * Renders the diary's answer in a flowing hand. While the diary composes, a little wand casts
 * twinkling sparkles where the reply will land; when it arrives the whole reply materializes with a
 * gentle fade-in, holds long enough to read, then dissolves back into the page.
 */
class ReplyView(context: Context) : View(context) {

    var onFinished: (() -> Unit)? = null

    private var layout: StaticLayout? = null
    private var textAlpha = 255
    private var showing = false

    /** True while the wand animation is running (between consult and the reply arriving). */
    private var musing = false
    private var museFrame = 0

    private val wandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val starPath = Path()

    /** Where the top of the reply wants to sit (view Y); < 0 means the default upper-page spot. */
    private var anchorY = -1f

    // Posted callbacks capture the generation they belong to; bumping it
    // invalidates everything in flight without touching other views' handlers.
    private var generation = 0

    private val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 40f, context.resources.displayMetrics
        )
        // resources.getFont is API 26+; both Supernote generations qualify (A5X/A6X
        // run Android 8.1 / API 27, Nomad and Manta run Android 11 / API 30).
        typeface = try {
            context.resources.getFont(R.font.dancing_script)
        } catch (_: Exception) {
            null
        } ?: Typeface.create("cursive", Typeface.NORMAL)
    }

    /** @param belowY ink bottom in view coordinates; the reply starts just under it. */
    fun reveal(text: String, belowY: Float = -1f) {
        generation++
        anchorY = if (belowY >= 0) belowY + GAP else -1f
        val body = text.trim()
        if (body.isEmpty()) { showing = false; musing = false; return }
        musing = false
        showing = true
        layout = buildLayout(body)
        textAlpha = 0
        invalidate()
        fadeIn(generation)
    }

    fun isShowing(): Boolean = showing

    /** Cut the hold short (e.g. the writer picked the pen back up). */
    fun dismiss() {
        if (!showing) return
        generation++
        fadeOut(generation)
    }

    /**
     * While the diary composes, a wand casts twinkling sparkles where the reply will appear, so the
     * wait reads as magic rather than silence. Cancelled by the next [reveal].
     */
    fun showMusing(belowY: Float = -1f) {
        generation++
        anchorY = if (belowY >= 0) belowY + GAP else -1f
        showing = false
        layout = null
        musing = true
        museFrame = 0
        invalidateMuse()
        museLoop(generation)
    }

    private fun museLoop(gen: Int) {
        postDelayed({
            if (gen != generation || !musing) return@postDelayed
            museFrame++
            invalidateMuse()
            museLoop(gen)
        }, MUSE_MS)
    }

    /** Repaint just the wand's region so the e-ink panel does a small partial refresh, not a flash. */
    private fun invalidateMuse() {
        val y = museAnchorY()
        invalidate(MARGIN - 8, (y - 48).toInt(), MARGIN + 280, (y + 136).toInt())
    }

    private fun museAnchorY(): Float {
        val y = if (anchorY >= 0) anchorY else height * 0.12f
        return y.coerceIn(MARGIN.toFloat() + 44f, (height - 168).toFloat())
    }

    /** A wand with a twinkling tip-star and sparkles that build up over four frames, then reset. */
    private fun drawWand(canvas: Canvas) {
        val y = museAnchorY()
        val hx = MARGIN.toFloat() + 6f   // handle end (lower-left)
        val hy = y + 104f
        val tx = MARGIN.toFloat() + 92f  // wand tip (upper-right)
        val ty = y + 14f
        val frame = museFrame % 4

        // The wand: a thin shaft with a thicker grip near the handle.
        wandPaint.style = Paint.Style.STROKE
        wandPaint.strokeWidth = 7f
        canvas.drawLine(hx, hy, tx, ty, wandPaint)
        wandPaint.strokeWidth = 12f
        canvas.drawLine(hx, hy, hx + (tx - hx) * 0.2f, hy + (ty - hy) * 0.2f, wandPaint)

        // The tip star pulses; trailing sparkles accumulate toward where the reply will flow.
        wandPaint.style = Paint.Style.FILL
        val bigR = floatArrayOf(15f, 23f, 30f, 23f)[frame]
        canvas.drawPath(star(tx, ty, bigR), wandPaint)

        val smalls = arrayOf(
            Triple(tx + 54f, y + 2f, 11f),
            Triple(tx + 100f, y + 24f, 8f),
            Triple(tx + 146f, y - 6f, 10f),
        )
        for (i in 0 until frame.coerceAtMost(3)) {
            val (sx, sy, sr) = smalls[i]
            canvas.drawPath(star(sx, sy, sr), wandPaint)
        }
    }

    /** A four-point sparkle centered at (cx, cy). */
    private fun star(cx: Float, cy: Float, outer: Float): Path {
        val inner = outer * 0.36f
        starPath.rewind()
        for (i in 0 until 8) {
            val r = if (i % 2 == 0) outer else inner
            val a = Math.toRadians((-90 + i * 45).toDouble())
            val px = cx + (r * Math.cos(a)).toFloat()
            val py = cy + (r * Math.sin(a)).toFloat()
            if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
        }
        starPath.close()
        return starPath
    }

    /** Materialize the reply in stepped alpha — it fades onto the page. */
    private fun fadeIn(gen: Int) {
        val steps = intArrayOf(50, 110, 170, 220, 255)
        steps.forEachIndexed { i, a ->
            postDelayed({
                if (gen != generation) return@postDelayed
                textAlpha = a
                invalidate()
                if (i == steps.size - 1) {
                    postDelayed({ if (gen == generation) fadeOut(gen) }, HOLD_MS)
                }
            }, FADE_STEP_MS * (i + 1))
        }
    }

    private fun fadeOut(gen: Int) {
        val steps = intArrayOf(150, 70, 0)
        steps.forEachIndexed { i, alpha ->
            postDelayed({
                if (gen != generation) return@postDelayed
                textAlpha = alpha
                if (alpha == 0) {
                    showing = false
                    musing = false
                    layout = null
                    onFinished?.invoke()
                }
                invalidate()
            }, FADE_STEP_MS * (i + 1))
        }
    }

    private fun buildLayout(text: String): StaticLayout {
        val textWidth = (width - 2 * MARGIN).coerceAtLeast(100)
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.35f)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (musing) {
            drawWand(canvas)
            return
        }
        val l = layout ?: return
        textPaint.alpha = textAlpha
        // Start under the writing when we know where it was, but keep the whole
        // reply on the page even if the writer filled the bottom of it.
        val desired = if (anchorY >= 0) anchorY else height * 0.12f
        val top = desired
            .coerceAtMost((height - l.height - MARGIN).toFloat())
            .coerceAtLeast(MARGIN.toFloat())
        canvas.save()
        canvas.translate(MARGIN.toFloat(), top)
        l.draw(canvas)
        canvas.restore()
    }

    private companion object {
        const val HOLD_MS = 16_000L
        const val FADE_STEP_MS = 300L
        const val MARGIN = 72
        const val GAP = 48f
        const val MUSE_MS = 600L
    }
}
