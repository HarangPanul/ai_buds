package com.example.aibuds.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeGestureDetectorTest {
    private var t = 0L
    private val d = VolumeGestureDetector(windowMs = 2000) { t }

    @Test fun upThenDownTriggers() {
        assertEquals(false, d.onKey(up = true))
        t += 500
        assertEquals(true, d.onKey(up = false))
    }

    @Test fun downAloneOrDownThenUpDoesNotTrigger() {
        assertEquals(false, d.onKey(up = false))
        assertEquals(false, d.onKey(up = false))
        assertEquals(false, d.onKey(up = true))
    }

    @Test fun tooSlowDoesNotTrigger() {
        d.onKey(up = true)
        t += 2500
        assertEquals(false, d.onKey(up = false))
    }

    @Test fun triggersOnceThenNeedsNewUp() {
        d.onKey(up = true)
        assertEquals(true, d.onKey(up = false))
        assertEquals(false, d.onKey(up = false))
    }

    @Test fun repeatedUpUsesLatestUp() {
        d.onKey(up = true)
        t += 1900
        d.onKey(up = true)
        t += 1900
        assertEquals(true, d.onKey(up = false))
    }
}
