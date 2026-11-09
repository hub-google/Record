package com.hubgoogle.record.call

import org.junit.Assert.*
import org.junit.Test

class VoipSourceRegistryTest {
    @Test fun knownCommunicationAppsHaveDistinctSourceTags() {
        assertEquals("VOIP_LINE", VoipSourceRegistry.source("jp.naver.line.android"))
        assertEquals("VOIP_WHATSAPP", VoipSourceRegistry.source("com.whatsapp"))
        assertEquals("VOIP_TELEGRAM", VoipSourceRegistry.source("org.telegram.messenger"))
        assertEquals("VOIP_SIGNAL", VoipSourceRegistry.source("org.thoughtcrime.securesms"))
        assertEquals("VOIP_MESSENGER", VoipSourceRegistry.source("com.facebook.orca"))
        assertEquals("VOIP_DISCORD", VoipSourceRegistry.source("com.discord"))
        assertEquals("VOIP_MEET", VoipSourceRegistry.source("com.google.android.apps.tachyon"))
    }

    @Test fun unknownAppNeverImpersonatesCallSource() {
        assertNull(VoipSourceRegistry.source("com.example.fake"))
        assertNull(VoipSourceRegistry.source("jp.naver.line.android.evil"))
    }

    @Test fun everyPackageHasRecognizableUniqueSourceLabel() {
        assertTrue(VoipSourceRegistry.packages.values.all { it.startsWith("VOIP_") })
        assertEquals(VoipSourceRegistry.packages.size,
            VoipSourceRegistry.packages.values.distinct().size)
    }
}
