package xyz.lukioff.webstreamvr.web

import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tiny HTTP/1.1 server: static viewer files from assets/web, a JSON status
 * endpoint and the WebSocket stream at /ws. One thread per connection.
 */
class WebServer(
    private val assets: AssetManager,
    private val hub: ViewerHub,
    private val statusJson: () -> String,
) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val cache = HashMap<String, ByteArray>()

    @Throws(IOException::class)
    fun start(port: Int) {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port), 32)
        server = socket
        val executor = Executors.newCachedThreadPool { Thread(it, "WebServer-conn") }
        pool = executor
        Thread({ acceptLoop(socket, executor) }, "WebServer").start()
        Log.i(TAG, "Listening on port $port")
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
        hub.closeAll()
    }

    private fun acceptLoop(socket: ServerSocket, executor: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (e: IOException) {
                Log.w(TAG, "accept failed", e)
                continue
            }
            try {
                executor.execute { handle(client) }
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        var upgraded = false
        try {
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream(), 8192)
            val request = readRequest(input) ?: return
            val output = client.getOutputStream()
            val path = request.path.substringBefore('?')
            when {
                path == "/ws" && request.headers["upgrade"].equals("websocket", ignoreCase = true) -> {
                    val key = request.headers["sec-websocket-key"]
                    if (key == null) {
                        respond(output, 400, "text/plain", "Missing Sec-WebSocket-Key".toByteArray(), request.method)
                        return
                    }
                    output.write(
                        ("HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: ${WebSocket.acceptKey(key)}\r\n\r\n").toByteArray()
                    )
                    output.flush()
                    upgraded = true
                    client.soTimeout = 0
                    hub.serve(client, input, statusJson())
                }
                path == "/api/status" ->
                    respond(output, 200, "application/json; charset=utf-8", statusJson().toByteArray(), request.method)
                else -> serveAsset(output, path, request.method)
            }
        } catch (_: IOException) {
        } finally {
            if (!upgraded) runCatching { client.close() }
        }
    }

    private fun serveAsset(output: OutputStream, path: String, method: String) {
        val name = when (path) {
            "/", "" -> "index.html"
            else -> path.trimStart('/')
        }
        if (name.contains("..") || name.contains('\\')) {
            respond(output, 404, "text/plain", "Not found".toByteArray(), method)
            return
        }
        val body = loadAsset(name)
        if (body == null) {
            respond(output, 404, "text/plain; charset=utf-8", "Not found".toByteArray(), method)
            return
        }
        respond(output, 200, contentType(name), body, method)
    }

    private fun loadAsset(name: String): ByteArray? = synchronized(cache) {
        cache[name] ?: try {
            assets.open("web/$name").use { it.readBytes() }.also { cache[name] = it }
        } catch (_: IOException) {
            null
        }
    }

    private fun respond(output: OutputStream, status: Int, type: String, body: ByteArray, method: String) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray())
        if (method != "HEAD") output.write(body)
        output.flush()
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            if (headers.size > 64) return null
        }
        return Request(parts[0], parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    private fun contentType(name: String) = when (name.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        "json" -> "application/json"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "WebServer"
    }
}
