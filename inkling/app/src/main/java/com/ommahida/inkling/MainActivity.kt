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
    private lateinit var anthropicOracle: Oracle
    private lateinit var openRouterOracle: OpenRouterOracle
    private lateinit var firmwareInk: FirmwareInk
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
        // Pick up a key pushed over adb (see KeyImport) before anything reads the config.
        KeyImport.importIfPresent(this, config)
        anthropicOracle = Oracle(config)
        openRouterOracle = OpenRouterOracle(config)
        firmwareInk = FirmwareInk(this)

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

        // InkView is a SurfaceView (renders behind the window through a punched hole),
        // so the reply and hint must come after it to composite on top.
        val root = FrameLayout(this)
        root.addView(inkView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(replyView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))
        setContentView(root)

        hint.text = getString(R.string.hint_first_run)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // A key can be pushed over adb while the app is open; pick it up on focus gain.
            if (KeyImport.importIfPresent(this, config) && !consulting && !replyView.isShowing()) {
                hint.text = if (config.hasActiveKey) getString(R.string.hint_first_run)
                            else getString(R.string.hint_no_key)
            }
            // The firmware resets pen ownership when focus changes, so (re)claim on every focus gain.
            inkView.firmwareInkActive = firmwareInk.setup()
            inkView.invalidate()
        }
    }

    private fun consultDiary(ink: Bitmap) {
        if (consulting) return

        if (!config.hasActiveKey) {
            hint.text = getString(R.string.hint_no_key)
            return
        }
        val oracle: DiaryOracle =
            if (config.provider == Config.PROVIDER_OPENROUTER) openRouterOracle else anthropicOracle

        consulting = true
        inkView.restTimerEnabled = false
        hint.text = ""
        // Snapshot the strokes into a fading layer first, then clear the firmware overlay a beat
        // later so the snapshot has taken over the pixels — the ink dissolves instead of blinking off.
        inkView.dissolveInk()
        inkView.postDelayed({ firmwareInk.clearAll() }, 60)
        replyView.showMusing(inkView.lastInkBottom)

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
                replyView.reveal(turn.reply, inkView.lastInkBottom)
            } else {
                replyView.reveal(getString(R.string.reply_error), inkView.lastInkBottom)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        firmwareInk.teardown()
        scope.cancel()
    }
}
