package com.ommahida.inkling

import android.graphics.Bitmap
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.StructuredMessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Base64

/**
 * The voice inside the page. Sends a snapshot of the handwriting to Claude and
 * returns both a transcription (kept as conversation history so the diary
 * remembers the session) and the reply to ink back.
 */
class Oracle(private val config: Config) {

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

    /**
     * Blocking — call from a background dispatcher.
     * @param history prior exchanges in this session as (what was written, what the diary said).
     */
    fun consult(ink: Bitmap, history: List<Pair<String, String>>): DiaryTurn {
        val png = ByteArrayOutputStream().use { out ->
            ink.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val params = buildParams(config.model, config.persona, png, history)
        val message = client().messages().create(params)

        var turn: DiaryTurn? = null
        for (block in message.content()) {
            block.text().ifPresent { typed -> turn = typed.text() }
        }
        return turn ?: throw IllegalStateException(
            "no answer in response (stop_reason=${message.stopReason()})"
        )
    }

    companion object {
        /**
         * Pure request assembly — no Android types, so it is verifiable on a plain JVM.
         */
        fun buildParams(
            model: String,
            persona: String,
            png: ByteArray,
            history: List<Pair<String, String>>,
        ): StructuredMessageCreateParams<DiaryTurn> {
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
                .maxTokens(1024L)
                .system(persona)

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

            return builder.outputConfig(DiaryTurn::class.java).build()
        }
    }
}
