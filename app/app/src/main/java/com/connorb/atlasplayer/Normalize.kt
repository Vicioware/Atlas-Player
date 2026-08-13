package com.connorb.atlasplayer

import java.text.Normalizer
import java.util.Locale

private val COMBINING_MARKS_REGEX = Regex("\\p{Mn}+")
private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * Devuelve una versión normalizada del texto: sin acentos, en minúsculas
 * (locale ROOT), con espacios colapsados y recortados. Sirve para comparar
 * nombres de canal o valores de `group-title` de forma robusta.
 */
fun normalizeKey(text: String): String {
    val noAccents = Normalizer
        .normalize(text, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS_REGEX, "")

    return noAccents
        .lowercase(Locale.ROOT)
        .trim()
        .replace(WHITESPACE_REGEX, " ")
}
