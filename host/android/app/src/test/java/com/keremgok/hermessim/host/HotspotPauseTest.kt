// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import com.keremgok.hermessim.host.PauseCore.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hotspot's call pause through whole calls (PauseCore): when it goes off, when it comes back, who wins. */
class HotspotPauseTest {
    /** A host that offers it, with the switch on, the client on Bluetooth and the hotspot up unless said otherwise. */
    private fun core() = PauseCore().apply { setting = true }
    private fun PauseCore.tick(bt: Boolean = true, up: Boolean = !paused, offered: Boolean = true) = step(offered, bt, up)

    @Test fun pausedFromTheCallsAudioToItsEnd() {
        val c = core()
        assertEquals(Action.NONE, c.tick())                       // no call: nothing
        c.calls(relayed = true, live = true)
        assertEquals(Action.PAUSE, c.tick())
        assertTrue(c.paused)
        assertEquals(Action.NONE, c.tick())                       // stays paused while the call goes on
        c.calls(relayed = false, live = false)
        assertEquals(Action.RESUME, c.tick())
        assertFalse(c.paused)
        assertEquals(Action.NONE, c.tick())
    }

    @Test fun aRingingCallOrOneTheHostAnsweredItselfNeverPauses() {
        val c = core()
        c.calls(relayed = false, live = true)                     // ringing, or audio not relayed (Basic mode, host's own call)
        assertEquals(Action.NONE, c.tick())
    }

    @Test fun aHoldDoesntBringItBackAndASecondCallKeepsItPaused() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.tick())
        c.calls(relayed = false, live = true)                     // on hold: no audio, the call is still up
        assertEquals(Action.NONE, c.tick())
        c.calls(relayed = true, live = true)                      // back, or the waiting call taken
        assertEquals(Action.NONE, c.tick())
        assertTrue(c.paused)
        c.calls(relayed = false, live = false)
        assertEquals(Action.RESUME, c.tick())
    }

    @Test fun neverOnHostsThatDontOfferItNorWithTheSwitchOff() {
        val off = PauseCore()
        off.calls(relayed = true, live = true)
        assertEquals(Action.NONE, off.tick())
        val notOffered = core()
        notOffered.calls(relayed = true, live = true)
        assertEquals(Action.NONE, notOffered.tick(offered = false))
    }

    @Test fun neverOverTheInternet() {
        val c = core()
        c.calls(relayed = true, live = true)
        assertEquals(Action.NONE, c.tick(bt = false))
    }

    @Test fun aHotspotThatIsOffStaysOffAndComesBackOnlyIfWeTookItDown() {
        val c = core()
        c.calls(relayed = true, live = true)
        assertEquals(Action.NONE, c.tick(up = false))
        c.calls(relayed = false, live = false)
        assertEquals(Action.NONE, c.tick(up = false))             // no resume: we never paused it
    }

    @Test fun theCallMovingToTheInternetBringsItBackForTheRestOfTheCall() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.tick())
        assertEquals(Action.RESUME, c.tick(bt = false))           // Bluetooth faded: the hotspot may carry the call now
        assertTrue(c.held)
        assertEquals(Action.NONE, c.tick(bt = true, up = true))   // Bluetooth back: no second pause in this call
        c.calls(relayed = false, live = false)
        assertFalse(c.held)
        c.calls(relayed = true, live = true)                      // the next call pauses again
        assertEquals(Action.PAUSE, c.tick())
    }

    @Test fun theUsersSwitchWinsForTheRestOfTheCall() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.tick())
        c.userSwitched()                                          // "Turn on now" (or the hotspot switch)
        assertFalse(c.paused)
        assertEquals(Action.NONE, c.tick(up = true))              // not taken down again
        c.calls(relayed = false, live = false)
        assertEquals(Action.NONE, c.tick(up = true))              // and nothing to resume
    }

    @Test fun turnedOnByHandDuringACallItStaysOn() {
        val c = core()
        c.calls(relayed = true, live = true)
        assertEquals(Action.NONE, c.tick(up = false))
        c.userSwitched()
        assertEquals(Action.NONE, c.tick(up = true))
    }

    @Test fun turnedOffByHandWhilePausedItStaysOffAfterTheCall() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.tick())
        c.userSwitched()
        c.calls(relayed = false, live = false)
        assertEquals(Action.NONE, c.tick(up = false))
    }

    @Test fun onFromElsewhereWhilePausedWins() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.tick())
        assertTrue(c.cameOnElsewhere())
        assertEquals(Action.NONE, c.tick(up = true))
        assertFalse(c.cameOnElsewhere())                          // only while paused
    }

    @Test fun switchingItOffMidPauseBringsTheHotspotBack() {
        val c = core()
        c.calls(relayed = true, live = true); assertEquals(Action.PAUSE, c.tick())
        c.setting = false
        assertEquals(Action.RESUME, c.tick())
        assertFalse(c.held)                                       // the switch went off: not a transport hold
    }

    @Test fun switchedOnMidCallItPausesAtOnce() {
        val c = PauseCore()
        c.calls(relayed = true, live = true); assertEquals(Action.NONE, c.tick())
        c.setting = true
        assertEquals(Action.PAUSE, c.tick())
    }

    @Test fun aPauseRestoredAfterAHostRestartEndsWithNoCall() {
        val c = core().apply { paused = true }                    // from prefs; the call didn't come back
        c.calls(relayed = false, live = false)
        assertEquals(Action.RESUME, c.tick(up = false))
    }
}
