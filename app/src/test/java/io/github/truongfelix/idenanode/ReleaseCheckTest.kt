package io.github.truongfelix.idenanode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCheckTest {
    private fun release(tag: String, draft: Boolean = false, body: String = "note of $tag") =
        JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", true).put("body", body)
            .put("html_url", "https://example.com/elsewhere")

    private fun v(text: String) = AppVersion.parse(text)!!

    @Test
    fun versionsCompareByNumbersThenPreRelease() {
        assertTrue(v("v0.2.0-beta.22") > v("0.2.0-beta.21"))
        assertTrue(v("0.2.0-beta.10") > v("0.2.0-beta.9"))
        assertTrue(v("0.2.0") > v("0.2.0-beta.99"))
        assertTrue(v("0.3.0-beta.1") > v("0.2.0"))
        assertTrue(v("0.2.1") > v("0.2"))
        assertEquals(0, v("v0.2.0-beta.22").compareTo(v("0.2.0-beta.22")))
        assertNull(AppVersion.parse("nightly"))
    }

    @Test
    fun theNewestNewerReleaseIsFoundWhateverTheOrder() {
        val list = JSONArray().put(release("v0.2.0-beta.21")).put(release("v0.2.0-beta.24")).put(release("v0.2.0-beta.23"))
        val found = newerRelease(list, "0.2.0-beta.22")!!
        assertEquals("0.2.0-beta.99", found.version)
        assertEquals("note of v0.2.0-beta.24", found.note)
        // The page is GitHub's for the tag, never the address the answer gives.
        assertEquals("https://github.com/truongfelix/idena-mobile/releases/tag/v0.2.0-beta.24", found.page)
    }

    @Test
    fun nothingNewerDraftsAndOddTagsAreLeftOut() {
        assertNull(newerRelease(JSONArray().put(release("v0.2.0-beta.22")).put(release("v0.2.0-beta.21")), "0.2.0-beta.22"))
        assertNull(newerRelease(JSONArray().put(release("v0.2.0-beta.30", draft = true)), "0.2.0-beta.22"))
        assertNull(newerRelease(JSONArray().put(release("v0.2.0-beta.30/../x")), "0.2.0-beta.22"))
        assertNull(newerRelease(JSONArray(), "0.2.0-beta.22"))
    }

    @Test
    fun offNothingIsAskedOnItIsAskedAfterTheGap() {
        val day = ReleaseCheck.DAILY_MS
        assertFalse(releaseCheckDue(on = false, lastCheckMs = null, nowMs = 10 * day, gapMs = day))
        assertTrue(releaseCheckDue(on = true, lastCheckMs = null, nowMs = 10 * day, gapMs = day))
        assertFalse(releaseCheckDue(on = true, lastCheckMs = 10 * day - 1, nowMs = 10 * day, gapMs = day))
        assertTrue(releaseCheckDue(on = true, lastCheckMs = 9 * day, nowMs = 10 * day, gapMs = day))
        // A clock set back does not stop the checks.
        assertTrue(releaseCheckDue(on = true, lastCheckMs = 11 * day, nowMs = 10 * day, gapMs = day))
    }

    @Test
    fun theNoteIsShownAsPlainText() {
        assertEquals("What's new\n• French and English", plainNote("## What's new\r\n- **French** and `English`\n"))
    }
}
