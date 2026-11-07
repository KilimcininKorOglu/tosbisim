// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import dev.simrelay.protocol.v1.SetSim
import dev.simrelay.protocol.v1.Sim
import dev.simrelay.protocol.v1.UnlockSim
import dev.simrelay.protocol.v1.UnlockSimResult
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * SIM slots: per-SIM status for the client and the per-SIM settings it can change (line on/off, data
 * roaming, which SIM carries mobile data). The setters are @SystemApi: reached by reflection (system APIs
 * are not hidden-API restricted) and allowed by MODIFY_PHONE_STATE, which the Magisk module grants us as a
 * privileged app. Calls and SMS pick their SIM per request (Dial.slot / SmsSend.slot), so the host's own
 * default call/SMS SIM does not matter.
 */
object Sims {
    private const val TAG = "SimRelayHost"
    private val attempts = ConcurrentHashMap<Int, Int>()    // slot -> PIN/PUK attempts left (cached, see lockOf)

    @SuppressLint("MissingPermission")
    fun active(ctx: Context): List<SubscriptionInfo> =
        ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()

    /**
     * Inserted physical SIMs, including ones switched off from the client (not in the active list), and active eSIM
     * lines (an eSIM was invisible to the iPhone yet got its data roaming switched off, 31 Oct 2026). A downloaded eSIM
     * that isn't active is left out: it has no slot to address.
     */
    @SuppressLint("MissingPermission")
    fun inserted(ctx: Context): List<SubscriptionInfo> {
        val activeIds = active(ctx).map { it.subscriptionId }.toSet()
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        @Suppress("UNCHECKED_CAST")
        val all = runCatching {
            SubscriptionManager::class.java.getMethod("getAvailableSubscriptionInfoList").invoke(sm) as List<SubscriptionInfo>?
        }.onFailure { Log.w(TAG, "getAvailableSubscriptionInfoList: $it") }.getOrNull() ?: active(ctx)
        return all.filter { it.simSlotIndex >= 0 && (!it.isEmbedded || it.subscriptionId in activeIds) }.sortedBy { it.simSlotIndex }
    }

