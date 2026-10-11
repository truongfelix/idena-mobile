package io.github.truongfelix.idenanode

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Talks to the router of the Wi-Fi the phone is on: finds its port forwarding service by SSDP, then sends it
 * SOAP requests. Only devices on the local network are contacted. The requests go over a plain socket: the
 * app's network security config allows plain HTTP to its own node only, and a router has no fixed address.
 */
object RouterNet {
    private val SSDP_GROUP: InetAddress = InetAddress.getByName("239.255.255.250")
    private const val SSDP_PORT = 1900
    private const val TIMEOUT_MS = 5_000
    private const val MAX_RESPONSE = 256 * 1024

    /** How long a search waits for answers. */
    private const val SEARCH_MS = 3_000L

    /**
     * How long the node listens for a router's own announcements when none answers a search: UPnP has a device
     * announce itself again within half its announcements' lifetime (the Livebox: max-age 1800 s, so 15 min).
     */
    const val LISTEN_MS = 16 * 60_000L

    /** Whether the phone's active network is a Wi-Fi: routers are only looked for there. */
    fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** The router at [location] if it is [udn] (the phone is on its network), else null. */
    fun gatewayAt(location: String, udn: String): Gateway? =
        runCatching { describe(location, timeoutMs = 2_000) }.getOrNull()?.takeIf { it.udn == udn }

    /** The first of the routers found before ([known]) that answers here, or null. */
    fun knownHere(known: List<Gateway>): Gateway? = known.firstNotNullOfOrNull { gatewayAt(it.location, it.udn) }

    /**
     * Finds the router's forwarding service: first at the addresses of routers found before ([known]), then by a
     * search, then by listening to announcements for up to [listenMs] (some routers, like the Livebox, never
     * answer a search). Null when none was found. When the router answered the search without a forwarding
     * service, [onHeard] gets its name before the listening starts.
     */
    fun find(context: Context, known: List<Gateway>, listenMs: Long = LISTEN_MS, onHeard: (String) -> Unit = {}): Gateway? {
        knownHere(known)?.let { return it }
        val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("IdenaNode:router").apply { setReferenceCounted(false) }
        return try {
            lock.acquire()
            search(routerAddress(context), onHeard) ?: listen(listenMs)
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    /** The active network's router: the address of its default IPv4 route, or null. */
    private fun routerAddress(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }.getOrNull()

    /**
     * Searches for gateways, and for root devices to name the [router] if it has no forwarding service: only the
     * devices at the router's address are read for that, not every device that answers.
     */
    private fun search(router: String?, onHeard: (String) -> Unit): Gateway? {
        val tried = mutableSetOf<String>()
        var heard: String? = null
        DatagramSocket().use { socket ->
            socket.soTimeout = 500
            fun send() = (SSDP_SEARCH_TARGETS + SSDP_ROOT_DEVICE).forEach { target ->
                val message = ssdpSearch(target).toByteArray()
                socket.send(DatagramPacket(message, message.size, SSDP_GROUP, SSDP_PORT))
            }
            send()
            val start = System.currentTimeMillis()
            val deadline = start + SEARCH_MS
            // UDP loses a search or an answer now and then (the Speedport's root device answer: 1 search in 17,
            // 2026-10-10): as UPnP asks of control points, each search goes out twice, as the desktop's.
            var sentAgain = false
            val buffer = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                if (!sentAgain && System.currentTimeMillis() >= start + SEARCH_MS / 3) {
                    send()
                    sentAgain = true
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val message = parseSsdp(String(packet.data, 0, packet.length, Charsets.ISO_8859_1)) ?: continue
                val atRouter = router != null && locationHost(message.location) == router
                if (message.type.equals(SSDP_ROOT_DEVICE, ignoreCase = true) && !atRouter) continue
                if (!tried.add(message.location)) continue
                val xml = runCatching { description(message.location) }.getOrNull() ?: continue
                parseGateway(message.location, xml)?.let { return it }
                if (atRouter && heard == null) heard = parseDeviceName(xml)
            }
        }
        heard?.let(onHeard)
        return null
    }

    private fun listen(listenMs: Long): Gateway? {
        val tried = mutableSetOf<String>()
        val socket = MulticastSocket(null)
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(SSDP_PORT))
            socket.soTimeout = 1_000
            socket.joinGroup(InetSocketAddress(SSDP_GROUP, SSDP_PORT), wifiInterface())
            val deadline = System.currentTimeMillis() + listenMs
            val buffer = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val message = parseSsdp(String(packet.data, 0, packet.length, Charsets.ISO_8859_1)) ?: continue
                if (!message.notify || !message.alive) continue
                // A router announces each of its devices and services: one description holds them all.
                if (tried.add(message.location)) runCatching { describe(message.location) }.getOrNull()?.let { return it }
            }
            null
        } finally {
            socket.close()
        }
    }

    /** The Wi-Fi interface (an up, non-loopback interface with a private IPv4 address), or null for the default. */
    private fun wifiInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .filter { nif -> nif.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress } }
            .sortedByDescending { it.name.startsWith("wlan") }
            .firstOrNull()
    }.getOrNull()

    private fun describe(location: String, timeoutMs: Int = TIMEOUT_MS): Gateway? =
        description(location, timeoutMs)?.let { parseGateway(location, it) }

    /** The device description at [location], or null when the device does not give it. */
    private fun description(location: String, timeoutMs: Int = TIMEOUT_MS): String? {
        val response = http(location, "GET", emptyMap(), null, timeoutMs).first
        return if (response.status == 200) response.body else null
    }

    /** Sends [action] to the router; returns its answer and the phone's address as the router sees it. */
    fun soap(gateway: Gateway, action: String, args: List<Pair<String, String>>): Pair<SoapAnswer, String> {
        val body = soapEnvelope(gateway.service, action, args)
        val headers = mapOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "SOAPAction" to "\"${gateway.service}#$action\"",
        )
        val (response, localIp) = http(gateway.controlUrl, "POST", headers, body)
        return parseSoap(response.status, response.body) to localIp
    }

    /** The router's forwarding of the node's port and the phone's address; the forwarding null when none. */
    fun mapping(gateway: Gateway): Pair<PortMapping?, String> {
        val (answer, phoneIp) = soap(gateway, "GetSpecificPortMappingEntry", NODE_PORT_ARGS)
        if (!answer.ok && answer.errorCode != UPNP_NO_SUCH_ENTRY) throw RouterError(answer)
        return portMapping(answer) to phoneIp
    }

    /**
     * Forwards the node's port to [client] for [leaseSeconds] (0 = no end), replacing a forwarding the phone has
     * there: deleted first, since the measured router (Livebox) once left a forwarding replaced in place
     * unreachable. A router that refuses a long lease gets the standard longest; one that only keeps leases with
     * no end gets that, and the upkeep closes it at the user's end.
     */
    fun open(gateway: Gateway, client: String, leaseSeconds: Long, replace: Boolean) {
        if (replace) delete(gateway)
        var answer = soap(gateway, "AddPortMapping", addPortMappingArgs(client, leaseSeconds)).first
        if (!answer.ok && answer.errorCode == UPNP_ONLY_PERMANENT_LEASES) {
            answer = soap(gateway, "AddPortMapping", addPortMappingArgs(client, 0)).first
        } else if (!answer.ok && answer.errorCode != UPNP_CONFLICT && leaseSeconds > MAX_STANDARD_LEASE_SECONDS) {
            answer = soap(gateway, "AddPortMapping", addPortMappingArgs(client, MAX_STANDARD_LEASE_SECONDS)).first
        }
        if (!answer.ok) throw RouterError(answer)
    }

    /** Removes the forwarding of the node's port; none there is fine. */
    fun delete(gateway: Gateway) {
        val answer = soap(gateway, "DeletePortMapping", NODE_PORT_ARGS).first
        if (!answer.ok && answer.errorCode != UPNP_NO_SUCH_ENTRY) throw RouterError(answer)
    }

    /** One HTTP request; returns the response and the local address the connection used. */
    private fun http(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int = TIMEOUT_MS,
    ): Pair<HttpResponse, String> {
        val uri = URI(url)
        require(uri.scheme.equals("http", ignoreCase = true)) { "not http: $url" }
        val host = uri.host ?: error("no host: $url")
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val content = body?.toByteArray(Charsets.UTF_8)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val request = StringBuilder("$method $path HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> request.append("$name: $value\r\n") }
            if (content != null) request.append("Content-Length: ${content.size}\r\n")
            request.append("\r\n")
            val out = socket.getOutputStream()
            out.write(request.toString().toByteArray(Charsets.ISO_8859_1))
            if (content != null) out.write(content)
            out.flush()
            val data = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val input = socket.getInputStream()
            while (data.size() < MAX_RESPONSE) {
                val n = input.read(buffer)
                if (n < 0) break
                data.write(buffer, 0, n)
            }
            val response = parseHttpResponse(data.toByteArray()) ?: error("not an HTTP response from $host")
            return response to (socket.localAddress?.hostAddress ?: "")
        }
    }
}

