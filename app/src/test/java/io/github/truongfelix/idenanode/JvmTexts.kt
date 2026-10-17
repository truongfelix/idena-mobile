package io.github.truongfelix.idenanode

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The English texts for the JVM tests, which have no Android resources: read from the `res/values` files and found by the
 * names of the ids in [R]. [Txt] installs it by itself.
 */
object JvmTexts : Txt.Lookup {
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    init {
        for (file in TranslationFiles.files("values")) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            for (e in doc.childElements()) when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = unescape(e.textContent)
                "plurals" -> plurals[e.getAttribute("name")] =
                    e.childElements().associate { it.getAttribute("quantity") to unescape(it.textContent) }
            }
        }
    }

    private val stringNames by lazy { R.string::class.java.fields.associate { it.getInt(null) to it.name } }
    private val pluralNames by lazy { R.plurals::class.java.fields.associate { it.getInt(null) to it.name } }

    override fun text(id: Int, args: Array<out Any>): String {
        val text = strings[stringNames[id]] ?: error("no English text for id $id")
        return if (args.isEmpty()) text else text.format(*args)
    }

    override fun plural(id: Int, count: Int, args: Array<out Any>): String {
        val forms = plurals[pluralNames[id]] ?: error("no English plural for id $id")
        val text = (if (count == 1) forms["one"] else null) ?: forms.getValue("other")
        return text.format(*args)
    }
}

/** The translation files of the app, for [JvmTexts] and the translation tests. */
object TranslationFiles {
    /** The XML files of `res/<folder>` that hold texts. */
    fun files(folder: String): List<File> {
        // Gradle runs the tests in the module's folder; an IDE may run them in the project's.
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        return File(res, folder).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
    }
}

internal fun Element.childElements(): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

/** A text as Android reads it from the file: the quotes and backslash escapes taken out. */
internal fun unescape(raw: String): String {
    val s = raw.trim().let { if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it }
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            out.append(when (val n = s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> n })
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}
