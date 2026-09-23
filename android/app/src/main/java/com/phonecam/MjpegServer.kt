package com.phonecam

import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Bounded client count, one pending frame per viewer, no camera-thread network IO. */
class MjpegServer(private val port: Int, private val code: String) {
    private data class Frame(val jpeg: ByteArray, val rotation: Int)
    private class Client(val socket: Socket) {
        val queue = ArrayBlockingQueue<Frame>(1)
        @Volatile var writingSince = 0L
    }
    private val clients = CopyOnWriteArrayList<Client>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var running = false
    @Volatile private var latest: Frame? = null
    private var listener: ServerSocket? = null
    private var discovery: DatagramSocket? = null
    val clientCount get() = clients.size
    @Volatile var frameCount = 0L; private set
    fun start() {
        listener = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(port)) }
        running = true
        thread(name = "phonecam-accept") {
            while (running) {
                val socket = try { listener?.accept() ?: break } catch (_: Exception) { break }
                if (sockets.size >= 4) { socket.close(); continue }
                sockets.add(socket)
                thread(name = "phonecam-http") { serve(socket) }
            }
        }
        thread(name = "phonecam-watchdog") {
            while (running) {
                val now = System.nanoTime()
                clients.filter { it.writingSince != 0L && now - it.writingSince > 2_000_000_000L }
                    .forEach { runCatching { it.socket.close() } }
                Thread.sleep(500)
            }
        }
        // Discovery is optional; a blocked UDP port must not prevent manual connection.
        runCatching { DatagramSocket(5888).also { discovery = it } }.getOrNull()?.let { udp ->
            thread(name = "phonecam-discovery") {
                val buffer = ByteArray(128)
                while (running) {
                    try {
                        val request = DatagramPacket(buffer, buffer.size); udp.receive(request)
                        if (String(request.data, 0, request.length, Charsets.US_ASCII) == "PHONECAM_DISCOVER_V1") {
                            val reply = "PHONECAM_V1:$port".toByteArray()
                            udp.send(DatagramPacket(reply, reply.size, request.address, request.port))
                        }
                    } catch (_: Exception) { break }
                }
            }
        }
    }
    fun stop() {
        running = false
        runCatching { listener?.close() }; listener = null
        discovery?.close(); discovery = null
        sockets.forEach { runCatching { it.close() } }; sockets.clear(); clients.clear(); latest = null
    }
    fun submit(jpeg: ByteArray, rotation: Int) {
        val frame = Frame(jpeg, rotation); latest = frame; frameCount++
        clients.forEach { it.queue.poll(); it.queue.offer(frame) }
    }
    private fun line(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 2048) {
            val n = input.read(); check(n >= 0) { "Disconnected" }
            if (n == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            bytes.add(n.toByte())
        }
        error("Request too long")
    }
    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 3000; socket.tcpNoDelay = true; socket.sendBufferSize = 64 * 1024
            val input = socket.getInputStream().buffered()
            val request = line(input).split(' ')
            check(request.size == 3 && request[0] == "GET")
            var headers = 0
            while (line(input).isNotEmpty()) check(++headers <= 32)
            val path = request[1].substringBefore('?')
            val query = request[1].substringAfter('?', "").split('&')
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            if ("code=$code" !in query) { response(out, "403 Forbidden", "Connection code required. Use the address displayed on your phone."); return }
            when (path) {
                "/stream" -> stream(socket, out)
                "/", "" -> response(out, "200 OK", page())
                "/rotation" -> response(out, "200 OK", (latest?.rotation ?: 0).toString(), "text/plain")
                else -> response(out, "404 Not Found", "Not found")
            }
        } catch (_: Exception) {
            // Closing the socket also interrupts a stalled writer during service shutdown.
        } finally { sockets.remove(socket); runCatching { socket.close() } }
    }
    private fun response(out: BufferedOutputStream, status: String, body: String, type: String = "text/html; charset=utf-8") {
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes); out.flush()
    }
    private fun stream(socket: Socket, out: BufferedOutputStream) {
        val client = Client(socket); clients.add(client)
        latest?.let { client.queue.offer(it) }
        try {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=phonecamframe\r\n" +
                "X-PhoneCam-Boundary: phonecamframe\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()); out.flush()
            while (running && !socket.isClosed) {
                val frame = client.queue.poll(1, TimeUnit.SECONDS) ?: continue
                client.writingSince = System.nanoTime()
                out.write(("--phonecamframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.jpeg.size}\r\n" +
                    "X-PhoneCam-Rotation: ${frame.rotation}\r\n\r\n").toByteArray())
                out.write(frame.jpeg); out.write(byteArrayOf(13, 10)); out.flush()
                client.writingSince = 0
            }
        } finally { clients.remove(client) }
    }
    private fun page() = """
        <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <title>PhoneCam</title><style>body{background:#101820;color:#eef5fa;font:16px system-ui;text-align:center;overflow:hidden}img{max-width:85vw;max-height:72vh;object-fit:contain;margin:5vh auto}footer{position:fixed;bottom:12px;width:100%}</style>
        <h2>PhoneCam · viewer</h2><img id="camera" src="/stream?code=$code"><footer>For video calls, select the PhoneCam camera on your PC.</footer>
        <script>setInterval(async()=>{try{let r=await(await fetch('/rotation?code=$code')).text();let n=Number(r);camera.style.transform='rotate('+n+'deg)';camera.style.maxWidth=(n%180?'65vh':'85vw');camera.style.maxHeight=(n%180?'85vw':'72vh')}catch(e){}},1000)</script></html>
    """.trimIndent()
}
