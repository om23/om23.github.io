package com.ommahida.inkling

/** One diary exchange: what the handwriting said, and the diary's answer. */
data class DiaryTurn(
    val transcription: String,
    val reply: String,
)
