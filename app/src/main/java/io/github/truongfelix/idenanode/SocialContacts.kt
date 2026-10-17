package io.github.truongfelix.idenanode

import androidx.compose.ui.semantics.contentDescription
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import org.json.JSONObject
import java.io.IOException
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Edit

/** A contact: the avatar, the name (or the short address), the short address under a name; a tap opens the profile. */
@Composable
fun PersonRow(person: Person, trailing: @Composable () -> Unit = {}, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(person.address, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(person.name.ifEmpty { shortAddress(person.address) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Following shows on the row's chip.
                if (person.name.isNotEmpty()) {
                    Text(shortAddress(person.address), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing()
        }
    }
}

/** A private name for [person]'s address; an empty name removes it. */
@Composable
fun NameDialog(person: Person, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(person.address) { mutableStateOf(person.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (person.name.isEmpty()) t(R.string.socialb_name_address, shortAddress(person.address)) else t(R.string.socialb_rename, person.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t(R.string.socialb_name_note))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.takeWhole(Person.MAX_NAME) },
                    label = { Text(t(R.string.socialb_name)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(cleanName(name)) }) { Text(if (cleanName(name).isEmpty() && person.name.isNotEmpty()) t(R.string.socialb_remove_name) else t(R.string.socialb_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.common_cancel)) } },
    )
}
