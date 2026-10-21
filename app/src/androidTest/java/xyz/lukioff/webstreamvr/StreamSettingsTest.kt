package xyz.lukioff.webstreamvr

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("stream_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun generatesAndPersistsRandomPortOnFirstLoad() {
        // First load: preference is empty
        val settings1 = StreamSettings.load(context)
        val port1 = settings1.httpPort

        assertTrue("Port $port1 should be in range 1024..65535", port1 in 1024..65535)

        // Second load: should load saved port from preferences
        val settings2 = StreamSettings.load(context)
        val port2 = settings2.httpPort

        assertEquals("Saved port should persist on subsequent loads", port1, port2)
    }
}
