// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.json.JSONObject

/**
 * Samsung's older Exynos audio (Exynos 7870 / 7570, before ABOX: Galaxy J7 Prime/Pro/Nxt, J7 Top, J6, J4, A6, M10,
 * J2 Core) on a phone without a profile. Their stock firmware stops at Android 10, so every such host runs a custom ROM.
 * The SoC's "AudioMixer" block joins the AP's I2S (CH1), the modem (CH2), Bluetooth (CH3) and the codec (AIF4).
 * Samsung's own call-forwarding route (stock mixer_paths "call_forwarding_master" + "-mic", Galaxy A6/J4 firmware):
 * the modem's uplink takes the AP's mix (CH2 DOUT = DMIX_OUT, with only the AP in it) instead of the codec's mic, and the
 * AP's capture takes the record mix holding only the downlink (RMIX1 on, RMIX2 off, CH1 DOUT = RMIX_OUT). Our audio
 * also reaches the codec through that mix, so its outputs are held off for the call (host silence). Inject on eax3, an
 * AP sub-stream that neither Samsung's HAL (eax0-2) nor the LineageOS one (PCM 0/1) plays to; capture on i2s0-pri.
 *
 * Built from the kernel's own controls (simrelayd HAS, with an enum's options): each option's number is this card's.
 * Never on ABOX phones (no card has both, AndroidDumps corpus 2 Nov 2026). Proven on a Galaxy J7 Prime that passed the
 * call test (2 Nov 2026); every phone still runs its own. Evidence: tools/corpus/research/exynos-audmixer.md.
 */
object SamsungMixerAutoProfile {
    const val MARKER = "AudioMixer CH2 DOUT Select"

    /** Present on every ABOX card in the corpus (128 of 128 mixer files): such a phone is not this family. */
    private val ABOX = listOf("ABOX Sound Type", "ABOX NSRC0")

    /** The call route, in the order it is applied: the mic out of the mix first, our audio into the uplink last. */
    private val ROUTE = listOf(
        "AudioMixer CH4 Mixer En" to "Off",       // codec ADC (host mic) out of the mix that now feeds the uplink
        "AudioMixer CH2 Mixer En" to "Off",       // the downlink out of it (no echo back to the caller)
        "AudioMixer CH3 Mixer En" to "Off",       // Bluetooth out of it
        "AudioMixer CH1 Mixer En" to "On",        // the AP's playback (eax3) into it
        "AudioMixer Mixer En" to "On",
        "AudioMixer SRC2 En" to "On",             // the modem channel's rate converter
        "AudioMixer CH2 DOUT Select" to "DMIX_OUT",   // the modem's uplink = that mix
        "AudioMixer RMIX2 En" to "Off",           // uplink out of the record mix
        "AudioMixer RMIX1 En" to "On",            // downlink into it
        "AudioMixer CH1 DOUT Select" to "RMIX_OUT",   // the AP's capture = the record mix
    )
    private const val CH3_REC = "AudioMixer CH3 Rec En"    // Bluetooth into the record mix: off where it exists

    /** Codec outputs (COD3026X pins and DAC mixer switches) and the CS35L40 speaker amp some models have: held at 0. */
    private val OUTPUTS = listOf("EP EP On", "SPK SPK On", "HP HP On",
        "EP Mixer DACL Switch", "EP Mixer DACR Switch", "SPK Mixer DACL Switch", "SPK Mixer DACR Switch",
        "HPL Mixer DACL Switch", "HPL Mixer DACR Switch", "HPR Mixer DACL Switch", "HPR Mixer DACR Switch",
        "AMP Enable Switch")
    private val EARPIECE = listOf("EP EP On", "EP Mixer DACL Switch")
    private val SPEAKER = listOf("SPK SPK On", "SPK Mixer DACL Switch", "AMP Enable Switch")

    const val CAPTURE_PCM = "i2s0-pri"
    const val INJECT_PCM = "eax3"

    /**
     * [ask]: simrelayd's HAS reply for a control ("OK count=1 items=Off|On"), null or ERR when it isn't there.
     * [pcms]: /proc/asound/pcm by name, read only once the card is this family's. Returns the profile, or null and why
     * (empty: not this family at all, so other builders may try).
     */
    fun build(device: String, ask: (String) -> String?, pcms: () -> Map<String, Int>): Pair<Profile?, String> {
        fun reply(n: String) = ask(n)?.takeIf { it.startsWith("OK") }
        val marker = reply(MARKER) ?: return null to ""
        ABOX.firstOrNull { reply(it) != null }?.let { return null to "Samsung AudioMixer: an ABOX control ($it) too" }
        fun count(r: String) = Regex("\\bcount=(\\d+)").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        fun items(r: String) = r.substringAfter(" items=", "").takeIf { it.isNotEmpty() }?.split('|').orEmpty()
        // A daemon from before 0.12.19 answers HAS without the options: the new one, swapped in after an app update, will
        if (items(marker).isEmpty()) return null to "Samsung AudioMixer: simrelayd lists no options (an older build)"

        val controls = JSONObject()
        val outputs = OUTPUTS.filter { n -> reply(n)?.let { count(it) == 1 && items(it).isEmpty() } == true }
        if (EARPIECE.none { it in outputs } || SPEAKER.none { it in outputs })
            return null to "Samsung AudioMixer: no earpiece or speaker control to hold off (found $outputs)"
        outputs.forEach { controls.put(it, 0) }
        val route = ROUTE + listOfNotNull((CH3_REC to "Off").takeIf { reply(CH3_REC) != null })
        for ((name, option) in route) {
            val r = reply(name) ?: return null to "Samsung AudioMixer: no $name"
            val i = items(r).indexOf(option)
            if (i < 0) return null to "Samsung AudioMixer: $name has no $option (${items(r)})"
            controls.put(name, i)
        }
        val pcm = pcms()
        val cap = pcm[CAPTURE_PCM]
        val inj = pcm[INJECT_PCM]
        if (cap == null || inj == null) return null to "Samsung AudioMixer: PCM $CAPTURE_PCM=$cap, $INJECT_PCM=$inj"
        // Rates: the AP side of the mixer runs at 48 kHz (its SRC converts the modem's 8/16 kHz); simrelayd negotiates.
        val json = JSONObject().put("id", "auto/$device").put("router", "exynos-audmixer").put("card", 0)
            .put("capture", JSONObject().put("pcm", cap).put("pcm_name", CAPTURE_PCM).put("rate", 48000).put("channels", 2)
                .put("route", "-").put("rec_config", "-"))
            .put("inject", JSONObject().put("pcm", inj).put("pcm_name", INJECT_PCM).put("rate", 48000).put("channels", 2)
                .put("route_by_session", JSONObject()))
            .put("session_by_slot", JSONObject().put("0", 1).put("1", 1))
            .put("call_controls", controls)
        return Profile(json.getString("id"), json, "auto") to
            "Samsung AudioMixer: $CAPTURE_PCM (pcm $cap) <- downlink, $INJECT_PCM (pcm $inj) -> uplink, ${outputs.size} outputs held off"
    }
}
