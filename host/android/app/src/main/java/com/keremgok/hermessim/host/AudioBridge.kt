// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.util.Log
import java.io.DataInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Closes a daemon audio socket so both ends notice at once. close() alone leaves the socket open while one of our
 * threads is blocked reading it (the read holds it): after a Bluetooth call the daemon kept the dead client until the
 * next call's first frame woke that read (1 Nov 2026). shutdown wakes our reader and tells the daemon now.
 */
internal fun LocalSocket.closeNow() {
    runCatching { shutdownInput() }
    runCatching { shutdownOutput() }
    runCatching { close() }
}

/**
 * Bridges the audio daemon (hermessimd, unix SEQPACKET "@hermessimd-audio") and the iPhone (BLE L2CAP CoC).
 * The daemon owns the call-audio codec and PCM I/O; this just forwards Opus packets both ways.
 *
 * Wire formats:
 *   daemon socket : one datagram = [u16 seq LE][u8 flags][opus]  (the daemon's packet, verbatim)
 *   L2CAP stream  : [u16 len LE][ that daemon packet ]           (length-prefixed, since CoC is a stream)
 *
 * One L2CAP server channel, its PSM published in the GATT AUDIO_PSM characteristic. The iPhone reads the
 * PSM and connects when it answers a call; on connect we open the daemon socket and run two pump threads.
 */
@SuppressLint("MissingPermission")
class AudioBridge(private val adapter: BluetoothAdapter, private val ble: BleLink) {
    companion object {
        private const val TAG = "HermesSimHost"; private const val MAX = 512
        private const val BACKLOG = 10            // 100 ms of caller audio waiting for the link, then the oldest goes
    }

    private var server: BluetoothServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun start() {
        val t = Thread({ acceptLoop() }, "audio-accept")
        acceptThread = t
        t.isDaemon = true
        t.start()
    }

    private fun acceptLoop() {
        var fails = 0
        while (acceptThread != null) {
            if (!adapter.isEnabled) { Thread.sleep(2000); continue }   // radio off: wait quietly (BleLink rebuilds on STATE_ON)
            if (!ble.allowed()) { Thread.sleep(2000); continue }       // no Bluetooth permission yet: self-repair grants it
            val srv = try {
                adapter.listenUsingL2capChannel()      // secure: needs the bonded, encrypted link
            } catch (e: Exception) {
                // A controller without LE credit-based channels never succeeds: back off to a minute, report once
                fails++
                if (fails == 3) Diag.event("audio_channel_failed", org.json.JSONObject().put("what", e.javaClass.simpleName), withLog = false)
                if (fails <= 3 || fails % 30 == 0) Log.e(TAG, "L2CAP listen failed ($fails): $e")
                Thread.sleep(minOf(60_000L, 2_000L shl minOf(fails - 1, 5))); continue
            }
            fails = 0
            server = srv
            ble.audioPsm = srv.psm
            Log.i(TAG, "audio L2CAP listening psm=${srv.psm}")
            try {
                while (acceptThread != null) {
                    val sock = srv.accept()             // one iPhone audio connection at a time
                    Log.i(TAG, "audio L2CAP connected ${sock.remoteDevice?.address}")
                    bridge(sock)
                    Log.i(TAG, "audio L2CAP closed")
                }
            } catch (e: Exception) {
                Log.i(TAG, "audio accept ended: $e")
            } finally {
                runCatching { srv.close() }
            }
        }
    }

