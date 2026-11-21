package com.xemmy.mmbank.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureScreenPolicyTest {
    @Test fun `nothing enabled does nothing`() =
        assertEquals(SecureScreenDecision(false, false), SecureScreenPolicy.decide(false, false, 35))

    @Test fun `block screenshots sets the flag only`() =
        assertEquals(SecureScreenDecision(true, false), SecureScreenPolicy.decide(true, false, 35))

    @Test fun `app lock on API 33+ uses the recents switch not the flag`() =
        assertEquals(SecureScreenDecision(false, true), SecureScreenPolicy.decide(false, true, 33))

    @Test fun `app lock below API 33 falls back to the flag`() =
        assertEquals(SecureScreenDecision(true, false), SecureScreenPolicy.decide(false, true, 30))

    @Test fun `both on API 34 sets flag and recents switch`() =
        assertEquals(SecureScreenDecision(true, true), SecureScreenPolicy.decide(true, true, 34))
}
