package io.github.truongfelix.idenanode

/**
 * The node's IPFS port by default, the official node's. The node never moves off its port (`IpfsConf.StaticPort`);
 * another one of [NODE_PORTS] is chosen in Advanced settings, for a second device behind the same router: a router
 * forwards a port to one device only, and each node tells its peers the port it listens on.
 */
const val DEFAULT_NODE_PORT = 40405

/** The ports the node can take: one per device behind a router, each forwarded to its own device. */
val NODE_PORTS: List<Int> = (DEFAULT_NODE_PORT..DEFAULT_NODE_PORT + 4).toList()

/** A saved node port: one of [NODE_PORTS], else (none saved, or damaged) the default. */
fun parseNodePort(text: String?): Int = text?.trim()?.toIntOrNull()?.takeIf { it in NODE_PORTS } ?: DEFAULT_NODE_PORT

/**
 * The first of [NODE_PORTS] after [taken] (then from the start) that the router forwards to no other device,
 * given each port's [owner] there; null when none is free.
 */
fun nextFreePort(taken: Int, owner: (Int) -> MappingOwner): Int? {
    val i = NODE_PORTS.indexOf(taken)
    val order = NODE_PORTS.drop(i + 1) + NODE_PORTS.take(maxOf(i, 0))
    return order.firstOrNull { it != taken && owner(it) != MappingOwner.Other }
}

val NODE_PORT_TITLE: String get() = t(R.string.node_port_title)

val NODE_PORT_INFO: String get() = t(R.string.node_port_info, DEFAULT_NODE_PORT)
