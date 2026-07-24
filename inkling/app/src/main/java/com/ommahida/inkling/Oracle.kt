package com.ommahida.inkling

import android.graphics.Bitmap
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Base64

/**
 * The Anthropic voice inside the page. Sends a snapshot of the handwriting to Claude and
 * returns both a transcription (kept as conversation history so the diary
 * remembers the session) and the reply to ink back.
 */
class Oracle(private val config: Config) : DiaryOracle {

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    private fun client(): AnthropicClient {
        val key = config.apiKey
        val cached = client
        if (cached != null && clientKey == key) return cached
        return AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(90))
            .build()
            .also { client = it; clientKey = key }
    }

    override fun consult(ink: Bitmap, history: List<Pair<String, String>>): DiaryTurn {
        val png = ByteArrayOutputStream().use { out ->
            ink.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val params = buildParams(config.model, config.persona, png, history)
        val message = client().messages().create(params)

        var text: String? = null
        for (block in message.content()) {
            block.text().ifPresent { text = it.text() }
        }
        val body = text ?: throw IllegalStateException(
            "no answer in response (stop_reason=${message.stopReason()})"
        )
        val json = JSONObject(body)
        return DiaryTurn(
            transcription = json.optString("transcription"),
            reply = json.getString("reply"),
        )
    }

    companion object {
        /**
         * Hand-written response schema. The SDK's class-based schema derivation
         * (outputConfig(Class)) needs Method.getAnnotatedReturnType(), which Android
         * only has from API 28 — the original A5X/A6X run API 27, so the schema is
         * spelled out here and the reply parsed with Android's built-in org.json.
         */
        private fun diaryOutputConfig(): OutputConfig {
            val schema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "transcription" to mapOf(
                        "type" to "string",
                        "description" to "The handwriting in the image, transcribed exactly as written",
                    ),
                    "reply" to mapOf(
                        "type" to "string",
                        "description" to "The diary's reply, at most sixty words",
                    ),
                ),
                "required" to listOf("transcription", "reply"),
                "additionalProperties" to false,
            )
            val formatSchema = JsonOutputFormat.Schema.builder()
            for ((k, v) in schema) formatSchema.putAdditionalProperty(k, JsonValue.from(v))
            return OutputConfig.builder()
                .format(JsonOutputFormat.builder().schema(formatSchema.build()).build())
                .build()
        }

        /** Pure request assembly — no Android UI types, no reflection. */
        fun buildParams(
            model: String,
            persona: String,
            png: ByteArray,
            history: List<Pair<String, String>>,
        ): MessageCreateParams {
            val imageBlock = ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                        .data(Base64.getEncoder().encodeToString(png))
                        .build()
                )
                .build()

            val builder = MessageCreateParams.builder()
                .model(Model.of(model))
                .maxTokens(400L)  // transcription + a ≤60-word reply; a tight cap bounds worst-case latency
                .system(persona)
                .outputConfig(diaryOutputConfig())

            for ((written, said) in history) {
                builder.addUserMessage("(handwritten) $written")
                builder.addMessage(
                    MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .content(said)
                        .build()
                )
            }

            builder.addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(imageBlock),
                    ContentBlockParam.ofText(
                        TextBlockParam.builder()
                            .text("Here is the fresh ink on your page. Read it and answer.")
                            .build()
                    ),
                )
            )

            return builder.build()
        }
    }
}
