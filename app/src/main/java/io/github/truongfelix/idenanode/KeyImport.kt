package io.github.truongfelix.idenanode

/**
 * What follows a key import: the node reads its key at its start, so a running node restarts with the new
 * one ([restart]), but not during the validation; [message] is what the Key card says.
 */
data class KeyImported(val restart: Boolean, val message: String)

/** The [KeyImported] for a node that runs ([nodeRuns]) or not, with the [risk] of a restart now. */
fun keyImported(nodeRuns: Boolean, risk: RestartRisk?): KeyImported = when {
    !nodeRuns -> KeyImported(false, t(R.string.wallet_key_imported))
    risk?.canRestart == false ->
        KeyImported(false, t(R.string.wallet_key_imported_validation))
    else -> KeyImported(
        true,
        risk?.let { t(R.string.wallet_key_imported_restart_risk, it.message) } ?: t(R.string.wallet_key_imported_restart),
    )
}

/**
 * The Key card's note when the phone already has a key: the node's code (its IPFS peer id) and its Direct peers
 * belong to the node, so another key keeps them, and nodes that name this phone keep connecting to it.
 */
val IMPORT_KEEPS_NODE_NOTE: String
    get() = t(R.string.wallet_key_keeps_node_note)

/**
 * The Key card's warning while the identity mines with this phone's node: once the node runs with another key,
 * nothing mines for it.
 */
val MINING_IMPORT_WARNING: String
    get() = t(R.string.wallet_key_mining_warning)

/** The Key card's message once a new account's checked backup went to the node, which then starts with it. */
val NEW_ACCOUNT_MESSAGE: String
    get() = t(R.string.wallet_key_account_created)
