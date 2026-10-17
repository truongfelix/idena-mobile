package io.github.truongfelix.idenanode

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * The app's language in the Node card: the phone's, or one of [Lang.choices], each named in its own language. A
 * choice shows the screen again in it ([Lang.choose]).
 */
@Composable
fun LanguageRow() {
    val activity = LocalContext.current as? Activity ?: return
    val chosen = remember { Lang.chosen(activity) }
    var menu by remember { mutableStateOf(false) }
    val choices = listOf(Lang.PHONE to t(R.string.lang_phone)) + Lang.choices
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t(R.string.lang_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            AssistChip(onClick = { menu = true }, label = { Text("${choices.first { it.first == chosen }.second}  ▾") })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                choices.forEach { (tag, name) ->
                    DropdownMenuItem(
                        leadingIcon = { RadioButton(selected = tag == chosen, onClick = null) },
                        text = { Text(name) },
                        onClick = {
                            menu = false
                            if (tag != chosen) Lang.choose(activity, tag)
                        },
                    )
                }
            }
        }
    }
}
