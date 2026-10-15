package app.soiboi.engine.core

/** Case- and punctuation-insensitive comparison; tags and catalogs disagree about "&", accents and spacing. */
fun normaliseForMatch(value: String): String =
    value.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), "")

/** Album names without Apple's release-type suffix, so "White Keys - Single" matches "White Keys". */
fun albumKey(name: String): String =
    normaliseForMatch(name.replace(Regex("\\s+-\\s+(Single|EP)\\s*$", RegexOption.IGNORE_CASE), ""))

private val titleCredits = Regex(
    "\\s*[(\\[]\\s*(?:from\\s+[\"“”'‘’]|feat\\.?\\s|ft\\.?\\s|featuring\\s)[^)\\]]*[)\\]]" +
        "|\\s+-\\s+from\\s+[\"“”'‘’].*$",
    RegexOption.IGNORE_CASE,
)

/** [title] without soundtrack and featuring credits; versions like "(Live)" or "(Remix)" stay. */
fun bareSongTitle(title: String): String = title.replace(titleCredits, "").trim().ifEmpty { title }

private fun ownedPart(value: String): String {
    val latin = normaliseForMatch(value)
    if (latin.isNotEmpty()) return latin
    // Keep non-Latin names instead of reducing them to nothing.
    return value.lowercase().replace("&", "and").replace(Regex("\\s+"), "")
}

/** Must agree with `owned_key` in `pipeline/soiboi_pipeline/downloader.py`. */
fun ownedSongKey(artist: String, title: String): String =
    "${ownedPart(artist)}|${ownedPart(bareSongTitle(title))}"
