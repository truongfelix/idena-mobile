package io.github.truongfelix.idenanode

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * The interface texts, from the translation files (`res/values*`), in the chosen language ([Lang]). [t] and [tp] work
 * outside a composable too (the models, the node's notification), so that every text is looked up the same way.
 */
object Txt {
    /** Looks a text up by its resource id. */
    interface Lookup {
        fun text(@StringRes id: Int, args: Array<out Any>): String
        fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any>): String
    }

    @Volatile
    private var lookup: Lookup? = null

    // The language [lookup] reads, for [refresh].
    @Volatile
    private var tag: String? = null

    /** Reads the texts in the chosen language: at each process's start ([IdenaApp]) and after a change. */
    fun init(context: Context) {
        tag = Lang.chosen(context)
        val resources = Lang.wrap(context.applicationContext).resources
        lookup = object : Lookup {
            override fun text(id: Int, args: Array<out Any>) = if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
            override fun plural(id: Int, count: Int, args: Array<out Any>) = resources.getQuantityString(id, count, *args)
        }
    }

    /** Reads the texts again when another process changed the language (the node's notification). */
    fun refresh(context: Context) {
        if (Lang.chosen(context) != tag) init(context)
    }

    /** For the JVM tests: the English texts read from the files, installed by the tests' `JvmTexts`. */
    fun install(lookup: Lookup) {
        this.lookup = lookup
    }

    internal fun lookup(): Lookup = lookup ?: testLookup() ?: error("Txt.init was not called")

    // The JVM tests have no Android resources: their `JvmTexts` (test sources only) reads the English files, so that
    // every test that meets a text works without its own set-up.
    private fun testLookup(): Lookup? = runCatching {
        Class.forName("io.github.truongfelix.idenanode.JvmTexts").getField("INSTANCE").get(null) as Lookup
    }.getOrNull()?.also { lookup = it }
}

/** The text [id] in the app's language; with [args], `%1$s`… filled in. */
fun t(@StringRes id: Int, vararg args: Any): String = Txt.lookup().text(id, args)

/** The plural text [id] for [count] in the app's language; with [args], `%1$s`… filled in. */
fun tp(@PluralsRes id: Int, count: Int, vararg args: Any): String = Txt.lookup().plural(id, count, args)