/** A UPnP error answered by the router. */
class RouterError(val answer: SoapAnswer) : Exception(
    if (answer.errorCode == -1) "the router answered ${answer.errorText}" else "the router refused (${answer.errorCode} ${answer.errorText.orEmpty()})",
)

/**
 * The app's router files, in the node directory so that both processes see them: the opening the app made and the
 * routers found before (to skip the search next time).
 */
object RouterFiles {
    private fun openingFile(context: Context) = File(NodeSettings.nodeDir(context), "router_opening.json")
    private fun knownFile(context: Context) = File(NodeSettings.nodeDir(context), "router_gateways.json")

    @Synchronized
    fun opening(context: Context): RouterOpening? =
        runCatching { RouterOpening.fromJson(JSONObject(openingFile(context).readText())) }.getOrNull()

    @Synchronized
    fun setOpening(context: Context, opening: RouterOpening?) {
        val file = openingFile(context)
        if (opening == null) file.delete() else writeAtomically(file, opening.toJson().toString())
    }

    fun known(context: Context): List<Gateway> = runCatching {
        val array = JSONArray(knownFile(context).readText())
        (0 until array.length()).mapNotNull { Gateway.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** Remembers [gateway] first; keeps the last few routers. */
    @Synchronized
    fun remember(context: Context, gateway: Gateway) {
        val list = listOf(gateway) + known(context).filter { it.udn != gateway.udn }
        writeAtomically(knownFile(context), JSONArray(list.take(8).map { it.toJson() }).toString())
    }

    /** The end of the node's log, where it writes the peers that connect. */
    fun nodeLogTail(context: Context, bytes: Long = 512 * 1024): String = runCatching {
        RandomAccessFile(File(NodeSettings.nodeDir(context), "output.log"), "r").use { file ->
            val start = maxOf(0L, file.length() - bytes)
            file.seek(start)
            val data = ByteArray((file.length() - start).toInt())
            file.readFully(data)
            String(data, Charsets.UTF_8)
        }
    }.getOrDefault("")

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }
}
