package io.github.lovislog.lexi

import java.text.Normalizer
import java.util.Locale

private val IpaWhitespace = Regex("[\\s\\p{Zs}]+")
private val IpaGroups = Regex("/[^/\\r\\n]+/|\\[[^\\]\\r\\n]+\\]")
private val IpaVariantSeparator = Regex("[\\s|,;·/]*")
private val AsciiSecondaryStress = Regex("(?:^|(?<=\\s)),(?=\\S)")
private val PaddedStress = Regex("([ˈˌ]) +")
private val MissingIpa = setOf("", "null", "n/a", "na", "-", "—", "无", "暂无")

internal fun formatIpa(value: String): String {
    val text = Normalizer.normalize(value, Normalizer.Form.NFC)
        .replace('／', '/').replace('［', '[').replace('］', ']')
        .trim().trim('\u0060').trim()
    if (text.lowercase(Locale.ROOT) in MissingIpa) return ""

    val groups = IpaGroups.findAll(text).map { it.value }.toList()
    if (groups.size > 1 && IpaVariantSeparator.matches(IpaGroups.replace(text, ""))) {
        return groups.map(::formatIpa).filter(String::isNotBlank).joinToString(" · ")
    }

    val narrow = text.startsWith('[') && text.endsWith(']')
    val symbols = text.trim { it.isWhitespace() || it == '/' || it == '[' || it == ']' }
        .replace(IpaWhitespace, " ")
    if (symbols.lowercase(Locale.ROOT) in MissingIpa) return ""
    val normalized = symbols.replace(':', 'ː').replace('：', 'ː')
        .replace('\'', 'ˈ').replace('’', 'ˈ').replace('‘', 'ˈ')
        .replace(AsciiSecondaryStress, "ˌ")
        .replace(PaddedStress, "$1")
    return if (narrow) "[$normalized]" else "/$normalized/"
}
