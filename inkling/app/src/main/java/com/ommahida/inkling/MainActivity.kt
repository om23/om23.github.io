package com.ommahida.inkling

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private lateinit var config: Config
    private lateinit var oracle: Oracle
    private lateinit var inkView: InkView
    private lateinit var replyView: ReplyView
    private lateinit var hint: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Session memory: (what was written, what the diary said). */
    private val history = mutableListOf<Pair<String, String>>()
    private var consulting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        config = Config(this)
        oracle = Oracle(config)

        inkView = InkView(this).apply {
            onInkRested = ::consultDiary
            onTwoFingerHold = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        replyView = ReplyView(this).apply {
            onFinished = { hint.text = "" }
        }
        hint = TextView(this).apply {
            setTextColor(Color.GRAY)
            setTypeface(Typeface.SERIF, Typeface.ITALIC)
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 0, 48, 64)
        }

        val root = FrameLayout(this)
        root.addView(replyView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(inkView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))
        setContentView(root)

        hint.text = getString(R.string.hint_first_run)
    }

    private fun consultDiary(ink: Bitmap) {
        if (consulting) return

        if (config.apiKey.isEmpty()) {
            hint.text = getString(R.string.hint_no_key)
            return
        }

        consulting = true
        inkView.restTimerEnabled = false
        if (replyView.isShowing()) replyView.dismiss()
        hint.text = getString(R.string.hint_thinking)
        inkView.fadeInk()

        scope.launch {
            val turn = try {
                withContext(Dispatchers.IO) { oracle.consult(ink, history.toList()) }
            } catch (t: Throwable) {
                // Throwable, not Exception: a NoSuchMethodError from a library on old
                // Android must degrade to the error reply, not kill the diary.
                Log.e("Inkling", "consult failed", t)
                null
            }
            consulting = false
            inkView.restTimerEnabled = true
            hint.text = ""
            if (turn != null) {
                history.add(turn.transcription to turn.reply)
                // The diary only remembers so much; keep the recent thread of conversation.
                while (history.size > 12) history.removeAt(0)
                replyView.reveal(turn.reply)
            } else {
                replyView.reveal(getString(R.string.reply_error))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
