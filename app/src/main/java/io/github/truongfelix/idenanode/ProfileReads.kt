package io.github.truongfelix.idenanode

/**
 * The last node answers of each profile's header while the app runs: its identity (state, age, stake), its Trust and
 * its circle (owner 2026-10-17: they showed "…" at each opening, while the likes and tips, from the scan, showed at
 * once). A profile opens with them, then reads them again; only answers are kept, at most [MAX] profiles, the last
 * opened kept longest.
 */
object ProfileReads {
    const val MAX = 200

    private class Recent<V> : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > MAX
    }

    private val identities = Recent<ProfileIdentity>()
    private val trusts = Recent<TrustCount>()
    private val circles = Recent<ProfileCircle>()

    @Synchronized fun identity(address: String): ProfileIdentity? = identities[address.lowercase()]
    @Synchronized fun trust(address: String): TrustCount? = trusts[address.lowercase()]
    @Synchronized fun circle(address: String): ProfileCircle? = circles[address.lowercase()]

    @Synchronized fun keep(address: String, identity: ProfileIdentity) { identities[address.lowercase()] = identity }
    @Synchronized fun keep(address: String, trust: TrustCount) { trusts[address.lowercase()] = trust }
    @Synchronized fun keep(address: String, circle: ProfileCircle) { circles[address.lowercase()] = circle }

    /** Forgets everything: another account (a key import) sees other Trusts and offers. */
    @Synchronized fun clear() {
        identities.clear()
        trusts.clear()
        circles.clear()
    }
}

/**
 * The answer to show after a read of a header line: [read] when the node answered, else the one shown before when there
 * was one ([shown]), so that a failed read again does not replace a known line by an error.
 */
fun <T> afterRead(read: Result<T>, shown: Result<T>?): Result<T> = if (read.isFailure && shown?.isSuccess == true) shown else read