    /**
     * SIM inserted/removed/switched, or default data SIM moved ([changed]); a SIM's carrier config (re)loaded: VoLTE
     * again; a SIM's signal level or roaming changed ([radioChanged]).
     */
    fun watch(ctx: Context, changed: () -> Unit, radioChanged: () -> Unit) {
        ctx.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) = volteOn(ctx)
        }, android.content.IntentFilter(android.telephony.CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED))
        listenRadio(ctx, radioChanged)
        ctx.getSystemService(SubscriptionManager::class.java).addOnSubscriptionsChangedListener(
            ctx.mainExecutor, object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() { listenRadio(ctx, radioChanged); changed() }
            })
    }

    /**
     * A SIM losing or getting back service, or starting or stopping roaming: pushed at once. They reached the iPhone only
     * with the next unrelated status (about hourly on a charge-capped host), and its automatic hotspot waits on them
     * ("no mobile signal", roaming with data roaming off; audit, 1 Nov 2026). Signal steps in between are not pushed: each
     * would wake the iPhone over Bluetooth, and they ride along with the next status. Main thread. PhoneStateListener:
     * one path on Android 11 to 16.
     */
    @Suppress("DEPRECATION")
    private val radio = mutableListOf<Pair<TelephonyManager, android.telephony.PhoneStateListener>>()

    @Suppress("DEPRECATION")
    private fun listenRadio(ctx: Context, changed: () -> Unit) {
        radio.forEach { (t, l) -> t.listen(l, android.telephony.PhoneStateListener.LISTEN_NONE) }
        radio.clear()
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        for (sub in active(ctx)) runCatching {
            val t = tm.createForSubscriptionId(sub.subscriptionId)
            val l = object : android.telephony.PhoneStateListener(ctx.mainExecutor) {
                var noSignal: Boolean? = null
                var roaming: Boolean? = null
                override fun onSignalStrengthsChanged(s: android.telephony.SignalStrength) {
                    val none = s.level == 0
                    if (none != noSignal) { noSignal = none; changed() }
                }
                override fun onServiceStateChanged(s: android.telephony.ServiceState) {
                    if (s.roaming != roaming) { roaming = s.roaming; changed() }
                }
            }
            t.listen(l, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or android.telephony.PhoneStateListener.LISTEN_SERVICE_STATE)
            radio += t to l
        }.onFailure { Log.w(TAG, "signal/roaming updates for SIM ${sub.simSlotIndex}: $it") }
    }

    fun log(ctx: Context) = Log.i(TAG, "SIMs: " + status(ctx).joinToString("; ") {
        "slot ${it.slot} ${it.operatorName}/${it.countryIso} on=${it.enabled} signal=${it.signalLevel} volte=${it.volte} " +
            "roaming=${it.roaming} dataRoaming=${it.dataRoaming} data=${it.defaultData} card=${it.carrierId}"
    }.ifEmpty { "none" })

    fun countryIso(ctx: Context, slot: Int): String =
        active(ctx).firstOrNull { it.simSlotIndex == slot }?.countryIso.orEmpty()

    /** A number in E.164 for the client, read in the country of the SIM it came through: else the network the phone
     *  is on, else any SIM's country (no fixed default: a UK SIM's "07..." is not a Turkish number). "" if unknown. */
    fun e164(ctx: Context, raw: String, slot: Int): String {
        if (raw.isEmpty()) return ""
        val iso = countryIso(ctx, slot).ifEmpty { ctx.getSystemService(android.telephony.TelephonyManager::class.java).networkCountryIso.orEmpty() }
            .ifEmpty { active(ctx).firstNotNullOfOrNull { it.countryIso?.takeIf(String::isNotEmpty) }.orEmpty() }
        return if (iso.isEmpty()) (raw.takeIf { it.startsWith("+") } ?: "")
        else android.telephony.PhoneNumberUtils.formatNumberToE164(raw, iso.uppercase()) ?: ""
    }

    fun subIdForSlot(ctx: Context, slot: Int): Int =
        active(ctx).firstOrNull { slot < 0 || it.simSlotIndex == slot }?.subscriptionId
            ?: SubscriptionManager.getDefaultSmsSubscriptionId()

    @SuppressLint("MissingPermission")
    fun status(ctx: Context): List<Sim> {
        val defaultData = SubscriptionManager.getDefaultDataSubscriptionId()
        val activeIds = active(ctx).map { it.subscriptionId }.toSet()
        val inserted = inserted(ctx)
        // A PIN-locked SIM may have no subscription yet: list it by slot so the client can unlock it.
        val locked = (0 until ctx.getSystemService(TelephonyManager::class.java).activeModemCount)
            .filter { slot -> inserted.none { it.simSlotIndex == slot } && lockOf(ctx, slot) != Sim.Lock.UNLOCKED }
            .map { slot ->
                val lock = lockOf(ctx, slot)
                Sim.newBuilder().setSlot(slot).setEnabled(false).setLock(lock).setAttemptsLeft(attemptsFor(ctx, slot, lock)).build()
            }
        return (inserted.map { info -> simFor(ctx, info, defaultData, activeIds) } + locked).sortedBy { it.slot }
    }

    @SuppressLint("MissingPermission")
    private fun simFor(ctx: Context, info: SubscriptionInfo, defaultData: Int, activeIds: Set<Int>): Sim {
        val sub = info.subscriptionId
        val on = sub in activeIds
        val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
        val b = Sim.newBuilder()
            .setSlot(info.simSlotIndex)
            .setOperatorName(info.carrierName?.toString().orEmpty())
            .setCountryIso(info.countryIso.orEmpty())
            .setEnabled(on)
            .setDefaultData(sub == defaultData)
            .setCarrierId(cardId(info))
        if (on) {
            b.setSignalLevel(runCatching { tm.signalStrength?.level ?: 0 }.getOrDefault(0))
                .setRoaming(runCatching { tm.isNetworkRoaming }.getOrDefault(false))
                .setDataRoaming(runCatching { tm.isDataRoamingEnabled }.getOrDefault(false))
                .setVolte(volte(ctx, sub))
                .setNumberE164(runCatching { number(ctx, info) }.getOrDefault(""))
        }
        val lock = lockOf(ctx, info.simSlotIndex)
        if (lock == Sim.Lock.UNLOCKED) attempts.remove(info.simSlotIndex)
        return b.setLock(lock).setAttemptsLeft(attemptsFor(ctx, info.simSlotIndex, lock)).build()
    }

    // ------------------------------------------------------------------ SIM PIN

    private fun lockOf(ctx: Context, slot: Int): Sim.Lock =
        when (ctx.getSystemService(TelephonyManager::class.java).getSimState(slot)) {
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> Sim.Lock.PIN_REQUIRED
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> Sim.Lock.PUK_REQUIRED
            TelephonyManager.SIM_STATE_PERM_DISABLED -> Sim.Lock.PERM_DISABLED
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> Sim.Lock.OTHER_LOCK
            else -> Sim.Lock.UNLOCKED
        }

    /**
     * Attempts left; asked once per lock with an empty PIN/PUK, exactly as AOSP's keyguard does (no attempt used).
     * The modem call can take seconds and status is built on the main thread (an ANR risk, audit 19 Oct): the first
     * status says "unknown", the answer comes off the main thread and [attemptsKnown] sends a fresh status.
     */
    @Volatile var attemptsKnown: () -> Unit = {}
    private val asking = java.util.Collections.synchronizedSet(HashSet<Int>())
    private fun attemptsFor(ctx: Context, slot: Int, lock: Sim.Lock): Int {
        if (lock != Sim.Lock.PIN_REQUIRED && lock != Sim.Lock.PUK_REQUIRED) return -1
        attempts[slot]?.let { return it }
        if (asking.add(slot)) imsExec.execute {
            try {
                val r = supply(ctx, slot, if (lock == Sim.Lock.PUK_REQUIRED) "" else null, "")
                attempts[slot] = r?.second ?: -1
            } finally { asking.remove(slot) }
            attemptsKnown()
        }
        return -1
    }

    /** Client PIN (or PUK + new PIN). Runs the modem call off the main thread; never logs the codes. */
    fun unlock(ctx: Context, u: UnlockSim, done: (UnlockSimResult) -> Unit) = Thread {
        val r = supply(ctx, u.slot, u.puk.ifEmpty { null }, u.pin)
        val ok = r?.first == true
        if (ok) attempts.remove(u.slot) else r?.second?.let { attempts[u.slot] = it }
        Thread.sleep(300)
        val lock = lockOf(ctx, u.slot)
        Log.i(TAG, "SIM slot ${u.slot} ${if (u.puk.isEmpty()) "PIN" else "PUK"} entry: ${if (ok) "unlocked" else "refused"}, " +
            "attempts left ${r?.second ?: "?"}, now $lock")
        done(UnlockSimResult.newBuilder().setSlot(u.slot).setOk(ok).setAttemptsLeft(if (ok) -1 else r?.second ?: -1)
            .setLock(lock).build())
    }.start()

    /** supplyIccLockPin / supplyIccLockPuk (@SystemApi, MODIFY_PHONE_STATE) -> (success, attempts left). */
    @SuppressLint("MissingPermission")
    private fun supply(ctx: Context, slot: Int, puk: String?, pin: String): Pair<Boolean, Int>? = runCatching {
        val sub = inserted(ctx).firstOrNull { it.simSlotIndex == slot }?.subscriptionId ?: subIdOfSlot(slot) ?: return null
        val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
        val res = if (puk != null)
            TelephonyManager::class.java.getMethod("supplyIccLockPuk", String::class.java, String::class.java).invoke(tm, puk, pin)
        else
            TelephonyManager::class.java.getMethod("supplyIccLockPin", String::class.java).invoke(tm, pin)
        val type = res!!.javaClass.getMethod("getResult").invoke(res) as Int            // PinResult: 0 = success
        val left = res.javaClass.getMethod("getAttemptsRemaining").invoke(res) as Int
        (type == 0) to left
    }.onFailure { Log.w(TAG, "SIM slot $slot PIN call failed: ${(it.cause ?: it).javaClass.simpleName}") }.getOrNull()

    /** Turn the SIM PIN lock on/off (needs the current PIN; a wrong one uses an attempt). Debug/test path. */
    fun setLockEnabled(ctx: Context, slot: Int, pin: String, on: Boolean) = Thread {
        val r = runCatching {
            val sub = inserted(ctx).firstOrNull { it.simSlotIndex == slot }?.subscriptionId ?: error("no SIM in slot $slot")
            val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
            val res = TelephonyManager::class.java.getMethod("setIccLockEnabled", Boolean::class.java, String::class.java)
                .invoke(tm, on, pin)!!
            val type = res.javaClass.getMethod("getResult").invoke(res) as Int
            val left = res.javaClass.getMethod("getAttemptsRemaining").invoke(res) as Int
            "${if (type == 0) "OK" else "REFUSED (result $type)"}, attempts left $left"
        }.getOrElse { "failed: ${(it.cause ?: it)}" }
        Log.i(TAG, "SIM slot $slot PIN lock ${if (on) "on" else "off"}: $r")
    }.start()

    private fun subIdOfSlot(slot: Int): Int? = runCatching {
        (SubscriptionManager::class.java.getMethod("getSubscriptionIds", Int::class.java).invoke(null, slot) as IntArray?)
            ?.firstOrNull()
    }.getOrNull()

    /**
     * Applies a client SetSim. Returns null on success or a short reason. A SIM seen for the first time gets
     * data roaming switched off (cost protection) before the client ever asks.
     */
    fun apply(ctx: Context, s: SetSim): String? {
        val info = inserted(ctx).firstOrNull { it.simSlotIndex == s.slot } ?: return "no SIM in slot ${s.slot}"
        val sub = info.subscriptionId
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        // An eSIM switched off may leave the list (nothing to switch back on from the iPhone, and the host has no screen)
        if (s.hasEnabled() && !s.enabled && info.isEmbedded) return "an eSIM line can't be switched off from HermesSIM Host yet"
        if (s.hasEnabled()) {
            val ok = call { SubscriptionManager::class.java
                .getMethod("setUiccApplicationsEnabled", Int::class.java, Boolean::class.java).invoke(sm, sub, s.enabled) }
            if (!ok) return "this phone cannot switch the SIM ${if (s.enabled) "on" else "off"}"
            Log.i(TAG, "SIM slot ${s.slot} switched ${if (s.enabled) "on" else "off"}")
        }
        if (s.hasDataRoaming()) {
            if (!setDataRoaming(ctx, sub, s.dataRoaming)) return "could not change data roaming"
            Log.i(TAG, "SIM slot ${s.slot} data roaming ${s.dataRoaming}")
        }
        if (s.makeDefaultData) {
            val ok = call { SubscriptionManager::class.java.getMethod("setDefaultDataSubId", Int::class.java).invoke(sm, sub) }
            if (!ok) return "could not move mobile data to this SIM"
            Log.i(TAG, "mobile data now on SIM slot ${s.slot}")
        }
        return null
    }

    private val volteExec = Bg.single("volte")

    /**
     * VoLTE on, once per SIM card, where the carrier offers it but the phone's switch is unset or off. MIUI/MediaTek read
     * an unset switch (siminfo volte_vt_enabled = -1) as off where AOSP falls back to the carrier default: the Redmi
     * on Vodafone TR called over 2G/3G and lost data during calls until it was switched on by hand (19 Oct 2026). Set
     * through the Settings API (ImsMmTelManager.setAdvancedCallingSettingEnabled, MODIFY_PHONE_STATE), which
     * re-registers IMS. The raw switch is read as root, so off the caller's thread. A carrier config that isn't
     * loaded yet (right after boot) says "no VoLTE": not remembered, tried again when it loads.
     */
    fun volteOn(ctx: Context) = volteExec.execute {
        val prefs = ctx.getSharedPreferences("sims", Context.MODE_PRIVATE)
        for (info in active(ctx)) runCatching {
            val key = "volte_${cardId(info)}"
            if (prefs.getBoolean(key, false)) return@runCatching
            val sub = info.subscriptionId
            val cc = ctx.getSystemService(android.telephony.CarrierConfigManager::class.java).getConfigForSubId(sub)
            if (cc?.getBoolean(android.telephony.CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL) != true) return@runCatching
            val raw = Regex("volte_vt_enabled=(-?\\d+)").find(Root.sh("content query --uri content://telephony/siminfo " +
                "--projection volte_vt_enabled --where _id=$sub", 10_000).out)?.groupValues?.get(1)?.toIntOrNull()
            val cls = Class.forName("android.telephony.ims.ImsMmTelManager")
            val mgr = cls.getMethod("createForSubscriptionId", Int::class.java).invoke(null, sub)
            val on = cls.getMethod("isAdvancedCallingSettingEnabled").invoke(mgr) as Boolean
            if (raw == null || (raw == 1 && on)) {           // switched on already (or the switch can't be read: leave it)
                if (raw != null) prefs.edit().putBoolean(key, true).apply()
                return@runCatching
            }
            cls.getMethod("setAdvancedCallingSettingEnabled", Boolean::class.javaPrimitiveType).invoke(mgr, true)
            prefs.edit().putBoolean(key, true).apply()
            Log.i(TAG, "SIM slot ${info.simSlotIndex}: VoLTE switched on (switch was $raw, setting said $on)")
            Diag.event("volte_switched_on", org.json.JSONObject().put("switch", raw).put("setting", on), withLog = false)
        }.onFailure { Log.w(TAG, "VoLTE switch, SIM slot ${info.simSlotIndex}: ${it.cause ?: it}") }
    }

    /** Data roaming off for SIMs never seen before (off by default, per-SIM switch). */
    fun protectNewSims(ctx: Context) {
        val prefs = ctx.getSharedPreferences("sims", Context.MODE_PRIVATE)
        for (info in active(ctx)) {
            val key = "seen_${cardId(info)}"
            if (prefs.getBoolean(key, false)) continue
            if (setDataRoaming(ctx, info.subscriptionId, false)) {
                prefs.edit().putBoolean(key, true).apply()
                Log.i(TAG, "new SIM in slot ${info.simSlotIndex}: data roaming off")
            }
        }
    }

    private fun setDataRoaming(ctx: Context, sub: Int, on: Boolean): Boolean {
        val tm = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
        return call { TelephonyManager::class.java.getMethod("setDataRoamingEnabled", Boolean::class.java).invoke(tm, on) }
    }

    /**
     * VoLTE/VoNR registered for voice. Android 12+: ImsMmTelManager.isAvailable (@SystemApi). Android 11 has no
     * isAvailable (it threw, so the Redmi Note 8 Pro always showed no VoLTE, 18 Oct): there TelephonyManager's
     * isVolteAvailable, else the IMS registration state (getRegistrationState: asynchronous, cached, refreshed every 30 s).
     */
    private fun volte(ctx: Context, sub: Int): Boolean = runCatching {
        val cls = Class.forName("android.telephony.ims.ImsMmTelManager")
        val mgr = cls.getMethod("createForSubscriptionId", Int::class.java).invoke(null, sub)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val isAvailable = cls.getMethod("isAvailable", Int::class.java, Int::class.java)
            // CAPABILITY_TYPE_VOICE = 1; REGISTRATION_TECH_LTE = 0, REGISTRATION_TECH_NR = 3
            return@runCatching isAvailable.invoke(mgr, 1, 0) as Boolean || isAvailable.invoke(mgr, 1, 3) as Boolean
        }
        // Android 11: TelephonyManager.isVolteAvailable (hidden, allowed) follows vendor IMS stacks; the AOSP
        // registration state below missed MediaTek's (Redmi: registered over VoLTE, state said not registered).
        runCatching {
            val tmSub = ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub)
            TelephonyManager::class.java.getMethod("isVolteAvailable").invoke(tmSub) as Boolean
        }.getOrNull()?.let { return@runCatching it }
        // Asynchronous: never wait for it on the caller's (main) thread; the answer fills the cache for the next status.
        val cached = imsCache[sub]
        if (cached == null || android.os.SystemClock.elapsedRealtime() - cached.second >= 30_000) {
            imsCache[sub] = (cached?.first ?: false) to android.os.SystemClock.elapsedRealtime()   // one request at a time
            cls.getMethod("getRegistrationState", java.util.concurrent.Executor::class.java, java.util.function.Consumer::class.java)
                .invoke(mgr, imsExec, java.util.function.Consumer<Int> { state ->
                    imsCache[sub] = (state == 2) to android.os.SystemClock.elapsedRealtime()   // REGISTRATION_STATE_REGISTERED
                })
        }
        cached?.first ?: false
    }.getOrElse { android.util.Log.w("SimRelayHost", "VoLTE check sub $sub: $it"); false }

    private val imsExec = Bg.single("ims")
    private val imsCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Boolean, Long>>()

    @SuppressLint("MissingPermission")
    private fun number(ctx: Context, info: SubscriptionInfo): String {
        @Suppress("DEPRECATION")
        val n = if (android.os.Build.VERSION.SDK_INT >= 33)
            ctx.getSystemService(SubscriptionManager::class.java).getPhoneNumber(info.subscriptionId)
        else info.number.orEmpty()
        if (n.isBlank()) return ""
        return android.telephony.PhoneNumberUtils.formatNumberToE164(n, info.countryIso.orEmpty().uppercase()) ?: ""
    }

    /** Stable id for the SIM card (ICCID hash; ICCID needs READ_PRIVILEGED_PHONE_STATE). Never the IMEI. */
    @SuppressLint("MissingPermission")
    private fun cardId(info: SubscriptionInfo): String {
        val iccid = runCatching { info.iccId }.getOrNull().orEmpty()
        val src = iccid.ifEmpty { "sub${info.subscriptionId}" }
        return MessageDigest.getInstance("SHA-256").digest(src.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }

    private fun call(block: () -> Unit): Boolean =
        runCatching(block).onFailure { Log.w(TAG, "SIM setting failed: ${(it.cause ?: it)}") }.isSuccess
}
