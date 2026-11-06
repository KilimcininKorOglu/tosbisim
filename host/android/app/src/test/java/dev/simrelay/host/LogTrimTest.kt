// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A report's log excerpt without Telecom's phone-account noise and in-a-row repeats (LogTrim), losing nothing else. */
class LogTrimTest {
    private val account = "11-05 13:03:01.734 I/Telecom ( 3639): PhoneAccountRegistrar: Modify account: [ComponentInfo{com.android.phone/x}]: TSI.rPA@AJU"
    private val changed = "11-05 13:03:01.740 I/Telecom ( 3639): CallsManager: handlePhoneAccountChanged: phoneAccount=[[X] PhoneAccount]: TSI.rPA@AJU"
    private val ours = "11-05 13:03:02.017 I/SimRelayHost( 5182): no client connected; HOST_STATUS not sent (SMS stay queued)"

    @Test fun phoneAccountLinesBecomeOneMarkerWithTheirCountAndTimes() {
        val out = LogTrim.trim(listOf(account, changed, account, ours).joinToString("\n"), 12_000)
        assertEquals("… 3 Telecom phone-account lines left out (13:03:01)\n$ours\n", out)
    }

    @Test fun telecomCallAndRouteLinesStay() {
        val call = "11-05 17:42:54.109 I/Telecom ( 1955): BluetoothDeviceManager: onDeviceConnected: Adding device with address: <mac> and devicetype=HFP: BSR.oR@AE4"
        val route = "11-05 17:42:54.110 I/Telecom ( 1955): CallAudioRouteStateMachine: Message received: BLUETOOTH_DEVICE_LIST_CHANGED=7, arg1=0"
        val calls = "11-05 17:42:55.000 I/Telecom ( 1955): CallsManager: onCallStateChanged: RINGING"
        val log = listOf(call, route, calls).joinToString("\n")
        assertEquals("$log\n", LogTrim.trim(log, 12_000))
    }

    @Test fun repeatsInARowFoldIntoTheFirstWithTheLastTime() {
        val hello = { t: String -> "11-05 13:03:$t I/SimRelayHost( 5182): daemon <- HELLO -> OK simrelayd 0.1 card=0 call=0 session=0" }
        val out = LogTrim.trim(listOf(hello("05.102"), hello("07.103"), account, hello("09.105"), ours).joinToString("\n"), 12_000)
        assertEquals(listOf(hello("05.102"), "… the line above 2 more times, last at 13:03:09",
            "… 1 Telecom phone-account lines left out (13:03:01)", ours).joinToString("\n") + "\n", out)
    }

    @Test fun threadtimeLinesAreReadToo() {
        val noise = "11-05 02:34:08.393  1852  1900 I Telecom : PhoneAccountRegistrar: getSimCallManager: SimCallManager for subId 1 queried"
        val gatt = "11-05 02:34:08.434  4844  4893 I SimRelayHost: GATT service added status=0"
        assertEquals("… 1 Telecom phone-account lines left out (02:34:08)\n$gatt\n", LogTrim.trim("$noise\n$gatt", 12_000))
    }

    @Test fun androidFourteensNamelessTelecomLinesToo() {
        val emergency = "11-05 23:07:34.469 I/Telecom (  602): : isInEmergencyCall: false: TSI.iIEC@AL4"
        val register = "11-05 19:51:16.140 W/Telecom ( 1806): : registerPhoneAccount - attempt to set a group from a non-system caller.: TSI.rPA@C6M"
        assertEquals("… 2 Telecom phone-account lines left out (23:07:34–19:51:16)\n$ours\n", LogTrim.trim("$emergency\n$register\n$ours", 12_000))
    }

    @Test fun crashesAndOtherTagsAreNeverDropped() {
        val log = listOf(
            "11-05 13:00:22.089 E/AndroidRuntime( 7973): FATAL EXCEPTION: main",
            "11-05 13:00:22.089 E/AndroidRuntime( 7973): \tat android.os.Parcel.readException(Parcel.java:2357)",
            "\tat android.os.Parcel.readException(Parcel.java:2357)",
            "\tat android.os.Parcel.readException(Parcel.java:2357)",          // not a logcat line: never folded
            "11-05 13:00:23.000 I/simrelayd( 5018): role android.app.role.SMS -> dev.simrelay.host",
        ).joinToString("\n")
        assertEquals("$log\n", LogTrim.trim(log, 12_000))
    }

    @Test fun theNewestCharactersAreKeptFromAWholeLine() {
        val lines = (0 until 400).map { i -> "11-05 13:%02d:%02d.000 I/SimRelayHost( 5182): keep-alive ack n=$i".format(i / 60, i % 60) }
        val out = LogTrim.trim(lines.joinToString("\n"), 2_000)
        assertTrue(out.length <= 2_000)
        assertTrue(out.endsWith("keep-alive ack n=399\n"))
        assertTrue(out.startsWith("11-05 "))                                  // no half line at the start
        assertFalse(out.contains("n=0\n"))
    }
}
