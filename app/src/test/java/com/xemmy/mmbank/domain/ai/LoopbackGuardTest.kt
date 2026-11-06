package com.xemmy.mmbank.domain.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackGuardTest {

    @Test
    fun `accepts ipv4 loopback with port and path`() {
        assertTrue(LoopbackGuard.isLoopbackUrl("http://127.0.0.1:11434/v1/chat/completions"))
    }

    @Test
    fun `accepts any 127 slash 8 address`() {
        assertTrue(LoopbackGuard.isLoopbackUrl("http://127.5.6.7:8080/v1/chat/completions"))
    }

    @Test
    fun `accepts localhost`() {
        assertTrue(LoopbackGuard.isLoopbackUrl("http://localhost:1234/v1/chat/completions"))
    }

    @Test
    fun `accepts ipv6 loopback in brackets`() {
        assertTrue(LoopbackGuard.isLoopbackUrl("http://[::1]:11434/v1/chat/completions"))
    }

    @Test
    fun `accepts https loopback`() {
        assertTrue(LoopbackGuard.isLoopbackUrl("https://127.0.0.1:443/v1/chat/completions"))
    }

    @Test
    fun `rejects 0000 wildcard bind address`() {
        assertFalse(LoopbackGuard.isLoopbackUrl("http://0.0.0.0:11434/v1/chat/completions"))
    }

    @Test
    fun `rejects a lan address`() {
        assertFalse(LoopbackGuard.isLoopbackUrl("http://192.168.1.50:11434/v1/chat/completions"))
        assertFalse(LoopbackGuard.isLoopbackUrl("http://10.0.0.2:11434/v1/chat/completions"))
    }

    @Test
    fun `rejects a public hostname`() {
        assertFalse(LoopbackGuard.isLoopbackUrl("https://api.openai.com/v1/chat/completions"))
    }

    @Test
    fun `rejects a hostname that merely contains localhost`() {
        assertFalse(LoopbackGuard.isLoopbackUrl("http://localhost.evil.com/v1/chat/completions"))
    }

    @Test
    fun `rejects non-http schemes`() {
        assertFalse(LoopbackGuard.isLoopbackUrl("ftp://127.0.0.1/x"))
        assertFalse(LoopbackGuard.isLoopbackUrl("file:///etc/passwd"))
    }

    @Test
    fun `rejects malformed or empty input`() {
        assertFalse(LoopbackGuard.isLoopbackUrl(""))
        assertFalse(LoopbackGuard.isLoopbackUrl("not a url"))
        assertFalse(LoopbackGuard.isLoopbackUrl("127.0.0.1:11434"))
    }
}
