package io.github.truongfelix.idenanode

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import java.io.File
import java.util.Locale

/**
 * The app's language: the phone's (French when the phone is set to French, English otherwise), or the one chosen in
 * the Node card. The choice is a small file, as [NodeSettings], so that the node process reads it too. Only the texts
 * change: numbers and dates keep the phone's own format, so [Locale.setDefault] is left alone.
 */
object Lang {
    /** The phone's language. */
    const val PHONE = ""

    /** The languages the app has, by tag, each named in its own language. */
    val choices = listOf("en" to "English", "fr" to "Français")

    private fun file(context: Context) = File(context.filesDir, "language")

    /** The chosen language's tag, or [PHONE]. */
    fun chosen(context: Context): String =
        runCatching { file(context).readText().trim() }.getOrDefault(PHONE).takeIf { tag -> choices.any { it.first == tag } } ?: PHONE

    /** Saves [tag] ([PHONE] for the phone's language) and shows [activity] again in it. */
    fun choose(activity: Activity, tag: String) {
        if (tag == PHONE) file(activity).delete() else file(activity).writeText(tag)
        Txt.init(activity)
        activity.recreate()
    }

    /** [base] with the chosen language's texts; [base] itself for the phone's language. */
    fun wrap(base: Context): Context {
        val tag = chosen(base)
        if (tag == PHONE) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }
}

/** An activity shown in the chosen language ([Lang]); every activity of the app extends it. */
abstract class AppActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }
}

/** Reads the texts in the chosen language in each of the app's processes (the screens', the node's) at its start. */
class IdenaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Txt.init(this)
    }
}
