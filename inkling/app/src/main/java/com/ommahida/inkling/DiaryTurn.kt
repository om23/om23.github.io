package com.ommahida.inkling

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * Structured output for one exchange. Plain mutable fields + no-arg constructor
 * so Jackson (bundled with the Anthropic SDK) can instantiate it reflectively.
 */
@JsonClassDescription("One diary exchange: what the handwriting said, and the diary's answer")
class DiaryTurn {
    @JsonPropertyDescription("The handwriting in the image, transcribed exactly as written")
    var transcription: String = ""

    @JsonPropertyDescription("The diary's reply, at most sixty words")
    var reply: String = ""
}
