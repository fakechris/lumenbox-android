package com.lumenopen.lumenbox.app.crypto

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

internal fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
}

internal fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** The server-generated relay vectors `:core`'s JVM tests use, packaged as androidTest assets. */
internal fun relayVectors(): JSONObject {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    val text = assets.open("relay-test-vectors.json").bufferedReader().use { it.readText() }
    return JSONObject(text)
}
