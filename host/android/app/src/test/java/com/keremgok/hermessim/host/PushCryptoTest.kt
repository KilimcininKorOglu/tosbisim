// HermesSIM host. Copyright (C) 2026 SIM Relay authors. GPL-3.0-or-later.
package com.keremgok.hermessim.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/** Push sealing: [u64 ms][envelope] under the "push" key; writes a vector the iPhone side's opener is checked with. */
class PushCryptoTest {
    @Test fun sealsUnderItsOwnKey() {
        val c = RemoteCrypto(ByteArray(32) { it.toByte() })
        val env = "envelope bytes".toByteArray()
        val p = c.sealPush(env)
        assertEquals(12 + 8 + env.size + 16, p.size)
        assertNotEquals(p.copyOfRange(20, p.size - 16).toList(), c.sealEnvelope(env).copyOfRange(20, p.size - 16).toList())
        File(System.getProperty("java.io.tmpdir"), "tosbisim-push-vector.txt").writeText(java.util.Base64.getEncoder().encodeToString(p))
    }
}
