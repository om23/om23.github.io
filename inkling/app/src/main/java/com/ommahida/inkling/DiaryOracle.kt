package com.ommahida.inkling

import android.graphics.Bitmap

/** A backend that reads the handwriting and answers as the diary. */
interface DiaryOracle {
    /**
     * Blocking — call from a background dispatcher.
     * @param history prior exchanges in this session as (what was written, what the diary said).
     */
    fun consult(ink: Bitmap, history: List<Pair<String, String>>): DiaryTurn
}
