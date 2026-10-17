package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeywordsTest {
    private val assets = File("src/main/assets")
    private val name = Regex("\"name\": \"([^\"]*)\"")

    private fun keywordsFile(values: String): String =
        Regex("<string name=\"keywords_file\">([^<]+)</string>").find(File("src/main/res/$values/strings_validation.xml").readText())!!.groupValues[1]

    /** Each language's list has a name for every index of the node's list: flip_words indexes into it. */
    @Test
    fun everyLanguageHasEveryKeyword() {
        val english = name.findAll(File(assets, "keywords.json").readText()).count()
        assertEquals(4616, english)
        for (values in listOf("values", "values-fr")) {
            val file = keywordsFile(values)
            val names = name.findAll(File(assets, file).readText()).map { it.groupValues[1] }.toList()
            assertEquals(file, english, names.size)
            assertTrue(file, names.all { it.isNotBlank() })
        }
        assertEquals("keywords-fr.json", keywordsFile("values-fr"))
    }
}
