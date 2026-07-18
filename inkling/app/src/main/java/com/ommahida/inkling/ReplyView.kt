package com.ommahida.inkling

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View

/**
 * Renders the diary's answer in a flowing hand. While the diary composes, ink dots pulse where the
 * reply will land; when it arrives the whole reply materializes with a gentle fade-in, holds long
 * enough to read, then dissolves back into the page.
 */
class ReplyView(context: Context) : View(context) {

    var onFinished: (() -> Unit)? = null

    private var layout: StaticLayout? = null
    private var textAlpha = 255
    private var showing = false

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
        if (body.isEmpty()) { showing = false; return }
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
     * While the diary composes, pulse ink dots where the reply will appear so the
     * wait reads as thinking rather than silence. Cancelled by the next [reveal].
     */
    fun showMusing(belowY: Float = -1f) {
        generation++
        anchorY = if (belowY >= 0) belowY + GAP else -1f
        showing = false
        textAlpha = 255
        layout = null
        invalidate()
        museNext(generation, 0)
    }

    private fun museNext(gen: Int, beat: Int) {
        postDelayed({
            if (gen != generation) return@postDelayed
            val dots = beat % 3 + 1
            layout = buildLayout("· ".repeat(dots).trim())
            invalidate()
            museNext(gen, beat + 1)
        }, MUSE_MS)
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
