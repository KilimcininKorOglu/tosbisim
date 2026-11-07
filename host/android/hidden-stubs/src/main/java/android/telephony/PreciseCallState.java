// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package android.telephony;

/**
 * Compile-time stand-in for the framework's @SystemApi class, so RootCallState can declare the hidden
 * PhoneStateListener callback. compileOnly: never packaged, the phone's own class is used at run time.
 */
public final class PreciseCallState {
    public int getForegroundCallState() { throw new RuntimeException("stub"); }
}
