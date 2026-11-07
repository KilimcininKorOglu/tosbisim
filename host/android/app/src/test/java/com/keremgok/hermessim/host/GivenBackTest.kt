// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** After "Stop using this phone as a host" (Retire.Mark) and for calls the host doesn't relay (BasicSilence.wanted). */
class GivenBackTest {
    @Test fun noMarkIsAWorkingHost() {
        assertEquals(Retire.Mark.State.NONE, Retire.Mark.state(null))
        assertEquals(Retire.Mark.State.NONE, Retire.Mark.state(""))
    }

    @Test fun theScriptsMarkWaitsForTheRestart() {
        assertEquals(Retire.Mark.State.RESTART_PENDING, Retire.Mark.state("12"))
        assertEquals(Retire.Mark.State.RESTART_PENDING, Retire.Mark.state("null"))   // a phone without a boot count
    }

    @Test fun aRestartThatLeftTheAppStaysGivenBack() {
        assertEquals(Retire.Mark.State.STILL_INSTALLED, Retire.Mark.state("13:after"))
    }

    @Test fun theClientsCallsStaySilencedWhoeverIsThePhoneApp() {
        assertTrue(BasicSilence.wanted(clientsCall = true, ourPhoneApp = true))
        assertTrue(BasicSilence.wanted(clientsCall = true, ourPhoneApp = false))
    }

    @Test fun anotherCallIsSilencedOnlyWhileTosbiSimIsThePhoneApp() {
        assertTrue(BasicSilence.wanted(clientsCall = false, ourPhoneApp = true))     // unchanged: a host in a drawer
        assertFalse(BasicSilence.wanted(clientsCall = false, ourPhoneApp = false))   // a person answered it on this phone
    }
}
