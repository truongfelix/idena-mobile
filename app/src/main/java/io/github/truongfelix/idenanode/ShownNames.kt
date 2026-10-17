package io.github.truongfelix.idenanode

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The names shown outside Social (History, oracle votings), as the desktop's useShownName: the name given in Social on
// this phone, else the name claimed in the profiles contract while its holder is in good standing.

private fun rpc(context: Context) = NodeRpc(NodeSettings.RPC_PORT, NodeSettings.apiKey(context))

/**
 * The names of [addresses] (lowercase) from the People list ([people], by lowercase address) and the [profiles] read
 * for them: only those with a name.
 */
fun namesFor(addresses: Collection<String>, people: Map<String, Person>, profiles: Collection<ContractProfile>): Map<String, String> {
    val all = mergedNames(peopleNames(people), shownNames(profiles))
    return addresses.map { it.lowercase() }.distinct().mapNotNull { a -> all[a]?.let { a to it } }.toMap()
}

/** [address] as a line shows it: its name and the short address ("@alice · 0xaaaa…0001"), " (you)" when it is [me]. */
fun namedAddress(address: String, names: Map<String, String>, me: String?): String {
    val short = shortAddress(address)
    val shown = names[address.lowercase()]?.let { "$it · $short" } ?: short
    return if (address.equals(me, ignoreCase = true)) t(R.string.socialb_title_you, shown) else shown
}

/**
 * The names to show for [addresses], read once per set (the People list and the profiles contract, through the
 * app's own node); empty while they are read, or when the node does not answer (the addresses then show alone).
 */
@Composable
fun rememberShownNames(addresses: Collection<String>): Map<String, String> {
    val context = LocalContext.current
    val key = addresses.map { it.lowercase() }.distinct().sorted()
    val names by produceState(emptyMap<String, String>(), key) {
        if (key.isEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val people = SocialStore.people(context)
                // Only the addresses without a name given on the phone need the contract.
                val unnamed = key.filter { peopleNames(people)[it] == null }
                val profiles = if (unnamed.isEmpty()) emptyList() else NodeProfilesSource(rpc(context)).profiles(unnamed).values
                namesFor(key, people, profiles)
            }.getOrDefault(emptyMap())
        }
    }
    return names
}