    /** Run until either side closes. Blocks the accept thread for the duration of one call's audio. */
    private fun bridge(l2cap: BluetoothSocket) {
        val ds: LocalSocket
        try {
            ds = LocalSocket(LocalSocket.SOCKET_SEQPACKET)
            ds.connect(LocalSocketAddress("hermessimd-audio", LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: Exception) {
            Log.e(TAG, "daemon audio socket connect failed: $e")
            runCatching { l2cap.close() }
            return
        }
        Log.i(TAG, "audio bridge up (L2CAP <-> hermessimd)")
        val alive = AtomicBoolean(true)
        val backlog = Backlog()
        val down = Thread({ pumpDown(ds, backlog, alive) }, "audio-down").apply { isDaemon = true; start() }
        val send = Thread({ pumpSend(l2cap, backlog, alive) }, "audio-send").apply { isDaemon = true; start() }
        try {
            pumpUp(l2cap, ds, alive)
        } catch (_: Exception) {
        } finally {
            alive.set(false)
            backlog.wake()
            runCatching { l2cap.close() }
            ds.closeNow()
            down.join(500); send.join(500)
            Log.i(TAG, "audio bridge down")
        }
    }

    /**
     * daemon -> backlog: each datagram becomes one length-prefixed frame, and every frame waiting goes out in ONE
     * write ([pumpSend]). On Android one write is one L2CAP packet: a link that carries one packet per connection event
     * (11.25 ms) can't take 100 single-frame writes a second. The write then blocked, the daemon's socket filled and it
     * dropped what didn't fit, silently: a client got 88 frames a second, steadily, through whole calls (a Mi 11 Pro
     * host with a Meizu client, 6 Nov 2026). This reader keeps the daemon drained; the backlog stays short.
     */
    private fun pumpDown(ds: LocalSocket, backlog: Backlog, alive: AtomicBoolean) {
        val din = ds.inputStream
        val buf = ByteArray(MAX)
        var stallLogged = 0L
        try {
            while (alive.get()) {
                val n = din.read(buf)
                if (n <= 0) break
                val framed = ByteArray(n + 2)
                framed[0] = (n and 0xff).toByte()
                framed[1] = (n shr 8).toByte()
                System.arraycopy(buf, 0, framed, 2, n)
                val dropped = backlog.add(framed)
                // A write that never returns logs nothing itself: say so from here (a client got no caller audio
                // for 15 s while its own audio still reached the host, 6 Nov 2026)
                val now = SystemClock.elapsedRealtime()
                if (dropped && now - stallLogged > 5000) { stallLogged = now; Log.w(TAG, "audio to the phone: Bluetooth isn't taking it, dropping the oldest") }
            }
        } catch (_: Exception) {} finally { alive.set(false); backlog.wake() }
    }

    /** backlog -> L2CAP: every frame waiting, up to the link's largest packet, in one write. */
    private fun pumpSend(l2cap: BluetoothSocket, backlog: Backlog, alive: AtomicBoolean) {
        val out = l2cap.outputStream
        val maxTx = runCatching { l2cap.maxTransmitPacketSize }.getOrDefault(0).let { if (it in 64..4096) it else MAX + 2 }
        val buf = ByteArray(maxOf(maxTx, MAX + 2))
        try {
            while (alive.get()) {
                val (n, frames) = backlog.take(buf, maxTx, alive)
                if (n == 0) continue
                val t0 = SystemClock.elapsedRealtime()
                out.write(buf, 0, n)
                backlog.wrote(frames, (SystemClock.elapsedRealtime() - t0).toInt(), t0)
                    ?.let { Log.i(TAG, "audio to the phone 5s: $it (max $maxTx B)") }
            }
        } catch (_: Exception) {} finally { alive.set(false) }
    }

    /** L2CAP -> daemon: de-frame, forward each packet as one datagram. Returns when either side ends or desyncs. */
    private fun pumpUp(l2cap: BluetoothSocket, ds: LocalSocket, alive: AtomicBoolean) {
        val din = DataInputStream(l2cap.inputStream)
        val dout = ds.outputStream
        val pkt = ByteArray(MAX)
        while (alive.get()) {
            val lo = din.read(); val hi = din.read()
            if (lo < 0 || hi < 0) return
            val len = lo or (hi shl 8)
            if (len <= 0 || len > MAX) return       // desync: drop the channel
            din.readFully(pkt, 0, len)
            dout.write(pkt, 0, len)                 // SEQPACKET: one write = one datagram
        }
    }

    /** Caller audio waiting for the Bluetooth link: [pumpDown] adds frames, [pumpSend] takes them in batches. */
    private class Backlog {
        private val q = ArrayDeque<ByteArray>()
        private val lock = ReentrantLock()
        private val ready = lock.newCondition()
        private val st = IntArray(5)                 // frames in, frames out, writes, dropped, slowest write (ms)
        private var nextLog = SystemClock.elapsedRealtime() + 5000

        /** Queue one frame; true if the oldest had to go. */
        fun add(frame: ByteArray): Boolean = lock.withLock {
            q.addLast(frame); st[0]++
            var dropped = false
            while (q.size > BACKLOG) { q.removeFirst(); st[3]++; dropped = true }
            ready.signal()
            dropped
        }

        /** Wait for frames, then copy as many as fit in [max] bytes (at least one) into [buf]: (bytes, frames). */
        fun take(buf: ByteArray, max: Int, alive: AtomicBoolean): Pair<Int, Int> = lock.withLock {
            while (q.isEmpty() && alive.get()) ready.await(200, TimeUnit.MILLISECONDS)
            var n = 0; var frames = 0
            while (q.isNotEmpty() && (n == 0 || n + q.first().size <= max)) {
                val f = q.removeFirst(); System.arraycopy(f, 0, buf, n, f.size); n += f.size; frames++
            }
            n to frames
        }

        /** Wake a sender waiting in [take] (the bridge is closing). */
        fun wake() = lock.withLock { ready.signalAll() }

        /**
         * Count one write that took [ms]. Every 5 s, the window's numbers if the link lagged (frames shared a write, a
         * drop, a slow write), else null: healthy calls already log every 5 s in the daemon's line.
         */
        fun wrote(frames: Int, ms: Int, now: Long): String? = lock.withLock {
            st[1] += frames; st[2]++; if (ms > st[4]) st[4] = ms
            if (now <= nextLog) return@withLock null
            nextLog = now + 5000
            val lagged = st[3] > 0 || st[2] < st[1] || st[4] >= 40
            val line = if (lagged) "frames=${st[1]}/${st[0]} writes=${st[2]} dropped=${st[3]} slowest write=${st[4]} ms" else null
            st.fill(0)
            line
        }
    }

    fun stop() {
        acceptThread = null
        runCatching { server?.close() }
    }
}
