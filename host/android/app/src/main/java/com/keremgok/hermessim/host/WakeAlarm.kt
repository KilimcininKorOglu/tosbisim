// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Delayed work that must also happen while the phone is in deep sleep. A main-thread timer counts uptime, which stops
 * there: on the S20 FE, asleep most of the time, the 2-minute "client away" check ran up to 5 min late, Wi-Fi join
 * retries meant for 15 s ran minutes apart, and box mode stayed off for hours after the escape window (audit, 1 Nov
 * 2026). Each task gets both a main-thread timer (on time while awake) and an alarm allowed while idle (wakes the
 * phone, on every Android version and maker; Android may hold it back a little in deep idle). Whichever comes first
 * runs it, once.
 *
 * One task per name: setting it again replaces it, [cancel] drops it. Tasks run on the main thread. Each carries a
 * number its alarm carries too: an alarm already on its way when the timer ran the task (and the task set itself
 * again) must not run the new one early (two Wi-Fi join tries 19 ms apart, 1 Nov 2026). An alarm left by a process
 * that died finds no task: its owner schedules again when it starts.
 */
object WakeAlarm {
    private const val TAG = "HermesSimHost"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private class Task(val id: Long, val run: () -> Unit, val timer: Runnable)
    private val tasks = ConcurrentHashMap<String, Task>()
    private val ids = java.util.concurrent.atomic.AtomicLong()

    fun set(ctx: Context, name: String, delayMs: Long, run: () -> Unit) {
        tasks.remove(name)?.let { main.removeCallbacks(it.timer) }
        val id = ids.incrementAndGet()
        val t = Task(id, run, Runnable { fire(name, id) })
        tasks[name] = t
        main.postDelayed(t.timer, delayMs)
        runCatching {
            ctx.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, intent(ctx, name, id))
        }.onFailure { Log.w(TAG, "alarm $name: $it") }
    }

    fun pending(name: String): Boolean = tasks.containsKey(name)

    fun cancel(ctx: Context, name: String) {
        val t = tasks.remove(name) ?: return
        main.removeCallbacks(t.timer)
        runCatching { ctx.getSystemService(AlarmManager::class.java).cancel(intent(ctx, name, t.id)) }
    }

    /** Main thread (the timer or the alarm's receiver): runs the task if it's still the one [id] was set for. */
    internal fun fire(name: String, id: Long) {
        val t = tasks[name]?.takeIf { it.id == id } ?: return
        tasks.remove(name, t)
        main.removeCallbacks(t.timer)
        Bg.guard("alarm $name") { t.run() }
    }

    // The data part keeps one PendingIntent per name (extras don't tell them apart; they ride along)
    private fun intent(ctx: Context, name: String, id: Long) = PendingIntent.getBroadcast(ctx.applicationContext, 0,
        Intent(ctx.applicationContext, WakeAlarmReceiver::class.java).setData(Uri.fromParts("alarm", name, null)).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        HostService.ensure(c)                          // an alarm set by a process that has since died: host back up
        i.data?.schemeSpecificPart?.let { WakeAlarm.fire(it, i.getLongExtra("id", -1)) }
    }
}
