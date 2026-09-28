package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ValidationTimeTest {
    private val now = Instant.parse("2026-09-28T20:00:00Z")

    @Test
    fun readsTheNodeTimeFormats() {
        // Go's time.Time in JSON: UTC, an offset, or nanoseconds.
        assertEquals(Instant.parse("2026-10-07T13:30:00Z"), parseValidationTime("2026-10-07T13:30:00Z"))
        assertEquals(Instant.parse("2026-10-07T13:30:00Z"), parseValidationTime("2026-10-07T15:30:00+02:00"))
        assertEquals(Instant.parse("2026-10-07T13:30:00.123456789Z"), parseValidationTime("2026-10-07T13:30:00.123456789Z"))
        assertNull(parseValidationTime(""))
    }

    @Test
    fun saysHowLongIsLeft() {
        assertEquals("in 8 days", timeUntil(now, Instant.parse("2026-10-07T13:30:00Z")))
        assertEquals("in 47 h", timeUntil(now, Instant.parse("2026-09-30T19:59:00Z")))
        assertEquals("in 5 h", timeUntil(now, Instant.parse("2026-09-29T01:10:00Z")))
        assertEquals("in 12 min", timeUntil(now, Instant.parse("2026-09-28T20:12:30Z")))
        assertEquals("in 1 min", timeUntil(now, Instant.parse("2026-09-28T20:00:20Z")))
        assertEquals("now", timeUntil(now, now.minusSeconds(60)))
    }
}
