package com.example.aibuds.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeTriggerTest {
    private var t = 0L
    private var earphone = true
    private var triggers = 0
    private val log = mutableListOf<String>()
    private val trigger = VolumeTrigger(now = { t }, earphoneConnected = { earphone }, log = { log += it }, onTrigger = { triggers++ })

    @Test fun upThenDownTriggersOnce() {
        trigger.onVolume(up = true); t += 300; trigger.onVolume(up = false)
        assertEquals(1, triggers)
    }

    @Test fun samePressFromKeyAndBroadcastTriggersOnce() {
        trigger.onVolume(up = true); trigger.onVolume(up = true)
        t += 300
        trigger.onVolume(up = false); trigger.onVolume(up = false)
        assertEquals(1, triggers)
    }

    @Test fun ignoredWithoutEarphone() {
        earphone = false
        trigger.onVolume(up = true, source = "key"); trigger.onVolume(up = false, source = "key")
        assertEquals(0, triggers)
        assertEquals(listOf("volume up (key)", "volume down (key) → trigger", "trigger ignored: no earphone output"), log)
    }
}
