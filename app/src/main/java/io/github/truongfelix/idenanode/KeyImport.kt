package io.github.truongfelix.idenanode

/**
 * What follows a key import: the node reads its key at its start, so a running node restarts with the new
 * one ([restart]), but not during the validation; [message] is what the Key card says.
 */
data class KeyImported(val restart: Boolean, val message: String)

/** The [KeyImported] for a node that runs ([nodeRuns]) or not, with the [risk] of a restart now. */
fun keyImported(nodeRuns: Boolean, risk: RestartRisk?): KeyImported = when {
    !nodeRuns -> KeyImported(false, "Key imported.")
    risk?.canRestart == false ->
        KeyImported(false, "Key imported. The validation is running: switch sync off and on after it to use the key.")
    else -> KeyImported(
        true,
        "Key imported. The node restarts with it: a few minutes to open its databases and find peers." +
            (risk?.let { " ${it.message}" } ?: ""),
    )
}

/**
 * The Key card's note when the phone already has a key: the node's code (its IPFS peer id) and its Direct peers
 * belong to the node, so another key keeps them, and nodes that name this phone keep connecting to it.
 */
const val IMPORT_KEEPS_NODE_NOTE =
    "This phone's node code and Direct peers stay: they belong to the phone, not the account."

/**
 * The Key card's warning while the identity mines with this phone's node: once the node runs with another key,
 * nothing mines for it.
 */
const val MINING_IMPORT_WARNING =
    "This identity mines on this phone. Switch \"Mine on this phone\" off first and wait until it is done: an " +
        "identity left mining with no node is taken offline by the network, with an 8-hour mining penalty."
