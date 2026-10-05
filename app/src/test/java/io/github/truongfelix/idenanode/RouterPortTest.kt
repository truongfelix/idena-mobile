package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The router port's messages and decisions. The Livebox samples in `resources/router/` are the answers of the
 * Orange Livebox the feature was measured on (2026-10-05), with its serial number, UDNs and public address
 * replaced.
 */
class RouterPortTest {
    private fun sample(name: String): String = javaClass.getResource("/router/$name")!!.readText()

    private val livebox = Gateway(
        "Orange Livebox", "uuid:11111111-2222-3333-4444-555555555555", "http://192.168.1.1:60000/11111111/gatedesc.xml",
        "http://192.168.1.1:60000/11111111/upnp/control/WANIPConn1", "urn:schemas-upnp-org:service:WANIPConnection:2",
    )

    @Test
    fun aRoutersAnnouncementGivesItsDescription() {
        // The Livebox announces each service, here its IPv6 firewall; the description holds all of them.
        val notify = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: http://192.168.1.1:60000/11111111/gatedesc.xml\r\nOPT: \"http://schemas.upnp.org/upnp/1/0/\"; ns=01\r\n" +
            "NT: urn:schemas-upnp-org:service:WANIPv6FirewallControl:1\r\nNTS: ssdp:alive\r\n" +
            "SERVER: Unspecified, UPnP/1.0, SoftAtHome\r\nUSN: uuid:aaaaaaaa::urn:schemas-upnp-org:service:WANIPv6FirewallControl:1\r\n\r\n"
        val message = parseSsdp(notify)!!
        assertTrue(message.notify)
        assertTrue(message.alive)
        assertEquals("http://192.168.1.1:60000/11111111/gatedesc.xml", message.location)
        assertEquals("urn:schemas-upnp-org:service:WANIPv6FirewallControl:1", message.type)
    }

    @Test
    fun anAnswerToASearchGivesItsDescription() {
        val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
            "location: http://192.168.0.1:5000/rootDesc.xml\r\nUSN: uuid:x::urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
        val message = parseSsdp(answer)!!
        assertFalse(message.notify)
        assertEquals("http://192.168.0.1:5000/rootDesc.xml", message.location)
        assertEquals("urn:schemas-upnp-org:device:InternetGatewayDevice:1", message.type)
    }

    @Test
    fun searchesByebyesAndMessagesWithoutDescriptionAreNotGateways() {
        assertNull(parseSsdp(ssdpSearch("ssdp:all")))
        assertNull(parseSsdp("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\nNTS: ssdp:alive\r\n\r\n"))
        assertNull(parseSsdp("HTTP/1.1 200 OK\r\nLOCATION: https://192.168.0.1/desc.xml\r\n\r\n"))
        assertFalse(parseSsdp("NOTIFY * HTTP/1.1\r\nLOCATION: http://192.168.0.1/d.xml\r\nNTS: ssdp:byebye\r\n\r\n")!!.alive)
    }

