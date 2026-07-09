package com.ommahida.inkling

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The settings drawer: API key, model, and the diary's persona.
 * Built in code — three fields don't warrant a layout file.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = Config(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.WHITE)
        }

        fun label(text: String) = column.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.BLACK)
            textSize = 16f
            setPadding(0, 32, 0, 8)
        })

        label("Anthropic API key")
        val keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(config.apiKey)
            hint = "sk-ant-…"
        }
        column.addView(keyField)

        label("Model")
        val modelField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(config.model)
            hint = Config.DEFAULT_MODEL
        }
        column.addView(modelField)

        label("Persona (the diary's voice)")
        val personaField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            setText(config.persona)
        }
        column.addView(personaField)

        column.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                config.apiKey = keyField.text.toString()
                config.model = modelField.text.toString()
                config.persona = personaField.text.toString()
                finish()
            }
        })

        column.addView(TextView(this).apply {
            text = "Your key is stored only on this device and sent only to api.anthropic.com."
            setTextColor(Color.GRAY)
            textSize = 13f
            setPadding(0, 24, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(column) })
    }
}
