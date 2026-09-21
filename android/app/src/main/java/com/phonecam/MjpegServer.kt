package com.phonecam

import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Serves the camera as MJPEG over HTTP.
 *
 * HTTP rather than a bespoke framing, because a browser renders
 * `multipart/x-mixed-replace` inside a plain `<img>` tag with no client-side
 * code at all. That means the phone can be checked from the PC before any
 * server exists — and the Rust side later reads exactly the bytes the browser
 * already proved were correct, so there is only ever one transport to debug.
 *
 * GET /         a page with the stream in an <img>
 * GET /stream   the stream itself
 */
class MjpegServer(private val port: Int) {

    private class Client {
        /** Capacity 1 on purpose: a client that cannot keep up misses frames
         *  instead of building a backlog of stale ones. */
        val queue = ArrayBlockingQueue<ByteArray>(1)

        @Volatile
        var dead = false
    }

    private val clients = CopyOnWriteArrayList<Client>()

    @Volatile
    private var running = false

    /**
     * Degrees clockwise still needed to stand the picture up. Frames leave the
     * phone unrotated, so both consumers need to know: the browser applies it
     * as CSS, the server applies it while decoding. Served in the stream
     * response headers and inlined into the page.
     */
    @Volatile
    var rotationDegrees: Int = 0
        private set

    private var serverSocket: ServerSocket? = null

    val clientCount: Int get() = clients.size

    fun start() {
        if (running) return
        serverSocket = ServerSocket(port)
        running = true
        thread(name = "mjpeg-accept", isDaemon = true) { acceptLoop() }
    }

    fun stop() {
        running = false
        // Closing the socket is what unblocks accept(); interrupting it is not
        // enough on all platforms.
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (c in clients) c.dead = true
        clients.clear()
    }

    /** Hands a frame to every client. Called from the camera thread. */
    fun submit(jpeg: ByteArray, rotationDegrees: Int) {
        this.rotationDegrees = rotationDegrees
        for (c in clients) {
            c.queue.poll()
            c.queue.offer(jpeg)
        }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (e: IOException) {
                break // closed under us, which is how stop() is meant to work
            }
            thread(name = "mjpeg-client", isDaemon = true) { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            // Browsers open speculative connections and then say nothing. A
            // blocking readLine() with no timeout parks a thread on each one
            // for as long as the socket lives.
            socket.soTimeout = REQUEST_TIMEOUT_MS
            val reader = socket.getInputStream().bufferedReader()

            val requestLine = reader.readLine() ?: return
            // Drain the headers. GET carries no body, so nothing is lost by
            // stopping at the blank line even though the reader may have
            // buffered past it.
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }

            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            val out = BufferedOutputStream(socket.getOutputStream())

            if (path.startsWith("/stream")) {
                stream(socket, out)
            } else {
                val body = page().toByteArray()
                out.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray()
                )
                out.write(body)
                out.flush()
            }
        } catch (e: IOException) {
            // The client hung up. Nothing to do and nothing worth logging at
            // this stage.
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun stream(socket: Socket, out: BufferedOutputStream) {
        // A stream is long-lived and write-only from here, so the request
        // timeout has done its job.
        runCatching { socket.soTimeout = 0 }

        val client = Client()
        clients.add(client)
        try {
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                        // Not a standard header, but this stream is not a
                        // standard stream either: the frames are unrotated and
                        // whoever decodes them has to know by how much.
                        "X-PhoneCam-Rotation: $rotationDegrees\r\n" +
                        "X-PhoneCam-Boundary: $BOUNDARY\r\n" +
                        "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            out.flush()

            while (running && !client.dead) {
                // Poll rather than take, so a client that goes away is noticed
                // even when the camera has stopped sending.
                val frame = client.queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                out.write(
                    (
                        "--$BOUNDARY\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${frame.size}\r\n\r\n"
                        ).toByteArray()
                )
                out.write(frame)
                out.write(CRLF)
                out.flush()
            }
        } catch (e: IOException) {
            // Client gone.
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            clients.remove(client)
            runCatching { socket.close() }
        }
    }

    private fun page(): String {
        // The frames arrive unrotated, so the page stands them up itself —
        // the same correction the PC server will make while decoding, which is
        // why both are driven by the one number the phone reports.
        val rotation = rotationDegrees
        val sideways = rotation == 90 || rotation == 270
        val box = if (sideways) {
            "max-width: 78vh; max-height: 94vw;"
        } else {
            "max-width: 94vw; max-height: 78vh;"
        }

        return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>PhoneCam</title>
          <style>
            html, body { margin: 0; height: 100%; background: #14181e; color: #e8e8e8;
                         font: 15px/1.5 system-ui, sans-serif; }
            main { height: 100%; display: flex; flex-direction: column;
                   align-items: center; justify-content: center; gap: 14px; }
            img { background: #000; border: 1px solid #2c333d; border-radius: 6px;
                  transform: rotate(${rotation}deg); $box }
            p { margin: 0; color: #8d98a6; }
          </style>
        </head>
        <body>
          <main>
            <img src="/stream" alt="PhoneCam stream">
            <p>PhoneCam &mdash; served by the phone itself, rotated ${rotation}&deg; for display.</p>
          </main>
        </body>
        </html>
        """.trimIndent()
    }

    private companion object {
        const val BOUNDARY = "phonecamframe"
        const val REQUEST_TIMEOUT_MS = 5000
        val CRLF = "\r\n".toByteArray()
    }
}
