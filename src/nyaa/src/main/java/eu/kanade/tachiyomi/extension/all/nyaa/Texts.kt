package eu.kanade.tachiyomi.extension.all.nyaa

import eu.kanade.tachiyomi.lib.archive.I18n

/** Idioma de todos los textos de la extensión; [Nyaa] lo conecta con su ajuste. */
internal object Texts {
    @Volatile
    var i18n: I18n = I18n()
}

internal fun t(es: String, en: String) = Texts.i18n.t(es, en)
