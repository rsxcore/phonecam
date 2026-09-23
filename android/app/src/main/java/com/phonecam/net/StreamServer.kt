package com.phonecam.net

import android.util.Log
import com.phonecam.stream.EncodedFrame
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** An unknown PC asking to use the camera; [decide] must be called exactly once. */
class PairRequest(val pcName: String, val code: String, val fingerprint: String, val decide: (Boolean) -> Unit)

/**
 * Serves one PC at a time over TLS. A new trusted connection replaces the old
 * one, so a PC that reconnects after a network hiccup never waits for a dead
 * socket to time out. Unknown PCs are held until the user approves them.
 *
 * Latency guard: the send queue holds at most [MAX_QUEUE_MS] of video. When
 * Wi-Fi stalls longer than that, queued video is dropped and the stream resumes
 * from the next key frame, instead of the picture falling further and further
 * behind real time.
 */
class StreamServer(
    private val context: android.content.Context,
    private val hello: () -> JSONObject,
    private val onPairRequest: (PairRequest?) -> Unit,
    private val onControl: (JSONObject) -> Unit,
    private val onClient: (String?) -> Unit,
    private val requestKeyFrame: () -> Unit,
) {
    private sealed class Out {
        class Raw(val type: Int, val parts: Array<ByteArray>) : Out()
        class Video(val frame: EncodedFrame, val rotation: Int, val at: Long) : Out()
    }

    private inner class Client(val socket: Socket) {
        val queue = LinkedBlockingDeque<Out>()
        @Volatile var waitingForKey = true
        @Volatile var closed = false

        fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }

    @Volatile private var running = false
    @Volatile private var client: Client? = null
    private var listener: ServerSocket? = null
    private var discovery: DatagramSocket? = null
    @Volatile private var config: ByteArray? = null

    val sentBytes = AtomicLong()
    val sentFrames = AtomicLong()
    val droppedFrames = AtomicLong()
    val connected get() = client != null

    private val fingerprint = Identity.fingerprint()

    fun start() {
        val ssl = Identity.sslContext()
        listener = (ssl.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            needClientAuth = true
            enabledProtocols = arrayOf("TLSv1.3")
            bind(InetSocketAddress(Protocol.PORT))
        }
        running = true
        thread(name = "phonecam-accept", isDaemon = true) {
            while (running) {
                val socket = try { listener?.accept() ?: break } catch (_: IOException) { break }
                thread(name = "phonecam-auth", isDaemon = true) { authenticate(socket as SSLSocket) }
            }
        }
        runCatching { DatagramSocket(Protocol.DISCOVERY_PORT).also { discovery = it } }.getOrNull()?.let { udp ->
            thread(name = "phonecam-discovery", isDaemon = true) {
                val buffer = ByteArray(256)
                while (running) {
                    try {
                        val request = DatagramPacket(buffer, buffer.size); udp.receive(request)
                        val text = String(request.data, 0, request.length, Charsets.US_ASCII)
                        if (text.startsWith("PHONECAM_DISCOVER")) {
                            val reply = "PHONECAM_V2:${Protocol.PORT}:${fingerprint.take(16)}:${android.os.Build.MODEL}".toByteArray()
                            udp.send(DatagramPacket(reply, reply.size, request.address, request.port))
                        }
                    } catch (_: IOException) { break }
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { listener?.close() }; listener = null
        discovery?.close(); discovery = null
        client?.close(); client = null
    }

    /**
     * New codec configuration (SPS/PPS/VPS with a small header) after the
     * encoder restarted. Video still queued from the old encoder is useless to
     * the new decoder, so it is dropped.
     */
    fun setConfig(payload: ByteArray) {
        config = payload
        client?.let {
            it.queue.removeIf { item -> item is Out.Video }
            it.waitingForKey = true
            it.queue.offer(Out.Raw(Protocol.CONFIG, arrayOf(payload)))
        }
    }

    fun sendState(state: JSONObject) {
        client?.let { enqueue(it, Out.Raw(Protocol.STATE, arrayOf(state.toString().toByteArray()))) }
    }

    fun submit(frame: EncodedFrame, rotation: Int) {
        val c = client ?: return
        if (c.waitingForKey) {
            if (!frame.key) { droppedFrames.incrementAndGet(); return }
            c.waitingForKey = false
        }
        val now = System.nanoTime()
        val oldest = c.queue.firstOrNull { it is Out.Video } as Out.Video?
        if (oldest != null && now - oldest.at > MAX_QUEUE_MS * 1_000_000L) {
            // The link cannot keep up: throw away stale video and restart from a key frame.
            val dropped = c.queue.count { it is Out.Video }
            c.queue.removeIf { it is Out.Video }
            droppedFrames.addAndGet(dropped.toLong() + 1)
            c.waitingForKey = true
            requestKeyFrame()
            Log.w(TAG, "Network congested, dropped $dropped queued frames")
            return
        }
        c.queue.offer(Out.Video(frame, rotation, now))
    }

    private fun enqueue(c: Client, out: Out) {
        // Control messages jump ahead of video so settings feel instant.
        c.queue.offerFirst(out)
    }

    /**
     * TLS handshake, then CLIENT_HELLO, then either straight to streaming for a
     * trusted PC or a pairing request the user answers on the phone.
     */
    private fun authenticate(socket: SSLSocket) {
        try {
            socket.soTimeout = 5000
            socket.tcpNoDelay = true
            socket.startHandshake()
            val pcFingerprint = Identity.fingerprint(socket.session.peerCertificates[0])
            val out = socket.outputStream
            out.write(Protocol.preamble()); out.flush()
            val first = Protocol.read(socket.inputStream)
            check(first.type == Protocol.CLIENT_HELLO) { "Expected CLIENT_HELLO" }
            val pcName = runCatching { JSONObject(String(first.payload)).optString("name", "PC") }.getOrDefault("PC").take(64)

            if (!Identity.isTrusted(context, pcFingerprint)) {
                val code = Identity.verificationCode(fingerprint, pcFingerprint)
                Protocol.write(out, Protocol.PAIRING, JSONObject().put("code", code).put("phone", android.os.Build.MODEL).toString().toByteArray())
                out.flush()
                val answer = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
                onPairRequest(PairRequest(pcName, code, pcFingerprint) { answer.offer(it) })
                val ok = answer.poll(90, TimeUnit.SECONDS) ?: false
                onPairRequest(null)
                Protocol.write(out, Protocol.PAIR_RESULT, JSONObject().put("ok", ok).toString().toByteArray())
                out.flush()
                if (!ok) { socket.close(); return }
                Identity.trust(context, pcFingerprint, pcName)
            }
            socket.soTimeout = 0
            attach(socket, pcName)
        } catch (e: Exception) {
            Log.i(TAG, "Connection rejected: ${e.message}")
            runCatching { socket.close() }
        }
    }

    private fun attach(socket: Socket, name: String) {
        client?.close()
        val c = Client(socket)
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 512 * 1024
            socket.soTimeout = 0
        } catch (_: IOException) { }
        client = c
        onClient(name)
        thread(name = "phonecam-writer", isDaemon = true) { writeLoop(c) }
        thread(name = "phonecam-reader", isDaemon = true) { readLoop(c) }
    }

    private fun detach(c: Client) {
        c.close()
        if (client === c) {
            client = null
            onClient(null)
        }
    }

    private fun writeLoop(c: Client) {
        try {
            val out = BufferedOutputStream(c.socket.getOutputStream(), 256 * 1024)
            Protocol.write(out, Protocol.HELLO, hello().toString().toByteArray())
            config?.let { Protocol.write(out, Protocol.CONFIG, it) }
            out.flush()
            requestKeyFrame()
            while (running && !c.closed) {
                val item = c.queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                when (item) {
                    is Out.Raw -> Protocol.write(out, item.type, *item.parts)
                    is Out.Video -> {
                        val f = item.frame
                        Protocol.write(out, Protocol.FRAME, Protocol.frameHeader(f.key, f.ptsUs, item.rotation), f.data)
                        sentBytes.addAndGet(f.data.size.toLong())
                        sentFrames.incrementAndGet()
                    }
                }
                if (c.queue.isEmpty()) out.flush()
            }
        } catch (e: IOException) {
            Log.i(TAG, "Client write ended: ${e.message}")
        } finally {
            detach(c)
        }
    }

    private fun readLoop(c: Client) {
        try {
            val input = c.socket.getInputStream().buffered()
            while (running && !c.closed) {
                val msg = Protocol.read(input)
                when (msg.type) {
                    Protocol.CONTROL -> runCatching { JSONObject(String(msg.payload)) }.getOrNull()?.let(onControl)
                    Protocol.PING -> enqueue(c, Out.Raw(Protocol.PONG, arrayOf(msg.payload)))
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Client read ended: ${e.message}")
        } finally {
            detach(c)
        }
    }

    companion object {
        private const val TAG = "PhoneCam.Server"
        const val MAX_QUEUE_MS = 300L
    }
}
