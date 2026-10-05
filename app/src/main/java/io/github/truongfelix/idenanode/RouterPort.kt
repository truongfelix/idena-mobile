package io.github.truongfelix.idenanode

import org.json.JSONObject
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The node's port on the router of the Wi-Fi the phone is on, opened by UPnP for a time the user chooses, so
 * that outside nodes can connect to the phone. This file holds what needs no network: the SSDP and SOAP
 * messages, the router's description, the durations and the opening the app made ([RouterOpening]).
 * [RouterNet] talks to the router; [RouterPortModel] runs the Sync card's row; [RouterPortUpkeep] keeps the
 * opening while the node runs.
 */

/** The node's IPFS port, fixed (`IpfsConf.StaticPort`), which the opening forwards to the phone. */
const val NODE_PORT = 40405

/** How the app names its opening on the router, where the router lists its port forwardings. */
const val PORT_MAPPING_DESCRIPTION = "idena-mobile node"

/** The longest lease UPnP IGD 2 promises; some routers refuse longer ones. */
const val MAX_STANDARD_LEASE_SECONDS = 604_800L

/** An SSDP announcement (NOTIFY) or an answer to a search, with the address of the device's description. */
data class SsdpMessage(val notify: Boolean, val location: String, val type: String, val alive: Boolean)

/** The search the app sends for gateways, one per [searchTarget]. */
fun ssdpSearch(searchTarget: String): String =
    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $searchTarget\r\n\r\n"

/** What the app searches for: gateways (IGD 1 and 2) and their connection services. */
val SSDP_SEARCH_TARGETS = listOf(
    "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
    "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
    "urn:schemas-upnp-org:service:WANIPConnection:2",
    "urn:schemas-upnp-org:service:WANIPConnection:1",
    "urn:schemas-upnp-org:service:WANPPPConnection:1",
)

/** An SSDP datagram, or null when it is neither an announcement nor an answer, or has no description address. */
fun parseSsdp(text: String): SsdpMessage? {
    val lines = text.split("\r\n", "\n")
    val first = lines.firstOrNull()?.trim() ?: return null
    val notify = first.startsWith("NOTIFY", ignoreCase = true)
    if (!notify && !first.startsWith("HTTP/1.1 200", ignoreCase = true)) return null
    val headers = lines.drop(1).mapNotNull { line ->
        val i = line.indexOf(':')
        if (i > 0) line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim() else null
    }.toMap()
    val location = headers["LOCATION"]?.takeIf { it.startsWith("http://", ignoreCase = true) } ?: return null
    return SsdpMessage(
        notify = notify,
        location = location,
        type = (if (notify) headers["NT"] else headers["ST"]).orEmpty(),
        alive = headers["NTS"]?.equals("ssdp:byebye", ignoreCase = true) != true,
    )
}

/**
 * A router's port forwarding service: the router's [name] and [udn] (its identity, the same on any Wi-Fi
 * address), where its description is ([location]) and where the [service] takes requests ([controlUrl]).
 */
data class Gateway(val name: String, val udn: String, val location: String, val controlUrl: String, val service: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("udn", udn).put("location", location).put("controlUrl", controlUrl).put("service", service)

    companion object {
        fun fromJson(json: JSONObject): Gateway? = runCatching {
            Gateway(
                json.getString("name"), json.getString("udn"), json.getString("location"),
                json.getString("controlUrl"), json.getString("service"),
            )
        }.getOrNull()
    }
}

/** The services that forward ports, the preferred first. */
private val FORWARDING_SERVICES = listOf(
    "urn:schemas-upnp-org:service:WANIPConnection:2",
    "urn:schemas-upnp-org:service:WANIPConnection:1",
    "urn:schemas-upnp-org:service:WANPPPConnection:1",
)

/**
 * The port forwarding service in a device description read at [location], or null when it has none. The root
 * device comes first in the description: its name and UDN are the first ones.
 */
