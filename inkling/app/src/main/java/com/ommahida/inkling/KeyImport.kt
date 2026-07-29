package com.ommahida.inkling

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Properties

/**
 * One-shot key provisioning without typing on the e-ink screen and without baking a secret into the
 * APK. Push a small properties file to the app's external files dir over adb, e.g.:
 *
 *   adb push inkling.properties /sdcard/Android/data/com.ommahida.inkling/files/inkling.properties
 *
 * On the next launch (or focus gain) the app reads it, stores the keys **encrypted** via
 * [SecureStore], and **deletes the plaintext file** so it doesn't linger on shared storage.
 * The file lives in the app-specific external dir (no storage permission needed) and is never
 * committed to git.
 *
 * Recognized keys (all optional): `provider`, `inkling.apiKey`, `inkling.openrouterKey`,
 * `openrouter.model`.
 */
object KeyImport {

    private const val TAG = "InklingKeyImport"
    private const val FILE = "inkling.properties"

    /** @return true if a file was found and applied (whether or not it changed anything). */
    fun importIfPresent(context: Context, config: Config): Boolean {
        val dir = context.getExternalFilesDir(null) ?: return false
        val file = File(dir, FILE)
        if (!file.exists()) return false

        return try {
            val props = Properties().apply { file.inputStream().use { load(it) } }
            val imported = mutableListOf<String>()

            props.getProperty("provider")?.trim()?.lowercase()?.let {
                if (it == Config.PROVIDER_ANTHROPIC || it == Config.PROVIDER_OPENROUTER) {
                    config.provider = it; imported += "provider"
                }
            }
            firstNonBlank(props, "inkling.apiKey", "apiKey")?.let {
                config.apiKey = it; imported += "anthropic key"
            }
            firstNonBlank(props, "inkling.openrouterKey", "openrouterKey")?.let {
                config.openrouterKey = it; imported += "openrouter key"
            }
            firstNonBlank(props, "openrouter.model", "openrouterModel")?.let {
                config.openrouterModel = it; imported += "openrouter model"
            }

            Log.i(TAG, "imported ${imported.ifEmpty { listOf("nothing") }} from $FILE")
            imported.isNotEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "import failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            // Always remove the plaintext file, even on parse failure.
            if (!file.delete()) Log.w(TAG, "could not delete $FILE after import")
        }
    }

    private fun firstNonBlank(props: Properties, vararg keys: String): String? {
        for (k in keys) {
            val v = props.getProperty(k)?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }
}
