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

    private val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 30f, context.resources.displayMetrics
        )
        // resources.getFont is API 26+; both Supernote generations qualify (A5X/A6X
        // run Android 8.1 / API 27, Nomad and Manta run Android 11 / API 30).
        typeface = try {
            context.resources.getFont(R.font.dancing_script)
        } catch (_: Exception) {
            null
        } ?: Typeface.create("cursive", Typeface.NORMAL)
    }

    fun reveal(text: String) {
        generation++
        words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        visibleWords = 0
        textAlpha = 255
        layout = null
        invalidate()
        revealNext(generation)
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
        val visible = words.take(visibleWords).joinToString(" ")
        val textWidth = (width - 2 * MARGIN).coerceAtLeast(100)
        layout = StaticLayout.Builder.obtain(visible, 0, visible.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.35f)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        textPaint.alpha = textAlpha
        canvas.save()
        canvas.translate(MARGIN.toFloat(), height * 0.12f)
        l.draw(canvas)
        canvas.restore()
    }

    private companion object {
        const val WORD_MS = 320L
        const val HOLD_MS = 16_000L
        const val FADE_STEP_MS = 400L
        const val MARGIN = 72
    }
}
