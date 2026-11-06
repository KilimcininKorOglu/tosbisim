// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Samsung's pre-ABOX AudioMixer route built from a real card: a Galaxy J7 Prime (Exynos 7870, Android 11 custom ROM)
 * whose host sent its hardware inventory on 2 Nov 2026 (test resource on7xelte-controls.txt, controls only).
 */
class SamsungMixerAutoProfileTest {
    /** Inventory lines ("numid\tname\ttype\tcount\trange|items") as simrelayd answers HAS for them. */
    private fun card(lines: List<String>): (String) -> String? {
        val byName = lines.map { it.split('\t') }.filter { it.size >= 4 }.associateBy { it[1] }
        return { n -> byName[n]?.let { c -> "OK count=${c[3]}" + if (c[2] == "enum") " items=${c.getOrElse(4) { "" }}" else "" } ?: "ERR no such control" }
    }
    private val j7 = javaClass.getResource("/on7xelte-controls.txt")!!.readText().lines().filter { it.isNotBlank() }
    // /proc/asound/pcm on that phone: 00-00 i2s0-pri ... 00-07..10 eax0-3
    private val j7Pcms = mapOf("i2s0-pri" to 0, "i2s0-sec" to 1, "voice" to 2, "bluetooth" to 3, "eax0" to 7, "eax3" to 10)

    private fun build(lines: List<String> = j7, pcms: Map<String, Int> = j7Pcms) =
        SamsungMixerAutoProfile.build("on7xelte", card(lines), { pcms })

    @Test fun buildsOnTheJ7Prime() {
        val (p, why) = build()
        assertTrue(why, p != null)
        val j = p!!.json
        assertEquals("exynos-audmixer", j.getString("router"))
        assertFalse(j.optBoolean("experimental"))                   // proven on this phone, 2 Nov 2026
        assertEquals(0, j.getJSONObject("capture").getInt("pcm"))
        assertEquals(10, j.getJSONObject("inject").getInt("pcm"))
        val c = j.getJSONObject("call_controls")
        assertEquals(0, c.getInt("AudioMixer CH2 DOUT Select"))      // DMIX_OUT|AIF4IN|AIF3IN
        assertEquals(2, c.getInt("AudioMixer CH1 DOUT Select"))      // DMIX_OUT|AIF4IN|RMIX_OUT
        assertEquals(1, c.getInt("AudioMixer RMIX1 En"))
        assertEquals(0, c.getInt("AudioMixer RMIX2 En"))
        assertEquals(0, c.getInt("AudioMixer CH2 Mixer En"))
        assertEquals(0, c.getInt("AudioMixer CH4 Mixer En"))
        assertEquals(1, c.getInt("AudioMixer CH1 Mixer En"))
        assertEquals(0, c.getInt("AudioMixer CH3 Rec En"))
        for (out in listOf("EP EP On", "SPK SPK On", "HP HP On", "EP Mixer DACL Switch", "HPR Mixer DACR Switch")) assertEquals(out, 0, c.getInt(out))
        assertFalse(c.has("AMP Enable Switch"))                      // no external amp on this model
        // Exactly what the daemon will be told: no Qualcomm defaults left over
        val lines = p.daemonSetLines()
        assertTrue(lines.contains("SET mute.tx -") && lines.contains("SET capture -") && lines.contains("SET inject.pcm 10"))
        assertTrue(lines.contains("SET call AudioMixer CH2 DOUT Select=0"))
    }

    @Test fun optionNumbersComeFromTheCard() {
        val swapped = j7.map { if ("\tAudioMixer CH1 DOUT Select\t" in it) it.replace("DMIX_OUT|AIF4IN|RMIX_OUT", "RMIX_OUT|DMIX_OUT|AIF4IN") else it }
        assertEquals(0, build(swapped).first!!.json.getJSONObject("call_controls").getInt("AudioMixer CH1 DOUT Select"))
    }

    @Test fun otherCardsAreLeftToTheOtherBuilders() {
        val (p, why) = build(listOf("1\tMultiMedia1 Mixer VOC_REC_DL\tbool\t1\t"))
        assertNull(p)
        assertEquals("", why)
    }

    @Test fun neverOnAnAboxCard() {
        val (p, why) = build(j7 + "999\tABOX Sound Type\tenum\t1\tVOICE|SPEAKER|CALLFWD")
        assertNull(p)
        assertTrue(why, "ABOX" in why)
    }

    @Test fun declinesWhatItCantKeepSilentOrReach() {
        val noEarpiece = j7.filterNot { "\tEP EP On\t" in it || "\tEP Mixer DACL Switch\t" in it }
        assertTrue(build(noEarpiece).second, build(noEarpiece).first == null && "earpiece" in build(noEarpiece).second)
        val (p, why) = build(pcms = j7Pcms - "eax3")
        assertNull(p)
        assertTrue(why, "eax3" in why)
        val noRecordMix = j7.map { if ("\tAudioMixer CH1 DOUT Select\t" in it) it.replace("|RMIX_OUT", "") else it }
        assertTrue(build(noRecordMix).second, build(noRecordMix).first == null && "RMIX_OUT" in build(noRecordMix).second)
    }

    @Test fun anOlderDaemonIsNamedNotGuessedAround() {
        // simrelayd before 0.12.19 answered "OK count=1" only: the host retries once the new build is swapped in
        val old: (String) -> String? = { n -> card(j7)(n)?.substringBefore(" items=") }
        val (p, why) = SamsungMixerAutoProfile.build("on7xelte", old, { j7Pcms })
        assertNull(p)
        assertTrue(why, "older build" in why)
    }

    @Test fun onItsPhoneTheRouteIsExpected() {
        fun v(t: Compat.Tier) = Compat.Verdict(t, "phone", "")
        val p = build().first
        assertEquals(Compat.Tier.EXPECTED, Compat.onThisPhone(v(Compat.Tier.UNKNOWN), p).tier)
        // A route still marked unproven (a future family's first release) stays experimental
        val unproven = Profile(p!!.id, org.json.JSONObject(p.json.toString()).put("experimental", true), "auto")
        assertEquals(Compat.Tier.EXPERIMENTAL, Compat.onThisPhone(v(Compat.Tier.NOT_YET), unproven).tier)
        assertEquals(Compat.Tier.VERIFIED, Compat.onThisPhone(v(Compat.Tier.VERIFIED), unproven).tier)
    }
}
