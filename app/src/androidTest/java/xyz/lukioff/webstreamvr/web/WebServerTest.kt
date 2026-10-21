package xyz.lukioff.webstreamvr.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class WebServerTest {

    private lateinit var server: WebServer
    private lateinit var hub: ViewerHub
    private var port: Int = 0

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        hub = ViewerHub {}
        server = WebServer(context.assets, hub, streamCode = "123456") { """{"type":"status"}""" }
        port = (20000..60000).random()
        server.start(port)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun rejectsUnauthorizedWebSocketConnection() {
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val req = "GET /ws?code=000000 HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
            out.write(req.toByteArray())
            out.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine()
            assertTrue("Expected 401 Unauthorized for invalid code, got: $statusLine", statusLine.contains("401"))
        }
    }

    @Test
    fun acceptsAuthorizedWebSocketConnection() {
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val req = "GET /ws?code=123456 HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
            out.write(req.toByteArray())
            out.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine()
            assertTrue("Expected 101 Switching Protocols for valid code, got: $statusLine", statusLine.contains("101"))
        }
    }

    @Test
    fun rejectsUnauthorizedStatusApiRequest() {
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val req = "GET /api/status?code=wrong HTTP/1.1\r\nHost: localhost\r\n\r\n"
            out.write(req.toByteArray())
            out.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine()
            assertTrue("Expected 401 for status API with wrong code, got: $statusLine", statusLine.contains("401"))
        }
    }

    @Test
    fun acceptsAuthorizedStatusApiRequest() {
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val req = "GET /api/status?code=123456 HTTP/1.1\r\nHost: localhost\r\n\r\n"
            out.write(req.toByteArray())
            out.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine()
            assertTrue("Expected 200 for status API with valid code, got: $statusLine", statusLine.contains("200"))
        }
    }
}