    @Test
    fun theSearchAsksForGatewaysAndTheirServices() {
        val search = ssdpSearch(SSDP_SEARCH_TARGETS.first())
        assertTrue(search.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(search.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(search.endsWith("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:2\r\n\r\n"))
        assertTrue(SSDP_SEARCH_TARGETS.contains("urn:schemas-upnp-org:service:WANPPPConnection:1"))
    }

    @Test
    fun theLiveboxDescriptionGivesItsForwardingService() {
        assertEquals(livebox, parseGateway(livebox.location, sample("livebox-description.xml")))
    }

    @Test
    fun aControlUrlResolvesAgainstUrlBaseAndTheBestServiceWins() {
        val xml = """<root><URLBase>http://10.0.0.1:2869/</URLBase><device><friendlyName>Box &amp; Co</friendlyName>
            <UDN>uuid:box</UDN><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType><controlURL>ctl/PPP</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>ctl/IPConn</controlURL></service>
            </serviceList></device></root>"""
        val gateway = parseGateway("http://10.0.0.1:2869/desc.xml", xml)!!
        assertEquals("Box & Co", gateway.name)
        assertEquals("uuid:box", gateway.udn)
        assertEquals("http://10.0.0.1:2869/ctl/IPConn", gateway.controlUrl)
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", gateway.service)
    }

    @Test
    fun aDescriptionWithoutForwardingIsNoGateway() {
        val xml = "<root><device><friendlyName>Printer</friendlyName><serviceList><service>" +
            "<serviceType>urn:schemas-upnp-org:service:PrintBasic:1</serviceType><controlURL>/p</controlURL></service></serviceList></device></root>"
        assertNull(parseGateway("http://192.168.1.20/d.xml", xml))
    }

    @Test
    fun theOpeningRequestNamesThePortThePhoneAndTheLease() {
        val envelope = soapEnvelope(livebox.service, "AddPortMapping", addPortMappingArgs("192.168.1.45", 21600))
        assertTrue(envelope.contains("<u:AddPortMapping xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:2\">"))
        assertTrue(
            envelope.contains(
                "<NewRemoteHost></NewRemoteHost><NewExternalPort>40405</NewExternalPort><NewProtocol>TCP</NewProtocol>" +
                    "<NewInternalPort>40405</NewInternalPort><NewInternalClient>192.168.1.45</NewInternalClient>" +
                    "<NewEnabled>1</NewEnabled><NewPortMappingDescription>idena-mobile node</NewPortMappingDescription>" +
                    "<NewLeaseDuration>21600</NewLeaseDuration>",
            ),
        )
        assertTrue(soapEnvelope("s", "A", listOf("X" to "a<b&c")).contains("<X>a&lt;b&amp;c</X>"))
    }

    @Test
    fun theLiveboxMappingAnswerGivesThePhoneAndTheLease() {
        val mapping = portMapping(parseSoap(200, sample("livebox-mapping.xml")))!!
        assertEquals(PortMapping("192.168.1.45", 40405, "idena-mobile node", enabled = true, leaseSeconds = 0), mapping)
        assertEquals("203.0.113.7", parseSoap(200, sample("livebox-external-ip.xml")).fields["NewExternalIPAddress"])
    }

    @Test
    fun noMappingIsUpnpError714AndAnErrorPageHasNoCode() {
        val none = parseSoap(500, sample("livebox-no-mapping.xml"))
        assertEquals(UPNP_NO_SUCH_ENTRY, none.errorCode)
        assertEquals("NoSuchEntryInArray", none.errorText)
        assertNull(portMapping(none))
        assertEquals(-1, parseSoap(404, "<html>not found</html>").errorCode)
    }

    @Test
    fun httpResponsesWithLengthOrChunks() {
        val plain = "HTTP/1.1 200 OK\r\nCONTENT-LENGTH: 5\r\nCONNECTION: close\r\n\r\nhello+extra".toByteArray()
        assertEquals(HttpResponse(200, "hello"), parseHttpResponse(plain))
        val chunked = "HTTP/1.1 500 Internal Server Error\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n<a/>\r\n3;x=y\r\nabc\r\n0\r\n\r\n".toByteArray()
        assertEquals(HttpResponse(500, "<a/>abc"), parseHttpResponse(chunked))
        assertNull(parseHttpResponse("HTTP/1.1 200 OK\r\nContent-Length: 3".toByteArray()))
        assertNull(parseHttpResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n".toByteArray()))
    }

    @Test
    fun durationsEndAfterTheirHoursOrTwoHoursAfterTheValidation() {
        val now = Instant.parse("2026-10-05T18:00:00Z")
        assertEquals(listOf("6 hours", "1 day", "3 days", "7 days", "Until the validation + 2 h"), PortDuration.entries.map { it.label })
        assertEquals(now.plus(Duration.ofHours(6)), PortDuration.H6.end(now, null))
        assertEquals(now.plus(Duration.ofDays(7)), PortDuration.D7.end(now, null))
        val validation = Instant.parse("2026-10-09T15:00:00Z")
        assertEquals(Instant.parse("2026-10-09T17:00:00Z"), PortDuration.UntilValidation.end(now, validation))
        assertNull(PortDuration.UntilValidation.end(now, null))
        assertNull(PortDuration.UntilValidation.end(now, now.minusSeconds(60)))
    }

    private fun opening(endMs: Long, changedAtMs: Long = 0, retried: Boolean = false, client: String = "192.168.1.45") =
        RouterOpening(livebox, client, endMs, changedAtMs, retried)

    private fun mapping(client: String = "192.168.1.45", lease: Long = 0, description: String = PORT_MAPPING_DESCRIPTION) =
        PortMapping(client, NODE_PORT, description, enabled = true, leaseSeconds = lease)

    @Test
    fun theForwardingIsThePhonesItsFormerAddressOrAnotherDevices() {
        val phone = "192.168.1.45"
        assertEquals(MappingOwner.None, mappingOwner(null, phone, null))
        assertEquals(MappingOwner.Phone, mappingOwner(mapping(), phone, null))
        // Made by hand for the phone: still the phone's, the app offers Close.
        assertEquals(MappingOwner.Phone, mappingOwner(mapping(description = "my port"), phone, null))
        val before = opening(endMs = 1, client = "192.168.1.30")
        assertEquals(MappingOwner.PhoneBefore, mappingOwner(mapping(client = "192.168.1.30"), phone, before))
        // The same name for another address with no opening of the app there: another phone with the app.
        assertEquals(MappingOwner.Other, mappingOwner(mapping(client = "192.168.1.30"), phone, null))
        assertEquals(MappingOwner.Other, mappingOwner(mapping(client = "192.168.1.30", description = "game"), phone, before))
    }

    @Test
    fun upkeepClosesAtTheEndAndOpensAgainForANewAddressOrALostForwarding() {
        val hour = 3_600_000L
        val now = 10 * hour
        assertEquals(UpkeepAction.Close, upkeepAction(opening(endMs = now), mapping(), "192.168.1.45", now, false, 3))
        assertEquals(UpkeepAction.Close, upkeepAction(opening(endMs = now), null, "192.168.1.45", now, false, 3))
        assertEquals(UpkeepAction.Forget, upkeepAction(opening(endMs = now), mapping(client = "192.168.1.9", description = "x"), "192.168.1.45", now, false, 3))
        assertEquals(UpkeepAction.Open, upkeepAction(opening(endMs = now + hour), null, "192.168.1.45", now, false, 3))
        assertEquals(UpkeepAction.Open, upkeepAction(opening(endMs = now + hour, client = "192.168.1.30"), mapping(client = "192.168.1.30"), "192.168.1.45", now, false, 3))
        assertEquals(UpkeepAction.Nothing, upkeepAction(opening(endMs = now + hour), mapping(client = "192.168.1.9", description = "x"), "192.168.1.45", now, false, 3))
    }

    @Test
    fun upkeepRenewsAShorterRouterLeaseNearItsEndButNotDuringAValidation() {
        val day = 86_400_000L
        val now = 10 * day
        val long = opening(endMs = now + 20 * day, changedAtMs = now - day)
        assertEquals(UpkeepAction.Nothing, upkeepAction(long, mapping(lease = 3_600), "192.168.1.45", now, false, 2))
        assertEquals(UpkeepAction.Renew, upkeepAction(long, mapping(lease = 1_200), "192.168.1.45", now, false, 2))
        assertEquals(UpkeepAction.Nothing, upkeepAction(long, mapping(lease = 1_200), "192.168.1.45", now, true, 2))
        assertEquals(UpkeepAction.Renew, upkeepAction(long, mapping(lease = 120), "192.168.1.45", now, true, 2))
        // The router's lease ends with the user's choice (it closes the opening itself), or never: no renewal.
        assertEquals(UpkeepAction.Nothing, upkeepAction(opening(endMs = now + 600_000, changedAtMs = now - day), mapping(lease = 600), "192.168.1.45", now, false, 2))
        assertEquals(UpkeepAction.Nothing, upkeepAction(long, mapping(lease = 0), "192.168.1.45", now, false, 2))
    }

    @Test
    fun upkeepMakesTheOpeningAgainOnceWhenNoPeerCameFromOutside() {
        val now = 100 * 60_000L
        val fresh = opening(endMs = now + 3_600_000, changedAtMs = now - 2 * 60_000)
        val old = opening(endMs = now + 3_600_000, changedAtMs = now - RETRY_AFTER_MS)
        assertEquals(UpkeepAction.Nothing, upkeepAction(fresh, mapping(), "192.168.1.45", now, false, 0))
        assertEquals(UpkeepAction.Retry, upkeepAction(old, mapping(), "192.168.1.45", now, false, 0))
        assertEquals(UpkeepAction.Nothing, upkeepAction(old, mapping(), "192.168.1.45", now, false, 1))
        assertEquals(UpkeepAction.Nothing, upkeepAction(old, mapping(), "192.168.1.45", now, false, null))
        assertEquals(UpkeepAction.Nothing, upkeepAction(old.copy(retried = true), mapping(), "192.168.1.45", now, false, 0))
    }

    @Test
    fun peersFromOutsideAreCountedInTheLogWindow() {
        val log = """
            INFO [10-05|17:22:51.346] using custom configuration
            INFO [10-05|17:23:01.373] Peer connected                           id=QmRrGpoZP2Cqp5h3RoNHnf9kJceWhNYZmVXnAaXUk6bj2S inbound=true shardId=0
            INFO [10-05|17:23:03.324] Peer connected                           id=QmWhsfr4RRRcgLmTDsY2LxH5mVLFZdu7EY6mPzY5MYyTVX inbound=false shardId=0
            INFO [10-05|17:30:00.001] Peer connected                           id=QmY64mRHLznkvJbYJFGZChmL1mWmUZ2oatLrCcDDz3NnE3 inbound=true shardId=0
            INFO [10-05|17:31:00.001] Peer disconnected                        id=QmY64mRHLznkvJbYJFGZChmL1mWmUZ2oatLrCcDDz3NnE3 shardId=0
        """.trimIndent()
        assertEquals(2, inboundPeersSince(log, Instant.parse("2026-10-05T17:22:51Z"), Instant.parse("2026-10-05T17:40:00Z")))
        assertEquals(1, inboundPeersSince(log, Instant.parse("2026-10-05T17:25:00Z"), Instant.parse("2026-10-05T17:40:00Z")))
        assertEquals(0, inboundPeersSince(log, Instant.parse("2026-10-05T17:31:00Z"), Instant.parse("2026-10-05T17:40:00Z")))
        val newYear = "INFO [12-31|23:59:30.000] Peer connected id=Qma inbound=true\nINFO [01-01|00:00:10.000] Peer connected id=Qmb inbound=true"
        assertEquals(2, inboundPeersSince(newYear, Instant.parse("2026-12-31T23:59:00Z"), Instant.parse("2027-01-01T00:01:00Z")))
    }

    @Test
    fun theOpeningIsSavedAndAsksTheRouterForTheTimeLeft() {
        val opening = opening(endMs = 7_200_000, changedAtMs = 1_000, retried = true)
        assertEquals(opening, RouterOpening.fromJson(JSONObject(opening.toJson().toString())))
        assertEquals(7_200L - 1, opening.leaseSeconds(1_000))
        assertEquals(60L, opening.leaseSeconds(7_190_000))
        assertNull(RouterOpening.fromJson(JSONObject("{}")))
    }

    @Test
    fun theRowSaysWhereTheOpeningIsAndOffersWhatFits() {
        val now = Instant.parse("2026-10-05T18:00:00Z")
        fun found(mapping: PortMapping?, opening: RouterOpening? = null, declined: Boolean = false, inbound: Int? = null) =
            RouterPortState.Found(livebox, "192.168.1.45", mapping, opening, declined, inbound)
        val ask = routerPortView(found(null), now, ZoneOffset.UTC)
        assertEquals("Orange Livebox found. Open the node's port, so that outside nodes can connect to the phone?", ask.text)
        assertTrue(ask.ask && ask.canOpen && !ask.canClose)
        assertEquals(RouterPortView("Closed on Orange Livebox", ask = false, canOpen = true, canClose = false), routerPortView(found(null, declined = true), now, ZoneOffset.UTC))
        val end = Instant.parse("2026-10-06T00:00:00Z")
        val made = opening(endMs = end.toEpochMilli())
        assertEquals(
            RouterPortView(
                "Open on Orange Livebox until ${formatOpeningEnd(end, ZoneOffset.UTC)} · 3 peers connected from outside since",
                ask = false, canOpen = false, canClose = true,
            ),
            routerPortView(found(mapping(lease = 21_600), made, inbound = 3), now, ZoneOffset.UTC),
        )
        // Made outside the app, with no end: the app shows it and offers Close.
        assertEquals("Open on Orange Livebox with no end", routerPortView(found(mapping(lease = 0)), now, ZoneOffset.UTC).text)
        val other = routerPortView(found(mapping(client = "192.168.1.9", description = "game")), now, ZoneOffset.UTC)
        assertEquals("Port 40405 on Orange Livebox goes to another device (192.168.1.9)", other.text)
        assertFalse(other.canOpen || other.canClose)
    }
}
