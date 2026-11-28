package com.lumenopen.lumenbox.app.crypto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumenopen.lumenbox.conformance.NoiseConformance
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** `:core`'s `NoiseConformanceTest` checks, run on Android's runtime and providers. */
@RunWith(AndroidJUnit4::class)
class NoiseConformanceOnDeviceTest {
    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { it.readText() }

    @Test
    fun rfc7748Vectors() {
        assertEquals(8, NoiseConformance.rfc7748())
    }

    @Test
    fun cacophonyNkAndIkVectors() {
        val counts = NoiseConformance.cacophony(asset("noise-cacophony-vectors.json"))
        Log.i("LumenBoxNoise", "cacophony vectors passed: $counts")
        assertEquals(mapOf(NoiseConformance.NK to 1, NoiseConformance.IK to 1), counts)
    }

    @Test
    fun refusals() {
        assertEquals(46, NoiseConformance.refusals(asset("relay-test-vectors.json")))
    }
}
