package io.github.truongfelix.idenanode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class PictureSearchTest {
    // Answers to a search for "apple" (2026-10-11), cut to their first two results.
    private fun sample(name: String): String = javaClass.getResource("/picture-search/$name")!!.readText()

    @Test
    fun openverseGivesItsOwnThumbnailsNotTheOriginalSites() {
        assertEquals(
            listOf(
                "https://api.openverse.org/v1/images/ae9def31-39f4-4056-8df3-135b19a2ddc7/thumb/",
                "https://api.openverse.org/v1/images/abe939b0-9741-4d30-b63e-19b08b3baf71/thumb/",
            ),
            openverseThumbnails(sample("openverse-apple.json")),
        )
        val elsewhere = JSONObject(sample("openverse-apple.json"))
        elsewhere.getJSONArray("results").getJSONObject(0).put("thumbnail", "https://live.staticflickr.com/6117/a.jpg")
        assertEquals("a thumbnail on another site is left out", 1, openverseThumbnails(elsewhere.toString()).size)
        assertTrue(openverseThumbnails("not json").isEmpty())
        assertEquals("https://api.openverse.org/v1/images/?q=apple+pie&page_size=20", openverseUrl("apple pie"))
    }

    @Test
    fun wikimediaGivesItsThumbnailsInSearchOrder() {
        val thumbs = wikimediaThumbnails(sample("wikimedia-apple.json"))
        assertEquals(2, thumbs.size)
        assertTrue("index 6 before index 27", thumbs[0].contains("Aerial_view_of_Apple_Park"))
        assertTrue(thumbs.all { it.startsWith("https://thumb.wikimedia.org/") })
        assertTrue(wikimediaThumbnails("{}").isEmpty())
        assertTrue(wikimediaUrl("apple pie").startsWith("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=apple+pie&"))
    }

    @Test
    fun thumbnailsComeOnlyFromTheSearchesOwnServers() {
        assertNull("no DuckDuckGo any more", thumbnailSource("https://external-content.duckduckgo.com/iu/?u=x&f=1"))
        assertEquals(PictureSource.Openverse, thumbnailSource("https://api.openverse.org/v1/images/x/thumb/"))
        assertEquals(PictureSource.Wikimedia, thumbnailSource("https://upload.wikimedia.org/wikipedia/commons/a.jpg"))
        assertNull(thumbnailSource("https://tse4.mm.bing.net/th/id/OIP.a"))
        assertNull("plain HTTP", thumbnailSource("http://api.openverse.org/v1/images/x/thumb/"))
        assertNull(thumbnailSource("https://user@api.openverse.org/v1/images/x/thumb/"))
        assertNull(thumbnailSource("https://api.openverse.org.example.com/x"))
        assertNull(thumbnailSource("not a url"))
    }

    @Test
    fun theStatusNamesTheSourcesStillSearchingAndThoseThatFailed() {
        val all = PictureSource.entries.toSet()
        assertEquals("Searching Openverse, Wikimedia…", pictureSearchStatus(all, emptyMap(), 0))
        assertEquals(
            "Searching Openverse…\nWikimedia: refuses searches from this connection for now; try again in a few minutes.",
            pictureSearchStatus(setOf(PictureSource.Openverse), mapOf(PictureSource.Wikimedia to "refuses searches from this connection for now; try again in a few minutes."), 12),
        )
        assertNull("done with pictures", pictureSearchStatus(emptySet(), emptyMap(), 30))
        assertEquals("No pictures found.\nWikimedia: no answer in time.", pictureSearchStatus(emptySet(), mapOf(PictureSource.Wikimedia to "no answer in time."), 0))
        assertEquals("no answer in time.", pictureSearchError(SocketTimeoutException("Read timed out")))
        assertEquals("not reachable (is the phone online?).", pictureSearchError(UnknownHostException("api.openverse.org")))
        assertEquals("answered 503.", pictureSearchError(IOException("answered 503.")))
    }
}
