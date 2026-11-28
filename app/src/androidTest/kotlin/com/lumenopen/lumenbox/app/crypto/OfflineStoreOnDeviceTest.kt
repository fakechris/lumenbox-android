package com.lumenopen.lumenbox.app.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumenopen.lumenbox.storage.ConnectionOfflineStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The per-connection offline cache sealed with Android's AEAD: opened, written, closed, reopened. */
@RunWith(AndroidJUnit4::class)
class OfflineStoreOnDeviceTest {
    @Test
    fun offlineCacheSurvivesCloseAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "offline-store-on-device").apply { deleteRecursively() }
        val dbKey = ByteArray(32) { (it * 7 + 3).toByte() }
        try {
            val first = ConnectionOfflineStore.open("conn-device", dir, dbKey.copyOf())
            first.putText("workspace", "overview", "sealed on the device")
            first.advanceDurableSeq(41)
            first.close()

            val reopened = ConnectionOfflineStore.open("conn-device", dir, dbKey.copyOf())
            assertEquals("sealed on the device", reopened.getText("workspace", "overview"))
            assertEquals(41L, reopened.durableSeq())
            reopened.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
