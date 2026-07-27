package com.ommahida.inkling

import android.content.Context

class Config(context: Context) {

    private val prefs = context.getSharedPreferences("inkling", Context.MODE_PRIVATE)

    /** Which backend answers: [PROVIDER_ANTHROPIC] (Claude direct) or [PROVIDER_OPENROUTER]. */
    var provider: String
        get() = prefs.getString(KEY_PROVIDER, PROVIDER_ANTHROPIC) ?: PROVIDER_ANTHROPIC
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    /** A key entered in settings wins; otherwise fall back to the one baked in at build time. */
    var apiKey: String
        get() = readSecret(KEY_API).ifEmpty { BuildConfig.BUILT_IN_API_KEY }
        set(value) = writeSecret(KEY_API, value)

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    var openrouterKey: String
        get() = readSecret(KEY_OR_KEY).ifEmpty { BuildConfig.BUILT_IN_OPENROUTER_KEY }
        set(value) = writeSecret(KEY_OR_KEY, value)

    var openrouterModel: String
        get() = prefs.getString(KEY_OR_MODEL, DEFAULT_OPENROUTER_MODEL) ?: DEFAULT_OPENROUTER_MODEL
        set(value) = prefs.edit()
            .putString(KEY_OR_MODEL, value.trim().ifEmpty { DEFAULT_OPENROUTER_MODEL }).apply()

    /** Does the currently selected provider have a usable key? */
    val hasActiveKey: Boolean
        get() = when (provider) {
            PROVIDER_OPENROUTER -> openrouterKey.isNotEmpty()
            else -> apiKey.isNotEmpty()
        }

    /** Read a key stored encrypted-at-rest, tolerating a pre-encryption plaintext value. */
    private fun readSecret(key: String): String {
        val stored = prefs.getString(key, "") ?: ""
        if (stored.isEmpty()) return ""
        // decrypt() returns null for a legacy plaintext value → fall through to the raw string.
        return SecureStore.decrypt(stored) ?: stored
    }

    /** Store a key encrypted; if the Keystore is unavailable, store the raw value rather than lose it. */
    private fun writeSecret(key: String, value: String) {
        val trimmed = value.trim()
        val toStore = if (trimmed.isEmpty()) "" else (SecureStore.encrypt(trimmed) ?: trimmed)
        prefs.edit().putString(key, toStore).apply()
    }

    var persona: String
        get() = prefs.getString(KEY_PERSONA, DEFAULT_PERSONA) ?: DEFAULT_PERSONA
        set(value) = prefs.edit().putString(KEY_PERSONA, value.trim().ifEmpty { DEFAULT_PERSONA }).apply()

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_OR_KEY = "openrouter_key"
        private const val KEY_OR_MODEL = "openrouter_model"
        private const val KEY_PERSONA = "persona"

        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENROUTER = "openrouter"

        const val DEFAULT_MODEL = "claude-opus-4-8"
        // Any slug from openrouter.ai/models that accepts image input. gpt-4o is a stable default.
        const val DEFAULT_OPENROUTER_MODEL = "openai/gpt-4o"

        val DEFAULT_PERSONA = """
            You are Inkling, the quiet spirit that lives inside this paper notebook.
            Someone is writing to you by hand; each image you receive is a photograph of
            handwriting freshly inked onto your page.

            Read the handwriting carefully, then answer as the diary itself would:
            warm, curious, attentive, and a little mysterious. Keep replies to at most
            sixty words — this is a page, not a screen. Never mention being an AI,
            a model, or an assistant; you are the notebook.

            If the page holds only a question mark, explain briefly how to use the
            diary: write and rest the pen to speak, use the eraser end to unwrite,
            hold two fingers on the page to reach the settings drawer.

            If the handwriting is illegible, say so gently and ask them to write again.
        """.trimIndent()
    }
}
