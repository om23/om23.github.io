package com.ommahida.inkling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log

/**
 * Debug-only smoke test for the LLM backends, triggerable over adb without writing on the tablet:
 *
 *   adb shell am broadcast -n com.ommahida.inkling/.SelfTestReceiver \
 *     -a com.ommahida.inkling.SELFTEST --es provider openrouter --es text "hello, are you there?"
 *
 * Renders a test "handwriting" image, runs it through the chosen backend with the stored (encrypted)
 * key, and logs the outcome under tag `InklingSelfTest`. Never logs the API key. No-op in release
 * builds. The optional `provider` extra overrides the saved provider for this test only, so it
 * doesn't disturb the user's configuration.
 */
class SelfTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return

        val config = Config(context)
        val provider = intent.getStringExtra("provider") ?: config.provider
        val prompt = intent.getStringExtra("text") ?: "Is anyone there?"

        val oracle: DiaryOracle = when (provider) {
            Config.PROVIDER_OPENROUTER -> OpenRouterOracle(config)
            else -> Oracle(config)
        }
        val model = if (provider == Config.PROVIDER_OPENROUTER) config.openrouterModel else config.model
        val image = renderText(prompt)

        Thread {
            val t0 = System.currentTimeMillis()
            try {
                val turn = oracle.consult(image, emptyList())
                Log.i(
                    TAG,
                    "OK provider=$provider model=$model ms=${System.currentTimeMillis() - t0} " +
                        "transcription=\"${turn.transcription.take(60)}\" reply=\"${turn.reply.take(120)}\""
                )
            } catch (t: Throwable) {
                Log.e(TAG, "FAIL provider=$provider model=$model: ${t.javaClass.simpleName}: ${t.message}")
            }
        }.start()
    }

    private fun renderText(text: String): Bitmap {
        val bmp = Bitmap.createBitmap(760, 220, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
        }
        canvas.drawText(text, 30f, 130f, paint)
        return bmp
    }

    private companion object {
        const val TAG = "InklingSelfTest"
    }
}
