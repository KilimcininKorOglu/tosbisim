// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

/**
 * Non-UI InCallService (bound by Telecom because we hold CONTROL_INCALL_EXPERIENCE as a privileged
 * app; the stock dialer stays the UI). Forwards every call and state change to CallRegistry.
 */
class CallMonitor : InCallService() {
    companion object { private const val TAG = "HermesSimHost" }

    private val callbacks = HashMap<Call, Call.Callback>()

    override fun onCreate() {
        super.onCreate()
        CallRegistry.init(this)
        HostService.ensure(this)                 // a call is the worst moment to find the host down
    }

    override fun onCallAdded(call: Call) {
        // The removal ran: the owner's own call, not ours to relay or silence
        if (Retire.givenBack(this)) { Log.i(TAG, "call added: phone given back, left alone"); return }
        val cb = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) = CallRegistry.changed(c)
            override fun onConnectionEvent(c: Call, event: String, extras: android.os.Bundle?) = CallRegistry.connectionEvent(c, event)
        }
        call.registerCallback(cb)
        callbacks[call] = cb
        Log.i(TAG, "call added: state=${call.stateCompat} dir=${call.details.callDirection} slot=${CallRegistry.slotOf(call)}")
        CallRegistry.added(call)
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        Log.i(TAG, "call removed: state=${call.stateCompat}")
        CallRegistry.removed(call)
    }
}
