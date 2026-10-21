package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class TranslationsTest {
    /** Each text of a folder by name (a plural by name and quantity), as Android reads it. */
    private fun texts(folder: String): Map<String, String> {
        val texts = HashMap<String, String>()
        for (file in TranslationFiles.files(folder)) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            for (e in doc.childElements()) {
                if (e.getAttribute("translatable") == "false") continue
                val name = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> assertTrue("$name given twice in $folder", texts.put(name, unescape(e.textContent)) == null)
                    "plurals" -> e.childElements().forEach { texts["$name/${it.getAttribute("quantity")}"] = unescape(it.textContent) }
                }
            }
        }
        return texts
    }

    // Placeholders are numbered (`%1$s`), so that a literal "51% of" is not read as one.
    private val placeholder = Regex("""%\d+\$[-#+0,(]*\d*(\.\d+)?[a-zA-Z]""")
    private val unnumbered = Regex("""%[a-zA-Z]""")

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyTextHasItsFrenchVersion() {
        val english = texts("values")
        val french = texts("values-fr")
        assertTrue("only ${english.size} English texts", english.size > 1)
        assertEquals("texts without a French version", emptySet<String>(), english.keys - french.keys)
        // French plurals also have "many" (a million and more), which English has not.
        assertEquals("French texts without an English one", emptySet<String>(), french.keys - english.keys - manyOf(english.keys))
    }

    private fun manyOf(keys: Set<String>) = keys.filter { it.endsWith("/other") }.map { it.removeSuffix("/other") + "/many" }.toSet()

    @Test
    fun everyFrenchPluralHasItsManyForm() {
        val english = texts("values")
        assertEquals("French plurals without \"many\"", emptySet<String>(), manyOf(english.keys) - texts("values-fr").keys)
    }

    @Test
    fun theFrenchTextsHaveTheEnglishPlaceholders() {
        val french = texts("values-fr")
        val wrong = texts("values").filter { (name, text) -> french[name]?.let { placeholders(it) != placeholders(text) } == true }
        assertEquals("texts whose French version has other placeholders", emptyMap<String, String>(), wrong)
    }

    // The texts name the social network "Idena Social", never by the website's address (owner 2026-10-21).
    @Test
    fun theSocialNetworkIsIdenaSocialInTheTexts() {
        for (folder in listOf("values", "values-fr")) {
            val wrong = texts(folder).filter { (_, text) -> text.contains("idena.social", ignoreCase = true) }
            assertEquals("texts of $folder naming idena.social", emptyMap<String, String>(), wrong)
        }
    }

    @Test
    fun everyPlaceholderIsNumbered() {
        val wrong = (texts("values") + texts("values-fr")).filter { (_, text) -> unnumbered.containsMatchIn(text) }
        assertEquals("texts with unnumbered placeholders", emptyMap<String, String>(), wrong)
    }
}