fun parseGateway(location: String, xml: String): Gateway? {
    val services = Regex("(?s)<service>(.*?)</service>").findAll(xml).mapNotNull { match ->
        val type = tag(match.groupValues[1], "serviceType") ?: return@mapNotNull null
        val control = tag(match.groupValues[1], "controlURL") ?: return@mapNotNull null
        type to control
    }.toList()
    val (service, control) = FORWARDING_SERVICES.firstNotNullOfOrNull { wanted -> services.firstOrNull { it.first == wanted } }
        ?: return null
    val base = tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
    val controlUrl = runCatching { URI(base).resolve(control).toString() }.getOrNull() ?: return null
    if (!controlUrl.startsWith("http://")) return null
    return Gateway(
        name = tag(xml, "friendlyName")?.takeIf { it.isNotBlank() } ?: "Router",
        udn = tag(xml, "UDN") ?: location,
        location = location,
        controlUrl = controlUrl,
        service = service,
    )
}

/** The text of the first <[name]> element in [xml], unescaped and trimmed. */
private fun tag(xml: String, name: String): String? =
    Regex("(?s)<$name>(.*?)</$name>").find(xml)?.groupValues?.get(1)?.trim()?.let(::xmlUnescape)

private fun xmlUnescape(s: String): String =
    s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

private fun xmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** A SOAP request of [action] to [service], with its arguments in order. */
fun soapEnvelope(service: String, action: String, args: List<Pair<String, String>>): String {
    val body = args.joinToString("") { (name, value) -> "<$name>${xmlEscape(value)}</$name>" }
    return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$service\">" +
        "$body</u:$action></s:Body></s:Envelope>"
}

/** A router's answer: its output arguments, or the UPnP error it returned. */
data class SoapAnswer(val fields: Map<String, String>, val errorCode: Int? = null, val errorText: String? = null) {
    val ok: Boolean get() = errorCode == null
}

/** UPnP errors the app handles: no such opening, the port taken by another device, only no-end leases. */
const val UPNP_NO_SUCH_ENTRY = 714
const val UPNP_CONFLICT = 718
const val UPNP_ONLY_PERMANENT_LEASES = 725

/** The answer in a SOAP response body; an error without a UPnP code (an HTTP error page) has code -1. */
fun parseSoap(httpStatus: Int, xml: String): SoapAnswer {
    val code = tag(xml, "errorCode")?.toIntOrNull()
    if (code != null) return SoapAnswer(emptyMap(), code, tag(xml, "errorDescription"))
    if (httpStatus != 200) return SoapAnswer(emptyMap(), -1, "HTTP $httpStatus")
    val fields = Regex("<(New\\w+)>([^<]*)</\\1>").findAll(xml).associate { it.groupValues[1] to xmlUnescape(it.groupValues[2]) }
    return SoapAnswer(fields)
}

fun addPortMappingArgs(client: String, leaseSeconds: Long): List<Pair<String, String>> = listOf(
    "NewRemoteHost" to "",
    "NewExternalPort" to NODE_PORT.toString(),
    "NewProtocol" to "TCP",
    "NewInternalPort" to NODE_PORT.toString(),
    "NewInternalClient" to client,
    "NewEnabled" to "1",
    "NewPortMappingDescription" to PORT_MAPPING_DESCRIPTION,
    "NewLeaseDuration" to leaseSeconds.toString(),
)

/** The arguments that name the node's port (GetSpecificPortMappingEntry, DeletePortMapping). */
val NODE_PORT_ARGS = listOf("NewRemoteHost" to "", "NewExternalPort" to NODE_PORT.toString(), "NewProtocol" to "TCP")

/** The router's forwarding of the node's port: to which device, named how, and the lease left (0 = no end). */
data class PortMapping(val client: String, val port: Int, val description: String, val enabled: Boolean, val leaseSeconds: Long)

