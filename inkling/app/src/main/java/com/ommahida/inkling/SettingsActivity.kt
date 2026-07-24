package com.ommahida.inkling

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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

        label("Provider")
        val providerIds = listOf(Config.PROVIDER_ANTHROPIC, Config.PROVIDER_OPENROUTER)
        val providerLabels = listOf("Anthropic (Claude direct)", "OpenRouter")
        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_item,
                providerLabels,
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(providerIds.indexOf(config.provider).coerceAtLeast(0))
        }
        column.addView(providerSpinner)

        label("Anthropic API key")
        val keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(config.apiKey)
            hint = "sk-ant-…"
        }
        column.addView(keyField)

        label("Model")
        val modelIds = mutableListOf(
            "claude-opus-4-8",
            "claude-sonnet-5",
            "claude-haiku-4-5",
        )
        val modelLabels = mutableListOf(
            "Opus 4.8 — deepest voice, slower",
            "Sonnet 5 — balanced",
            "Haiku 4.5 — fastest",
        )
        // A model set some other way (rebuild, future import) still shows up as a choice.
        if (config.model !in modelIds) {
            modelIds.add(config.model)
            modelLabels.add(config.model)
        }
        val modelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_item,
                modelLabels,
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(modelIds.indexOf(config.model))
        }
        column.addView(modelSpinner)

        label("OpenRouter API key")
        val orKeyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(config.openrouterKey)
            hint = "sk-or-…"
        }
        column.addView(orKeyField)

        label("OpenRouter model")
        val orModelField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(config.openrouterModel)
            hint = Config.DEFAULT_OPENROUTER_MODEL
        }
        column.addView(orModelField)
        column.addView(TextView(this).apply {
            text = "any vision-capable slug from openrouter.ai/models — e.g. anthropic/claude-sonnet-4, google/gemini-2.5-flash"
            setTextColor(Color.GRAY)
            textSize = 12f
            setPadding(0, 4, 0, 0)
        })

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
                config.provider = providerIds[providerSpinner.selectedItemPosition]
                config.apiKey = keyField.text.toString()
                config.model = modelIds[modelSpinner.selectedItemPosition]
                config.openrouterKey = orKeyField.text.toString()
                config.openrouterModel = orModelField.text.toString()
                config.persona = personaField.text.toString()
                finish()
            }
        })

        column.addView(TextView(this).apply {
            text = "Your key is stored only on this device and sent only to the provider you select " +
                "(api.anthropic.com or openrouter.ai)."
            setTextColor(Color.GRAY)
            textSize = 13f
            setPadding(0, 24, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(column) })
    }
}
