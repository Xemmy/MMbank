package com.xemmy.mmbank.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPrivacyStatusTest {
    @Test fun onlyEnabledOnlineSendsTextOffDevice() {
        assertTrue(AiPrivacyStatus.sendsTextOffDevice(true, AiProviderMode.ONLINE))
        assertFalse(AiPrivacyStatus.sendsTextOffDevice(true, AiProviderMode.LOCAL))
        assertFalse(AiPrivacyStatus.sendsTextOffDevice(false, AiProviderMode.ONLINE))
        assertFalse(AiPrivacyStatus.sendsTextOffDevice(false, AiProviderMode.LOCAL))
    }

    @Test fun menuSubtitleNamesTheProvider() {
        assertEquals("Off · read messages the rules miss", AiPrivacyStatus.menuSubtitle(false, AiProviderMode.ONLINE))
        assertEquals("On · on-device server", AiPrivacyStatus.menuSubtitle(true, AiProviderMode.LOCAL))
        assertTrue(AiPrivacyStatus.menuSubtitle(true, AiProviderMode.ONLINE).contains("leaves your device"))
    }
}