/** The forwarding in a GetSpecificPortMappingEntry answer. */
fun portMapping(answer: SoapAnswer): PortMapping? {
    if (!answer.ok) return null
    val client = answer.fields["NewInternalClient"] ?: return null
    return PortMapping(
        client = client,
        port = answer.fields["NewInternalPort"]?.toIntOrNull() ?: NODE_PORT,
        description = answer.fields["NewPortMappingDescription"].orEmpty(),
        enabled = answer.fields["NewEnabled"] != "0",
        leaseSeconds = answer.fields["NewLeaseDuration"]?.toLongOrNull() ?: 0L,
    )
}

/** How long the user opens the port for. */
enum class PortDuration(val label: String, val hours: Long?) {
    H6("6 hours", 6),
    D1("1 day", 24),
    D3("3 days", 72),
    D7("7 days", 168),
    UntilValidation("Until the validation + 2 h", null),
    ;

    /**
     * The opening's end for a choice made at [now], or null when it cannot be chosen (the next validation unknown
     * or already started).
     */
    fun end(now: Instant, validation: Instant?): Instant? = when (hours) {
        null -> validation?.takeIf { it.isAfter(now) }?.plus(Duration.ofHours(2))
        else -> now.plus(Duration.ofHours(hours))
    }
}

/**
 * The opening the app made: on which router ([gateway]), for which address of the phone ([client]), until when
 * the user chose ([endMs]), when the router's forwarding last changed ([changedAtMs]) and whether it was made
 * again once because no incoming peer came ([retried]).
 */
data class RouterOpening(
    val gateway: Gateway,
    val client: String,
    val endMs: Long,
    val changedAtMs: Long,
    val retried: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("gateway", gateway.toJson()).put("client", client).put("endMs", endMs)
        .put("changedAtMs", changedAtMs).put("retried", retried)

    /** The lease to ask the router for at [nowMs]: the time left, at least a minute. */
    fun leaseSeconds(nowMs: Long): Long = maxOf(60L, (endMs - nowMs) / 1000)

    companion object {
        fun fromJson(json: JSONObject): RouterOpening? = runCatching {
            RouterOpening(
                Gateway.fromJson(json.getJSONObject("gateway"))!!, json.getString("client"), json.getLong("endMs"),
                json.getLong("changedAtMs"), json.optBoolean("retried"),
            )
        }.getOrNull()
    }
}

/** Whose the router's forwarding of the node's port is, for the phone at [phoneIp] and the app's [opening]. */
enum class MappingOwner { None, Phone, PhoneBefore, Other }

fun mappingOwner(mapping: PortMapping?, phoneIp: String, opening: RouterOpening?): MappingOwner = when {
    mapping == null -> MappingOwner.None
    mapping.client == phoneIp -> MappingOwner.Phone
    // The app's opening for an address the phone had before on this network.
    opening != null && mapping.client == opening.client && mapping.description == PORT_MAPPING_DESCRIPTION -> MappingOwner.PhoneBefore
    else -> MappingOwner.Other
}

/**
 * What the upkeep does with the app's [opening] at [nowMs], given the router's forwarding ([mapping]) and the
 * phone's address on the router's network ([phoneIp]): the router's lease is the time left to the user's end,
 * so that the router closes it by itself; a router that keeps it shorter gets it again near its end, never
 * during a validation unless it would end first ([validationRuns]); [inboundSinceChange] null when unknown.
 */
enum class UpkeepAction { Nothing, Close, Forget, Open, Renew, Retry }

