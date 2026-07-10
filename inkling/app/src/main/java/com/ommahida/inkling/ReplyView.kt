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
 * Renders the diary's answer in a flowing hand, one word at a time,
 * holds it long enough to read, then fades it back into the page.
 */
class ReplyView(context: Context) : View(context) {

    var onFinished: (() -> Unit)? = null

    private var words: List<String> = emptyList()
    private var visibleWords = 0
    private var layout: StaticLayout? = null
    private var textAlpha = 255

    // Posted callbacks capture the generation they belong to; bumping it
    // invalidates everything in flight without touching other views' handlers.
    private var generation = 0

    /** Where the top of the reply wants to sit (view Y); < 0 means the default upper-page spot. */
    private var anchorY = -1f

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
        words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        visibleWords = 0
        textAlpha = 255
        layout = null
        invalidate()
        revealNext(generation)
    }

    /**
     * While the diary composes, pulse ink dots where the reply will appear so the
     * wait reads as thinking rather than silence. Cancelled by the next [reveal].
     */
    fun showMusing(belowY: Float = -1f) {
        generation++
        anchorY = if (belowY >= 0) belowY + GAP else -1f
        words = emptyList()
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

    fun isShowing(): Boolean = words.isNotEmpty()

    /** Cut the reveal/hold short (e.g. the writer picked the pen back up). */
    fun dismiss() {
        if (!isShowing()) return
        generation++
        visibleWords = words.size
        rebuildLayout()
        fadeOut(generation)
    }

    private fun revealNext(gen: Int) {
        postDelayed({
            if (gen != generation) return@postDelayed
            if (visibleWords < words.size) {
                visibleWords++
                rebuildLayout()
                invalidate()
                revealNext(gen)
            } else {
                postDelayed({ if (gen == generation) fadeOut(gen) }, HOLD_MS)
            }
        }, WORD_MS)
    }

    private fun fadeOut(gen: Int) {
        val steps = intArrayOf(150, 70, 0)
        steps.forEachIndexed { i, alpha ->
            postDelayed({
                if (gen != generation) return@postDelayed
                textAlpha = alpha
                if (alpha == 0) {
                    words = emptyList()
                    layout = null
                    onFinished?.invoke()
                }
                invalidate()
            }, FADE_STEP_MS * (i + 1))
        }
    }

    private fun rebuildLayout() {
        layout = buildLayout(words.take(visibleWords).joinToString(" "))
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
        const val WORD_MS = 320L
        const val HOLD_MS = 16_000L
        const val FADE_STEP_MS = 400L
        const val MARGIN = 72
        const val GAP = 48f
        const val MUSE_MS = 600L
    }
}
