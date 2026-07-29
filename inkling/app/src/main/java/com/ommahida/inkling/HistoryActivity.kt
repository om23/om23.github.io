package com.ommahida.inkling

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A quiet log of past exchanges — what you wrote and what the diary answered. */
class HistoryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val history = DiaryHistory(this)
        val entries = history.load().reversed() // newest first

        val script: Typeface = try {
            resources.getFont(R.font.dancing_script)
        } catch (_: Exception) {
            Typeface.create("cursive", Typeface.NORMAL)
        }
        val stamp = SimpleDateFormat("MMM d · h:mm a", Locale.getDefault())

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 56, 64, 56)
            setBackgroundColor(Color.WHITE)
        }

        column.addView(TextView(this).apply {
            text = "History"
            setTextColor(Color.BLACK)
            textSize = 22f
            setTypeface(Typeface.SERIF, Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        })

        if (entries.isEmpty()) {
            column.addView(TextView(this).apply {
                text = "The diary remembers nothing yet."
                setTextColor(Color.GRAY)
                setTypeface(Typeface.SERIF, Typeface.ITALIC)
                textSize = 16f
            })
        } else {
            for (e in entries) {
                column.addView(TextView(this).apply {
                    text = stamp.format(Date(e.time))
                    setTextColor(Color.parseColor("#94a3b8"))
                    textSize = 11f
                    setPadding(0, 20, 0, 4)
                })
                if (e.written.isNotBlank()) {
                    column.addView(TextView(this).apply {
                        text = e.written
                        setTextColor(Color.parseColor("#64748b"))
                        setTypeface(Typeface.SERIF, Typeface.ITALIC)
                        textSize = 15f
                        setPadding(0, 0, 0, 6)
                    })
                }
                column.addView(TextView(this).apply {
                    text = e.reply
                    setTextColor(Color.BLACK)
                    typeface = script
                    textSize = 22f
                })
                column.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#e2e8f0"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { topMargin = 20 }
                })
            }

            // Two-tap clear so the whole diary log isn't wiped by an accidental touch.
            column.addView(Button(this).apply {
                text = "Clear history"
                var armed = false
                setOnClickListener {
                    if (!armed) {
                        armed = true
                        text = "Tap again to erase everything"
                        postDelayed({ armed = false; text = "Clear history" }, 4000)
                    } else {
                        history.clear()
                        finish()
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 40 }
            })
        }

        column.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        })

        setContentView(ScrollView(this).apply {
            addView(column)
            isFillViewport = true
        })
    }
}
