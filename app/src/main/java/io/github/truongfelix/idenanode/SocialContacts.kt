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
            Avatar(person.address, 40.dp)
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

/**
 * The Inbox's Contacts: the names given and the people followed, kept on the phone only.
 * A tap opens the profile; ✎ (name) and Follow there or here. Contacts are added from their profile.
 */
@Composable
fun PeopleList(model: SocialModel, modifier: Modifier, onName: (Person) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val people = remember(model.people) { sortedPeople(model.people) }
    // What the last export or import did, and whether the explanations (ⓘ) are open.
    var note by remember { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf(false) }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Contacts") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Names and follows are private: they stay on this phone and are never published. To add someone, open " +
                            "their profile (a tap on an address, or paste the address in the feed's search) and tap ✎ (name) or Follow.",
                    )
                    Text(
                        "Export writes the list to a file of your choice (to keep it, or to move it to another phone); whoever " +
                            "gets the file sees your names. Import adds a file's contacts to this list: nothing here is removed.",
                    )
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } },
        )
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val list = model.people
        scope.launch {
            note = withContext(Dispatchers.IO) {
                runCatching {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("the file cannot be written")
                    out.use { it.write(peopleToJson(list).toString(2).toByteArray(Charsets.UTF_8)) }
                }.fold({ "Exported ${peopleCount(list.size)}." }, { "Export failed: ${it.message}" })
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("the file cannot be read")
                    val bytes = input.use { stream ->
                        // At most one byte more than allowed (InputStream.readNBytes needs Android 13).
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (out.size() <= MAX_PEOPLE_FILE) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    if (bytes.size > MAX_PEOPLE_FILE) throw IOException("the file is too large")
                    val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    if (json?.optJSONArray("people") == null) throw IOException("this is not an exported contact list")
                    peopleFromJson(json)
                }
            }
            note = read.fold(
                { imported ->
                    val result = model.importPeople(imported)
                    "Imported: ${peopleCount(result.added)} added, ${result.changed} changed."
                },
                { "Import failed: ${it.message}" },
            )
        }
    }
    LazyColumn(modifier, state = model.peopleState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "about") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Export", enabled = people.isNotEmpty()) { exporter.launch("idena-contacts.json") }
                    Pill("Import", enabled = true) { importer.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { about = true }) { Text("ⓘ", fontSize = 20.sp) }
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (people.isEmpty()) item(key = "empty") { Text("No contacts yet.", style = MaterialTheme.typography.bodyMedium) }
        items(people, key = { it.address }) { person ->
            PersonRow(person, trailing = {
                IconButton(onClick = { onName(person) }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
                FilterChip(
                    selected = person.following,
                    onClick = { model.setPerson(person.copy(following = !person.following)) },
                    label = { Text(if (person.following) "Following" else "Follow") },
                )
            }) { model.openProfile(person.address) }
        }
    }
}

/** The largest contact file imported (about 10,000 contacts). */
private const val MAX_PEOPLE_FILE = 2_000_000

private fun peopleCount(n: Int): String = if (n == 1) "1 contact" else "$n contacts"

/** A private name for [person]'s address; an empty name removes it. */
@Composable
fun NameDialog(person: Person, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(person.address) { mutableStateOf(person.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (person.name.isEmpty()) "Name ${shortAddress(person.address)}" else "Rename ${person.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shown instead of the address in the Social tab. It stays on this phone: nobody else sees it.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.takeWhole(Person.MAX_NAME) },
                    label = { Text("Name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(cleanName(name)) }) { Text(if (cleanName(name).isEmpty() && person.name.isNotEmpty()) "Remove the name" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
