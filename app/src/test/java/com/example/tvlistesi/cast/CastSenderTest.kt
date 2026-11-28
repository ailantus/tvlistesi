package com.example.tvlistesi.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastSenderTest {

    @Test
    fun `discovery reply is parsed`() {
        val device = CastSender.parseDiscoveryReply(
            "TVListesiCast|DEVICE|192.168.1.82|TvListesi|DREAMSTAR",
            localIp = "192.168.1.55"
        )

        assertEquals("192.168.1.82", device?.ip)
        assertEquals("TvListesi", device?.name)
        assertEquals("DREAMSTAR", device?.model)
    }

    @Test
    fun `own device is ignored`() {
        val device = CastSender.parseDiscoveryReply(
            "TVListesiCast|DEVICE|192.168.1.55|Telefon|Pixel",
            localIp = "192.168.1.55"
        )

        assertNull(device)
    }

    @Test
    fun `unrelated packets are ignored`() {
        assertNull(CastSender.parseDiscoveryReply("TVListesiCast|DISCOVER", "192.168.1.55"))
        assertNull(CastSender.parseDiscoveryReply("merhaba", "192.168.1.55"))
    }
}
