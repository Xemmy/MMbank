package com.xemmy.mmbank.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteOnceTest {

    @Test fun `a second tap on the same write is ignored`() {
        val once = WriteOnce<Pair<Long, Long>>()
        assertTrue(once.claim(7L to 100L))
        assertFalse(once.claim(7L to 100L))
    }

    @Test fun `another bill cycle or another bill is a new write`() {
        val once = WriteOnce<Pair<Long, Long>>()
        assertTrue(once.claim(7L to 100L))
        assertTrue(once.claim(7L to 200L))
        assertTrue(once.claim(8L to 100L))
    }

    @Test fun `a failed write can be tried again`() {
        val once = WriteOnce<String>()
        assertTrue(once.claim("acct:1"))
        once.release("acct:1")
        assertTrue(once.claim("acct:1"))
    }
}
