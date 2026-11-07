// SIM Relay host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package dev.simrelay.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import dev.simrelay.protocol.v1.CallEvent
import dev.simrelay.protocol.v1.Envelope
import dev.simrelay.protocol.v1.SmsReceived
import org.json.JSONObject

/**
 * Push wake-up (protocol/README.md "Push"): when the phone can't hear us, hermessim.keremgok.tr asks FCM to wake it. A
 * ringing call goes as a VoIP push (iOS rings it through CallKit and starts the app, which then reaches us over
 * Bluetooth or the internet); a text the iPhone didn't take within a few seconds goes as an alert push its
 * notification extension decrypts. Both carry the same sealed envelope as the room (push key, AAD "h2c"); the
 * server sees only ciphertext. The key is the iPhone's PushSetup (Internet mode may be off); an iPhone that never sent
 * one (older than 0.9.5) is pushed with remote mode's key.
 */
object Push {
    private const val TAG = "SimRelayHost"
    /** A text not acked by then (the iPhone asleep or out of reach, even if it looks present in the room) is pushed. */
    private const val SMS_ACK_WAIT_MS = 4000L
    private lateinit var app: Context
    private val wake by lazy {
        app.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "tosbisim:sms-push").apply { setReferenceCounted(false) }
    }
    private val main = Handler(Looper.getMainLooper())
    private val exec = Bg.single("push")
    private val pushedCalls = LinkedHashSet<String>()           // main thread

    @Volatile private var crypto: RemoteCrypto? = null

    fun start(ctx: Context) {
        app = ctx.applicationContext
        prefs().getString("key", null)?.let { crypto = RemoteCrypto(Base64.decode(it, Base64.NO_WRAP)) }
    }

    private fun prefs() = app.getSharedPreferences("push", Context.MODE_PRIVATE)

    /** PushSetup over Bluetooth (32 bytes), or empty to forget it (Unpair, Retire). */
    fun setKey(k: ByteArray) {
        if (!::app.isInitialized) return
        if (k.size == 32) {
            prefs().edit().putString("key", Base64.encodeToString(k, Base64.NO_WRAP)).apply()
            crypto = RemoteCrypto(k)
            Log.i(TAG, "push: key set (${keyId()?.let { RemoteCrypto.hex(it) }})")
        } else {
            prefs().edit().remove("key").apply()
            crypto = null
            Log.i(TAG, "push: key forgotten")
        }
    }

    /** First 8 bytes of sha256(key): the iPhone sends its key again when this isn't its own. */
    fun keyId(): ByteArray? = if (!::app.isInitialized) null else prefs().getString("key", null)?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(Base64.decode(it, Base64.NO_WRAP)).copyOf(8)
    }

    /** Relay.callEvent, main: an incoming call is ringing and no iPhone is on Bluetooth. Once per call. */
    fun call(e: CallEvent) {
        if (!::app.isInitialized || !pushedCalls.add(e.callId)) return
        while (pushedCalls.size > 20) pushedCalls.remove(pushedCalls.first())
        // Basic mode: the iPhone doesn't ring host calls; a VoIP push must ring, so this one is a note instead.
        val voip = VoiceGate.fullVoice
        send(if (voip) "call" else "note", Envelope.newBuilder().setCallEvent(e).build().toByteArray(),
            if (voip) null else "basic-${e.callId}", "call ${e.callId.take(8)}")
    }

    /** Relay.smsReceived, main: pushed only if the iPhone hasn't acked it (stored it) shortly. */
    fun sms(m: SmsReceived) {
        if (!::app.isInitialized) return
        // The wait counts uptime: the phone wakes for the text and fell back asleep inside it, so one push went out after
        // 131 s, not 4 (31 Oct 2026). Held awake for the wait and the push's request.
        wake.acquire(SMS_ACK_WAIT_MS + 10_000)
        main.postDelayed({
            if (!SmsStore.isPending(app, m.id)) return@postDelayed
            send("sms", Envelope.newBuilder().setSmsReceived(fitted(m)).build().toByteArray(), m.id, "text ${m.id.take(8)}")
        }, SMS_ACK_WAIT_MS)
    }

    /** adb test: push a made-up call or text whatever the link (Relay's rules are not involved). */
    fun test(kind: String) {
        val id = java.util.UUID.randomUUID().toString()
        if (kind == "sms") send("sms", Envelope.newBuilder().setSmsReceived(SmsReceived.newBuilder().setId(id)
            .setFromRaw("+905550000000").setFromE164("+905550000000").setBody("HermesSIM Host push test")
            .setReceivedMs(System.currentTimeMillis()).setSlot(-1)).build().toByteArray(), id, "test text")
        else send("call", Envelope.newBuilder().setCallEvent(CallEvent.newBuilder().setCallId(id)
            .setState(CallEvent.State.RINGING).setIncoming(true).setNumberRaw("+905550000000").setNumberE164("+905550000000")
            .setSlot(-1).setStateSinceMs(System.currentTimeMillis())).build().toByteArray(), null, "test call")
    }

    /** An alert push carries at most 4 KB: a long text is cut for the notification (the whole one follows on sync). */
    private fun fitted(m: SmsReceived): SmsReceived {
        val bytes = m.body.toByteArray()
        if (bytes.size <= 1500) return m
        var cut = String(bytes, 0, 1500, Charsets.UTF_8).trimEnd('�')
        cut += "…"
        return m.toBuilder().setBody(cut).build()
    }

    private fun send(kind: String, env: ByteArray, collapseId: String?, what: String) {
        val c = crypto ?: RemoteLink.crypto ?: run { Log.i(TAG, "push: $what not sent (no push key from the phone yet)"); return }
        exec.execute {
            val body = JSONObject().put("room_token", RemoteCrypto.hex(c.roomToken)).put("kind", kind)
                .put("sealed", Base64.encodeToString(c.sealPush(env), Base64.NO_WRAP))
            if (collapseId != null && collapseId.length <= 64 && collapseId.all { it.isLetterOrDigit() || it in "._-" })
                body.put("collapse_id", collapseId)
            val t = System.currentTimeMillis()
            val (code, _) = Api.postStatus("/push/send", body)
            Log.i(TAG, "push $kind: $what -> HTTP $code in ${System.currentTimeMillis() - t} ms")
        }
    }
}
