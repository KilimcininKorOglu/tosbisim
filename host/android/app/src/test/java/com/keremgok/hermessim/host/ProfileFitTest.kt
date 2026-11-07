// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A shipped profile on another phone's card (Profile.onThisCard) and the tier the host reports (Compat.onThisPhone). */
class ProfileFitTest {
    private fun load(path: String) = JSONObject(File("../../../profiles/$path").readText()).let { Profile(it.getString("id"), it, "family") }
    private val redmi = load("candidates/mt6785-xiaomi-begonia.json")
    private val s20fe = load("qcom/sm8250-samsung-r8q.json")
    private val redmiPcms = mapOf("Playback_1" to 0, "Playback_2" to 2, "Capture_Mono_1" to 17)

    private fun Profile.pcm(k: String) = json.getJSONObject(k).getInt("pcm")

    @Test fun fitsTheMeasuredPhoneUnchanged() {
        val (fit, why) = redmi.onThisCard({ true }, redmiPcms)
        assertEquals("", why)
        assertEquals(17, fit!!.pcm("capture"))
        assertEquals(2, fit.pcm("inject"))
    }

    @Test fun aSiblingGetsItsOwnPcmNumbers() {
        val (fit, _) = redmi.onThisCard({ true }, mapOf("Playback_2" to 4, "Capture_Mono_1" to 19))
        assertEquals(19, fit!!.pcm("capture"))
        assertEquals(4, fit.pcm("inject"))
        assertEquals(17, redmi.pcm("capture"))                  // the shipped profile itself is untouched
    }

    @Test fun aMissingControlOrPcmRejectsIt() {
        val (noAmp, why) = s20fe.onThisCard({ it != "Left AMP Mute" }, mapOf("MultiMedia1" to 0, "MultiMedia9" to 23))
        assertNull(noAmp)
        assertTrue(why, why.contains("Left AMP Mute"))
        val (noPcm, why2) = redmi.onThisCard({ true }, mapOf("Playback_2" to 2))
        assertNull(noPcm)
        assertTrue(why2, why2.contains("Capture_Mono_1"))
    }

    @Test fun withoutRootTheNumbersStay() {
        assertEquals(23, s20fe.onThisCard({ true }, emptyMap()).first!!.pcm("inject"))
    }

    @Test fun everyControlIsChecked() {
        val names = s20fe.controlNames()
        for (n in listOf("MultiMedia1 Mixer VOC_REC_DL", "Incall_Music Audio Mixer MultiMedia9", "Incall_Music_2 Audio Mixer MultiMedia9",
                "Voice Tx Device Mute", "Voice Rx Device Mute", "Left AMP Mute", "Right AMP Mute")) assertTrue(n, n in names)
        assertTrue("Voc Rec Config" !in names)                   // optional: older kernels lack it
        assertEquals(redmi.json.getJSONObject("call_controls").length(), redmi.controlNames().size)
    }

    @Test fun aProfileOnThisPhoneMeansExpected() {
        fun v(t: Compat.Tier) = Compat.Verdict(t, "phone", "")
        assertEquals(Compat.Tier.EXPECTED, Compat.onThisPhone(v(Compat.Tier.EXPERIMENTAL), redmi).tier)
        assertEquals(Compat.Tier.EXPECTED, Compat.onThisPhone(v(Compat.Tier.UNKNOWN), redmi).tier)
        assertEquals(Compat.Tier.VERIFIED, Compat.onThisPhone(v(Compat.Tier.VERIFIED), redmi).tier)
        assertEquals(Compat.Tier.UNSUPPORTED, Compat.onThisPhone(v(Compat.Tier.UNSUPPORTED), redmi).tier)
        assertEquals(Compat.Tier.EXPERIMENTAL, Compat.onThisPhone(v(Compat.Tier.EXPERIMENTAL), null).tier)
    }
}
