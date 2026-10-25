package eu.kanade.tachiyomi.lib.archive

import java.util.Locale

/**
 * Idioma de los textos de la extensión. [setting] devuelve "es", "en" o "auto"
 * (español si el teléfono está en español, inglés en cualquier otro caso).
 */
class I18n(private val setting: () -> String = { AUTO }) {

    val english: Boolean
        get() = when (setting()) {
            "en" -> true
            "es" -> false
            else -> Locale.getDefault().language != "es"
        }

    fun t(es: String, en: String) = if (english) en else es

    companion object {
        const val PREF_KEY = "language"
        const val AUTO = "auto"
        val ENTRIES = arrayOf<CharSequence>("Auto (system) / Automático", "English", "Español")
        val VALUES = arrayOf<CharSequence>(AUTO, "en", "es")
    }
}