fun upkeepAction(
    opening: RouterOpening,
    mapping: PortMapping?,
    phoneIp: String,
    nowMs: Long,
    validationRuns: Boolean,
    inboundSinceChange: Int?,
): UpkeepAction {
    if (nowMs >= opening.endMs) return if (mappingOwner(mapping, phoneIp, opening) == MappingOwner.Other) UpkeepAction.Forget else UpkeepAction.Close
    val owner = mappingOwner(mapping, phoneIp, opening)
    if (owner == MappingOwner.Other) return UpkeepAction.Nothing
    if (owner == MappingOwner.None || owner == MappingOwner.PhoneBefore) return UpkeepAction.Open
    val lease = mapping!!.leaseSeconds
    val routerEndsFirst = lease > 0 && nowMs + lease * 1000 < opening.endMs - 60_000
    if (routerEndsFirst && lease < RENEW_BEFORE_SECONDS && (!validationRuns || lease < 180)) return UpkeepAction.Renew
    if (!opening.retried && nowMs - opening.changedAtMs >= RETRY_AFTER_MS && inboundSinceChange == 0) return UpkeepAction.Retry
    return UpkeepAction.Nothing
}

/** A router's lease shorter than this gets renewed (the upkeep runs every few minutes). */
const val RENEW_BEFORE_SECONDS = 30 * 60L

/** How long after a change the upkeep waits for an incoming peer before it makes the opening again, once. */
const val RETRY_AFTER_MS = 5 * 60_000L

/** A plain HTTP response: status code and body (chunked or not). */
data class HttpResponse(val status: Int, val body: String)

/** Parses the bytes of an HTTP/1.x response read until the connection closed; null when not one. */
fun parseHttpResponse(bytes: ByteArray): HttpResponse? {
    val headerEnd = indexOf(bytes, "\r\n\r\n".toByteArray()).takeIf { it >= 0 } ?: return null
    val head = String(bytes, 0, headerEnd, Charsets.ISO_8859_1).split("\r\n")
    val status = head.first().split(" ").getOrNull(1)?.toIntOrNull() ?: return null
    val headers = head.drop(1).mapNotNull { line ->
        val i = line.indexOf(':')
        if (i > 0) line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim() else null
    }.toMap()
    var body = bytes.copyOfRange(headerEnd + 4, bytes.size)
    if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
        body = dechunk(body) ?: return null
    } else {
        headers["content-length"]?.toIntOrNull()?.let { if (it <= body.size) body = body.copyOf(it) }
    }
    return HttpResponse(status, String(body, Charsets.UTF_8))
}

private fun dechunk(data: ByteArray): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    var pos = 0
    while (true) {
        val lineEnd = indexOf(data, "\r\n".toByteArray(), pos).takeIf { it >= 0 } ?: return null
        val size = String(data, pos, lineEnd - pos, Charsets.ISO_8859_1).substringBefore(';').trim().toIntOrNull(16) ?: return null
        if (size == 0) return out.toByteArray()
        val start = lineEnd + 2
        if (start + size > data.size) return null
        out.write(data, start, size)
        pos = start + size + 2
    }
}

private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int = 0): Int {
    outer@ for (i in from..data.size - pattern.size) {
        for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
        return i
    }
    return -1
}

/** The node log's timestamps: UTC month, day and time (`INFO [10-05|17:22:51.346] ...`). */
private val LOG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd|HH:mm:ss").withZone(ZoneOffset.UTC)
private val INBOUND_PEER = Regex("\\[(\\d\\d-\\d\\d\\|\\d\\d:\\d\\d:\\d\\d)\\.\\d+\\] Peer connected .*inbound=true")

/**
 * How many Idena peers connected to the phone from outside (`inbound=true`) in [logTail] (the end of the node's
 * log) from [since] to [now]. The log has no year: over New Year the window wraps.
 */
fun inboundPeersSince(logTail: String, since: Instant, now: Instant): Int {
    val from = LOG_TIME.format(since)
    val to = LOG_TIME.format(now.plusSeconds(60))
    return logTail.lineSequence().count { line ->
        val time = INBOUND_PEER.find(line)?.groupValues?.get(1) ?: return@count false
        if (from <= to) time in from..to else time >= from || time <= to
    }
}
