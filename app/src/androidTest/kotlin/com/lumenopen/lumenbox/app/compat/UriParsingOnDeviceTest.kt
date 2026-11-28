package com.lumenopen.lumenbox.app.compat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumenopen.lumenbox.auth.PkceOAuthClient
import com.lumenopen.lumenbox.relay.ParsedPairingOffer
import com.lumenopen.lumenbox.relay.X25519Keypair
import com.lumenopen.lumenbox.transport.TransportResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * The pairing QR URI and the OAuth callback are parsed with URL decoding that has to exist on
 * Android's java.* at minSdk 28; `URLDecoder.decode(String, Charset)` does not (INV-2293).
 */
@RunWith(AndroidJUnit4::class)
class UriParsingOnDeviceTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    @Test
    fun pairingUriWithPercentEncodedRelayParses() {
        val hostPub = X25519Keypair.fromPrivateSeed(ByteArray(32) { 0x11 }).publicKey
        val secret = ByteArray(32) { 0x22 }
        val uri = "lumenbox://pair#hostPub=${b64.encodeToString(hostPub)}" +
            "&relay=wss%3A%2F%2Frelay.example.com%2Fv1&route=rt%20one&pairSecret=${b64.encodeToString(secret)}" +
            "&direct=https%3A%2F%2Fmac.example.com%3A8443"
        val offer = ParsedPairingOffer.parse(uri)
        assertArrayEquals(hostPub, offer.hostPublicKey)
        assertEquals("wss://relay.example.com/v1", offer.relayUrl)
        assertEquals("rt one", offer.routeId)
        assertEquals(listOf("https://mac.example.com:8443"), offer.directUrls)
    }

    @Test
    fun oauthCallbackWithPercentEncodedValuesParses() {
        val client = PkceOAuthClient(unauthenticatedRoundTrip = { _, _ -> TransportResponse(500, emptyMap(), ByteArray(0)) })
        val ok = client.parseCallbackUri("lumenbox://oauth/callback?code=c%2F1%3D&state=s%20t")
        assertEquals("c/1=", ok.code)
        assertEquals("s t", ok.state)
        val denied = client.parseCallbackUri("lumenbox://oauth/callback?error=access_denied&error_description=%E6%8B%92%E7%BB%9D")
        assertEquals("access_denied", denied.error)
        assertEquals("拒绝", denied.errorDescription)
    }
}
