package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocialDraftTest {
    private val image = ImageDraft(mapOf(512 to byteArrayOf(1)), 512)
    private val sent = EditorContent("hello", image, textOnIpfs = true)

    @Test
    fun aFailedPostGoesBackToAnEditorThatHoldsNothing() {
        assertEquals(sent, contentToPutBack(sent, "", null))
        assertEquals(sent, contentToPutBack(sent, "  \n", null))
        assertEquals("The draft is back in the editor.", putBackNote(sent, putBack = true))
    }

    @Test
    fun anEditorThatHoldsATextOrAnImageStaysAsItIs() {
        assertNull(contentToPutBack(sent, "new text", null))
        assertNull(contentToPutBack(sent, "", ImageDraft(mapOf(512 to byteArrayOf(2)), 512)))
        assertEquals("The editor holds another draft, so this one was not put back.", putBackNote(sent, putBack = false))
    }

    @Test
    fun aLikeOrATipHasNoDraft() {
        assertNull(contentToPutBack(null, "", null))
        assertNull(putBackNote(null, putBack = false))
    }
}
