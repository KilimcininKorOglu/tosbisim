// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.PreciseCallState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * When the far end of an outgoing call starts ringing. Telecom shows an InCallService DIALING until the answer; the
 * modem's precise call state goes DIALING -> ALERTING on 180 Ringing (or 183 with early media). Reading it needs
 * READ_PRECISE_PHONE_STATE, a privileged permission that would stop phones booting wherever the module's privapp
 * allowlist is older ([RootBt]), so it is read as root through app_process, one helper per outgoing call:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.keremgok.hermessim.host.RootCallState watch
 *     -> "ready", then "state <slot> <foreground precise call state>" on each change, or "error ...".
 *        It exits when its stdin closes (the host let go of it), or after 3 minutes.
 *
 * 1 Nov 2026: the iPhone played its own ringback from the moment of dialing; the network alerted 19 s later and sent
 * its own ringback (another pitch and cadence): two different tones on one call. A phone stays silent until the alert.
 */
object RootCallState {
    private const val LISTEN_PRECISE_CALL_STATE = 0x00000800       // PhoneStateListener, @SystemApi
    const val ALERTING = 4                                           // PreciseCallState.PRECISE_CALL_STATE_ALERTING

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.singleOrNull() == "watch") { "usage" }
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val ctx = systemContext()
            val subs = ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            require(subs.isNotEmpty()) { "no active SIM" }
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            @Suppress("DEPRECATION")
            for (s in subs) tm.createForSubscriptionId(s.subscriptionId).listen(Listener(s.simSlotIndex), LISTEN_PRECISE_CALL_STATE)
            say("ready")
            Thread { runCatching { while (System.`in`.read() >= 0) Unit }; exitProcess(0) }.apply { isDaemon = true }.start()
            Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 180_000)
            Looper.loop()
        } catch (t: Throwable) {
            say("error ${t.cause ?: t}")
            exitProcess(1)
        }
    }

    private fun say(line: String) { println(line); System.out.flush() }

    /** A context in this bare process (as [RootBt]): telephony's service handles come with the mainline modules. */
    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        runCatching { at.getMethod("initializeMainlineModules").invoke(null) }
        val thread = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    // TelephonyCallback's precise call state interface is @SystemApi as well; PhoneStateListener works on 11 to 16.
    @Suppress("DEPRECATION")
    private class Listener(private val slot: Int) : PhoneStateListener() {
        /** The framework's hidden callback: same name and signature, so this overrides it at run time. */
        @Suppress("unused")
        fun onPreciseCallStateChanged(s: PreciseCallState) = say("state $slot ${s.foregroundCallState}")
    }

    // ------------------------------------------------------------------------------------------ in the host app

    private const val TAG = "HermesSimHost"
    private val lock = Any()
    @Volatile private var gen = 0                  // one per watch; anything from an older one is dropped
    private var proc: Process? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }   // lazy: the helper process has no looper at init

    /**
     * Watch the precise call state while the iPhone's call dials. [onState] and [onLost] (the helper failed or quit
     * on its own: no alert will come from it) run on the main thread; nothing after [stop].
     */
    fun watch(ctx: Context, onState: (slot: Int, state: Int) -> Unit, onLost: (String) -> Unit) {
        stop()
        val my = gen
        val apk = ctx.applicationInfo.sourceDir
        Thread {
            val p = runCatching {
                ProcessBuilder("su", "-c", "CLASSPATH=${Root.q(apk)} app_process /system/bin ${RootCallState::class.java.name} watch")
                    .redirectErrorStream(true).start()
            }.getOrElse { e -> main.post { if (gen == my) onLost("su: $e") }; return@Thread }
            synchronized(lock) { if (gen == my) proc = p }
            if (gen != my) { end(p); return@Thread }
            val ready = AtomicBoolean(false)
            var last = ""
            // A helper that never gets going (no root, a ROM without the hidden callback) must not leave the client silent
            main.postDelayed({
                if (gen == my && !ready.get()) { Log.w(TAG, "precise call state: no helper after 8 s"); stop(); onLost("start timeout") }
            }, 8_000)
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    val w = line.trim().split(' ')
                    when {
                        w[0] == "ready" -> { ready.set(true); Log.i(TAG, "precise call state: watching") }
                        w[0] == "state" && w.size == 3 -> {
                            val slot = w[1].toIntOrNull(); val state = w[2].toIntOrNull()
                            if (slot != null && state != null) main.post { if (gen == my) onState(slot, state) }
                        }
                        line.isNotBlank() -> last = line.trim().take(120)
                    }
                }
            }
            main.post {
                if (gen != my) return@post                      // stopped by us
                stop()
                Log.w(TAG, "precise call state: helper ended (${last.ifEmpty { "no output" }})")
                onLost(last.ifEmpty { "helper ended" })
            }
        }.apply { isDaemon = true; start() }
    }

    /** The dialing phase is over: the helper exits as its stdin closes (killed if it doesn't within 2 s). */
    fun stop() {
        val p = synchronized(lock) { gen++; proc.also { proc = null } } ?: return
        end(p)
    }

    private fun end(p: Process) = Thread {
        runCatching { p.outputStream.close() }
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
    }.apply { isDaemon = true; start() }
}
